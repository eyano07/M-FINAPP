package com.mbsc.desktop.local;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Gestionnaire unique de la base H2 locale.
 * Fournit des helpers transactionnels pour les DAOs.
 */
public final class LocalDatabase {

    private static final Logger log = LoggerFactory.getLogger(LocalDatabase.class);

    private static final EntityManagerFactory EMF =
        Persistence.createEntityManagerFactory("mbsc-local");

    private LocalDatabase() {}

    public static EntityManager em() {
        return EMF.createEntityManager();
    }

    /** Execute une operation transactionnelle qui retourne un resultat. */
    public static <T> T inTransaction(Function<EntityManager, T> work) {
        EntityManager em = em();
        try {
            em.getTransaction().begin();
            T result = work.apply(em);
            em.getTransaction().commit();
            return result;
        } catch (RuntimeException ex) {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            log.error("Transaction H2 annulee (rollback) : {}", ex.getMessage());
            throw ex;
        } finally {
            em.close();
        }
    }

    /** Execute une operation transactionnelle sans resultat. */
    public static void inTransaction(Consumer<EntityManager> work) {
        inTransaction(em -> {
            work.accept(em);
            return null;
        });
    }

    public static void shutdown() {
        if (EMF.isOpen()) {
            EMF.close();
        }
    }
}
