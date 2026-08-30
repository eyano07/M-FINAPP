package com.mbsc.desktop.local;

import java.util.List;

import com.mbsc.desktop.model.LocalEcriture;
import com.mbsc.desktop.model.LocalTransaction;

/** Acces aux transactions de caisse stockees en H2. */
public class TransactionDao {

    public LocalTransaction save(LocalTransaction tx) {
        return LocalDatabase.inTransaction(em -> {
            em.persist(tx);
            return tx;
        });
    }

    public List<LocalTransaction> findAll() {
        try (var em = LocalDatabase.em()) {
            return em.createQuery(
                "select t from LocalTransaction t order by t.dateOperation desc",
                LocalTransaction.class).getResultList();
        }
    }

    /** Ecritures du grand livre, transaction la plus recente en premier, debit avant credit. */
    public List<LocalEcriture> findAllEcritures() {
        try (var em = LocalDatabase.em()) {
            return em.createQuery(
                "select e from LocalEcriture e join fetch e.transaction t "
                    + "order by t.dateOperation desc, e.id asc",
                LocalEcriture.class).getResultList();
        }
    }

    public List<LocalTransaction> findNonSynced() {
        try (var em = LocalDatabase.em()) {
            return em.createQuery(
                "select t from LocalTransaction t where t.synced = false",
                LocalTransaction.class).getResultList();
        }
    }

    public long countNonSynced() {
        try (var em = LocalDatabase.em()) {
            return em.createQuery(
                "select count(t) from LocalTransaction t where t.synced = false",
                Long.class).getSingleResult();
        }
    }

    public void markSynced(String uuid) {
        LocalDatabase.inTransaction(em -> {
            em.createQuery("update LocalTransaction t set t.synced = true where t.uuid = :u")
              .setParameter("u", uuid)
              .executeUpdate();
        });
    }

    /** Nombre de decaissements enregistres aujourd'hui (minuit local -> maintenant). */
    public long countDecaissementAujourdhui() {
        try (var em = LocalDatabase.em()) {
            java.time.Instant debutJour = java.time.LocalDate.now()
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
            return em.createQuery(
                    "select count(t) from LocalTransaction t"
                    + " where t.sens = :s and t.dateOperation >= :d",
                    Long.class)
                .setParameter("s", com.mbsc.desktop.model.SensTransaction.DECAISSEMENT)
                .setParameter("d", debutJour)
                .getSingleResult();
        }
    }
}
