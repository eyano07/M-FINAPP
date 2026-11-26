package com.mbsc.finapp.service.instance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Serveur local : chaque ligne du journal d'audit est aussi mise de cote ({@code audit_a_envoyer}) pour etre recopiee
 * sur le serveur en ligne (copie hors site du journal, qui ne se supprime jamais). La ligne est notee dans sa propre
 * transaction : une action metier annulee n'efface pas la trace de la tentative, comme dans le fichier.
 */
@Component
public class CopieAuditServeurLocal {

    private static final Logger log = LoggerFactory.getLogger(CopieAuditServeurLocal.class);

    private final InstanceService instance;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public CopieAuditServeurLocal(InstanceService instance, JdbcTemplate jdbc, PlatformTransactionManager tm) {
        this.instance = instance;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Met la ligne de cote si ce serveur est le serveur local (sinon rien). Ne leve jamais d'exception. */
    public void noter(String ligneJson) {
        try {
            if (!instance.estLocal()) return;
            tx.executeWithoutResult(s -> jdbc.update("insert into audit_a_envoyer (ligne) values (?)", ligneJson));
        } catch (RuntimeException e) {
            log.warn("Ligne d'audit non mise de cote pour le serveur en ligne : {}", e.toString());
        }
    }
}
