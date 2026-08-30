package com.mbsc.desktop.local;

import com.mbsc.desktop.model.SyncOutbox;
import com.mbsc.desktop.model.SyncStatus;

import java.time.Instant;
import java.util.List;

/** Acces a la file de synchronisation (Outbox). */
public class OutboxDao {

    public SyncOutbox enqueue(SyncOutbox entry) {
        return LocalDatabase.inTransaction(em -> {
            em.persist(entry);
            return entry;
        });
    }

    /** Entrees a (re)envoyer : PENDING ou FAILED, par ordre chronologique. */
    public List<SyncOutbox> findEnvoyables(int limit) {
        try (var em = LocalDatabase.em()) {
            return em.createQuery(
                    "select o from SyncOutbox o " +
                    "where o.statut in (:p, :f) order by o.dateCreation asc",
                    SyncOutbox.class)
                .setParameter("p", SyncStatus.PENDING)
                .setParameter("f", SyncStatus.FAILED)
                .setMaxResults(limit)
                .getResultList();
        }
    }

    public long countPending() {
        try (var em = LocalDatabase.em()) {
            return em.createQuery(
                    "select count(o) from SyncOutbox o where o.statut in (:p, :f)",
                    Long.class)
                .setParameter("p", SyncStatus.PENDING)
                .setParameter("f", SyncStatus.FAILED)
                .getSingleResult();
        }
    }

    public void markSent(Long id) {
        LocalDatabase.inTransaction(em -> {
            SyncOutbox o = em.find(SyncOutbox.class, id);
            if (o != null) {
                o.setStatut(SyncStatus.SENT);
                o.setDateEnvoi(Instant.now());
            }
        });
    }

    public void markFailed(Long id, String erreur) {
        LocalDatabase.inTransaction(em -> {
            SyncOutbox o = em.find(SyncOutbox.class, id);
            if (o != null) {
                o.incrementTentatives(erreur);
                o.setStatut(SyncStatus.FAILED);
            }
        });
    }

    public void markConflict(Long id, String erreur) {
        LocalDatabase.inTransaction(em -> {
            SyncOutbox o = em.find(SyncOutbox.class, id);
            if (o != null) {
                o.incrementTentatives(erreur);
                o.setStatut(SyncStatus.CONFLICT);
            }
        });
    }
}
