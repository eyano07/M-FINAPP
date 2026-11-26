package com.mbsc.finapp.service.mail;

import com.mbsc.finapp.domain.ParametresMail;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.repository.ParametresMailRepository;
import com.mbsc.finapp.service.ia.ChiffrementSecretService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * Paramètres de messagerie : configuration effective (écran Administration d'abord, variables d'environnement
 * {@code APP_MAIL_*} en secours tant qu'aucune ligne n'a été enregistrée) et mise à jour. Les accès à la base se
 * font dans leur propre transaction : l'envoi d'un e-mail est déclenché depuis des transactions métier, parfois
 * en lecture seule.
 */
@Service
public class ParametresMailService {

    private final ParametresMailRepository repository;
    private final ChiffrementSecretService chiffrement;
    private final TransactionTemplate tx;

    private final boolean envActif;
    private final String envHote;
    private final int envPort;
    private final String envUtilisateur;
    private final String envMotDePasse;
    private final boolean envStartTls;
    private final String envExpediteur;
    private final String envUrlPublique;

    private volatile ConfigMail cache;
    /** Dernier échec d'envoi (mémoire : sert aussi quand la config vient de l'environnement). */
    private volatile String derniereErreur;
    private volatile Instant derniereErreurLe;

    public ParametresMailService(ParametresMailRepository repository, ChiffrementSecretService chiffrement,
                                 PlatformTransactionManager transactionManager,
                                 @Value("${app.mail.enabled:false}") boolean envActif,
                                 @Value("${spring.mail.host:}") String envHote,
                                 @Value("${spring.mail.port:587}") int envPort,
                                 @Value("${spring.mail.username:}") String envUtilisateur,
                                 @Value("${spring.mail.password:}") String envMotDePasse,
                                 @Value("${spring.mail.properties.mail.smtp.starttls.enable:true}") boolean envStartTls,
                                 @Value("${app.mail.from:}") String envExpediteur,
                                 @Value("${app.public-url:}") String envUrlPublique) {
        this.repository = repository;
        this.chiffrement = chiffrement;
        this.envActif = envActif;
        this.envHote = envHote;
        this.envPort = envPort;
        this.envUtilisateur = envUtilisateur;
        this.envMotDePasse = envMotDePasse;
        this.envStartTls = envStartTls;
        this.envExpediteur = envExpediteur;
        this.envUrlPublique = envUrlPublique;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ConfigMail config() {
        ConfigMail c = cache;
        if (c == null) {
            c = charger();
            cache = c;
        }
        return c;
    }

    private ConfigMail charger() {
        ParametresMail p = ligne();
        if (p != null) {
            return new ConfigMail(p.isActif(), blanc(p.getHote()), p.getPort(), p.getSecurite(), blanc(p.getUtilisateur()),
                chiffrement.dechiffrer(p.getMotDePasseChiffre()), blanc(p.getExpediteur()), nettoyerUrl(p.getUrlPublique()));
        }
        return new ConfigMail(envActif, blanc(envHote), envPort, envStartTls ? "STARTTLS" : "AUCUNE", blanc(envUtilisateur),
            blanc(envMotDePasse), blanc(envExpediteur), nettoyerUrl(envUrlPublique));
    }

    /** Ligne en base (null tant que l'administrateur n'a rien enregistré). */
    public ParametresMail ligne() {
        return tx.execute(s -> repository.findById(ParametresMail.SINGLETON_ID).orElse(null));
    }

    /** true si la configuration vient des variables d'environnement et non de l'écran Administration. */
    public boolean viaEnvironnement() {
        return ligne() == null && (StringUtils.hasText(envHote) || envActif);
    }

    public void modifier(Consumer<ParametresMail> maj, User auteur) {
        tx.executeWithoutResult(s -> {
            ParametresMail p = repository.findById(ParametresMail.SINGLETON_ID)
                .orElseGet(() -> amorcer());
            maj.accept(p);
            p.setModifiePar(auteur);
            p.setDateMaj(Instant.now());
            repository.save(p);
        });
        cache = null;
    }

    /** Première sauvegarde : reprend les valeurs d'environnement pour ne rien perdre de ce qui fonctionnait. */
    private ParametresMail amorcer() {
        ParametresMail p = ParametresMail.builder().id(ParametresMail.SINGLETON_ID).build();
        p.setActif(envActif);
        p.setHote(blanc(envHote));
        p.setPort(envPort);
        p.setSecurite(envStartTls ? "STARTTLS" : "AUCUNE");
        p.setUtilisateur(blanc(envUtilisateur));
        if (StringUtils.hasText(envMotDePasse)) {
            p.setMotDePasseChiffre(chiffrement.chiffrer(envMotDePasse));
            p.setMotDePasseFin(fin(envMotDePasse));
        }
        p.setExpediteur(blanc(envExpediteur));
        p.setUrlPublique(nettoyerUrl(envUrlPublique));
        return p;
    }

    public String chiffrer(String clair) {
        return chiffrement.chiffrer(clair);
    }

    /** Mémorise le résultat du dernier envoi : {@code null} = succès (efface l'erreur). */
    public void noterEnvoi(String erreur) {
        if (java.util.Objects.equals(erreur, derniereErreur)) return;
        derniereErreur = erreur;
        derniereErreurLe = erreur == null ? null : Instant.now();
        try {
            tx.executeWithoutResult(s -> repository.findById(ParametresMail.SINGLETON_ID).ifPresent(p -> {
                p.setDerniereErreur(erreur);
                p.setDerniereErreurLe(derniereErreurLe);
                repository.save(p);
            }));
        } catch (RuntimeException ignore) {
            // Pas bloquant : l'erreur reste disponible en mémoire.
        }
    }

    public String derniereErreur() {
        return derniereErreur;
    }

    public Instant derniereErreurLe() {
        return derniereErreurLe;
    }

    /** Recharge l'erreur mémorisée en base au démarrage de l'écran (après redémarrage de l'application). */
    public void restaurerErreur() {
        if (derniereErreur != null) return;
        ParametresMail p = ligne();
        if (p != null) {
            derniereErreur = p.getDerniereErreur();
            derniereErreurLe = p.getDerniereErreurLe();
        }
    }

    public static String fin(String secret) {
        if (secret == null || secret.isBlank()) return null;
        String s = secret.strip();
        return s.length() <= 4 ? s : s.substring(s.length() - 4);
    }

    static String nettoyerUrl(String u) {
        return StringUtils.hasText(u) ? u.strip().replaceAll("/+$", "") : null;
    }

    private static String blanc(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
