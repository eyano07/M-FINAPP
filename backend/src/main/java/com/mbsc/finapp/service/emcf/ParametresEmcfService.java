package com.mbsc.finapp.service.emcf;

import com.mbsc.finapp.domain.GroupeTaxeDgi;
import com.mbsc.finapp.domain.ParametresEmcf;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.repository.GroupeTaxeDgiRepository;
import com.mbsc.finapp.repository.ParametresEmcfRepository;
import com.mbsc.finapp.service.ia.ChiffrementSecretService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/** Paramètres du dispositif e-MCF (jeton chiffré) et groupes de taxation DGI. Accès en transaction propre. */
@Service
public class ParametresEmcfService {

    private final ParametresEmcfRepository repository;
    private final GroupeTaxeDgiRepository groupes;
    private final ChiffrementSecretService chiffrement;
    private final TransactionTemplate tx;

    private volatile ConfigEmcf cache;

    public ParametresEmcfService(ParametresEmcfRepository repository, GroupeTaxeDgiRepository groupes,
                                 ChiffrementSecretService chiffrement, PlatformTransactionManager tm) {
        this.repository = repository;
        this.groupes = groupes;
        this.chiffrement = chiffrement;
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ConfigEmcf config() {
        ConfigEmcf c = cache;
        if (c == null) {
            ParametresEmcf p = ligne();
            c = p == null ? new ConfigEmcf(false, "SIMULATION", null, null, null, 10000)
                : new ConfigEmcf(p.isActif(), p.getMode(), blanc(p.getUrlBase()), chiffrement.dechiffrer(p.getJetonChiffre()),
                    blanc(p.getNumeroDef()), p.getDelaiMs());
            cache = c;
        }
        return c;
    }

    public ParametresEmcf ligne() {
        return tx.execute(s -> repository.findById(ParametresEmcf.SINGLETON_ID).orElse(null));
    }

    public void modifier(Consumer<ParametresEmcf> maj, User auteur) {
        tx.executeWithoutResult(s -> {
            ParametresEmcf p = repository.findById(ParametresEmcf.SINGLETON_ID)
                .orElseGet(() -> ParametresEmcf.builder().id(ParametresEmcf.SINGLETON_ID).build());
            maj.accept(p);
            p.setModifiePar(auteur);
            p.setDateMaj(Instant.now());
            repository.save(p);
        });
        cache = null;
    }

    public String chiffrer(String clair) {
        return chiffrement.chiffrer(clair);
    }

    public List<GroupeTaxeDgi> groupes() {
        return tx.execute(s -> groupes.findAllByOrderByOrdreAscCodeAsc());
    }

    public void enregistrerGroupes(List<GroupeTaxeDgi> liste) {
        tx.executeWithoutResult(s -> groupes.saveAll(liste));
    }

    public static String fin(String secret) {
        if (!StringUtils.hasText(secret)) return null;
        String s = secret.strip();
        return s.length() <= 4 ? s : s.substring(s.length() - 4);
    }

    private static String blanc(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
