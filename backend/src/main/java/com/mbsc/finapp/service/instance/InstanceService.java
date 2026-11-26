package com.mbsc.finapp.service.instance;

import com.mbsc.finapp.domain.ParametresInstance;
import com.mbsc.finapp.domain.enums.ModeInstance;
import com.mbsc.finapp.repository.ParametresInstanceRepository;
import com.mbsc.finapp.service.ia.ChiffrementSecretService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Mode de ce serveur (en ligne, local, copie en ligne, ancien local : voir {@link ModeInstance}) et appairage avec l'autre
 * serveur (V132). L'etat est garde en memoire : les filtres le lisent a chaque requete. Seule cette classe ecrit la ligne,
 * toujours dans sa propre transaction (elle est appelee depuis des filtres, des taches planifiees et des transactions
 * metier, parfois en lecture seule). Aucune verification de droits ici : voir {@code InstanceAdminService}.
 */
@Service
public class InstanceService {

    private static final Logger log = LoggerFactory.getLogger(InstanceService.class);

    private final ParametresInstanceRepository repository;
    private final ChiffrementSecretService chiffrement;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    private volatile ParametresInstance cache;
    private volatile Integer versionSchema;

    public InstanceService(ParametresInstanceRepository repository, ChiffrementSecretService chiffrement, JdbcTemplate jdbc,
                           PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.chiffrement = chiffrement;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Etat courant (copie detachee : ne pas la modifier, passer par {@link #modifier}). */
    public ParametresInstance etat() {
        ParametresInstance e = cache;
        if (e == null) {
            e = charger();
            cache = e;
        }
        return e;
    }

    private ParametresInstance charger() {
        ParametresInstance p = tx.execute(s -> repository.findById(ParametresInstance.SINGLETON_ID)
            .orElseGet(() -> repository.save(ParametresInstance.builder().id(ParametresInstance.SINGLETON_ID).build())));
        return p.toBuilder().build();
    }

    public ModeInstance mode() {
        return etat().getMode();
    }

    /** Vrai si les saisies sont refusees ici (copie en ligne, ancien serveur local). */
    public boolean lectureSeule() {
        return mode().lectureSeule();
    }

    public boolean estMiroir() {
        return mode() == ModeInstance.MIROIR;
    }

    public boolean estLocal() {
        return mode() == ModeInstance.LOCAL;
    }

    /** Modifie la ligne dans sa propre transaction ; {@code auteur} : identifiant de connexion, ou null (systeme). */
    public ParametresInstance modifier(Consumer<ParametresInstance> maj, String auteur) {
        ParametresInstance apres = tx.execute(s -> {
            ParametresInstance p = repository.findById(ParametresInstance.SINGLETON_ID)
                .orElseGet(() -> ParametresInstance.builder().id(ParametresInstance.SINGLETON_ID).build());
            maj.accept(p);
            if (auteur != null) p.setModifiePar(auteur);
            p.setDateMaj(Instant.now());
            return repository.save(p);
        });
        cache = apres.toBuilder().build();
        return cache;
    }

    /** Oublie l'etat en memoire (relu a la prochaine demande). */
    public void oublier() {
        cache = null;
    }

    /** Cle d'appairage dechiffree, si une cle est enregistree. */
    public Optional<SignatureReplication.Cle> cle() {
        ParametresInstance p = etat();
        if (p.getCleChiffree() == null) return Optional.empty();
        try {
            return Optional.of(SignatureReplication.lire(chiffrement.dechiffrer(p.getCleChiffree())));
        } catch (RuntimeException e) {
            log.warn("Cle d'appairage illisible (cle de chiffrement du serveur changee ?) : {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Chiffre une cle d'appairage pour l'enregistrer. */
    public String chiffrer(String texte) {
        return chiffrement.chiffrer(texte);
    }

    /** Version du schema de la base (derniere migration Flyway appliquee) : les deux serveurs doivent l'avoir egale. */
    public int versionSchema() {
        Integer v = versionSchema;
        if (v == null) {
            v = jdbc.queryForObject("select coalesce(max(version::int), 0) from flyway_schema_history "
                + "where success and version ~ '^[0-9]+$'", Integer.class);
            versionSchema = v;
        }
        return v;
    }

    /** Note une erreur de synchronisation (affichee a l'administrateur), sans changer le reste. */
    public void noterErreur(String message) {
        String texte = message == null ? null : (message.length() > 1000 ? message.substring(0, 1000) : message);
        ParametresInstance e = etat();
        if (java.util.Objects.equals(texte, e.getDerniereErreur())) return;
        modifier(p -> {
            p.setDerniereErreur(texte);
            p.setDerniereErreurLe(texte == null ? null : Instant.now());
        }, null);
    }

    /** Note une synchronisation reussie (efface l'erreur). */
    public void noterSucces() {
        modifier(p -> {
            p.setDerniereSynchro(Instant.now());
            p.setDerniereErreur(null);
            p.setDerniereErreurLe(null);
        }, null);
    }

    /** Cote en ligne : note le dernier appel recu du serveur local (au plus une ecriture par minute). */
    public void noterContact() {
        Instant dernier = etat().getDernierContact();
        if (dernier != null && dernier.isAfter(Instant.now().minusSeconds(60))) return;
        modifier(p -> p.setDernierContact(Instant.now()), null);
    }
}
