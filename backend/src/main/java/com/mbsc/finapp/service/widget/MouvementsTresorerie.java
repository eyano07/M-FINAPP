package com.mbsc.finapp.service.widget;

import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.service.push.PushService;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mise à jour automatique des widgets Android (18/11/2026), par un message Firebase silencieux (ni son ni bandeau) qui
 * fait relire au widget son résumé :
 * <ul>
 *   <li>une note de frais change (création, soumission, vérification, validation, rejet, transmission, paiement…) :
 *       tous les comptes dont un téléphone a les widgets, sous {@value #VERIFICATION_MS} ms ;</li>
 *   <li>un mouvement de trésorerie (écriture sur un compte de classe 5, pièce comptabilisée, annulée, restaurée) : les
 *       comptes qui ont activé le widget Trésorerie, au plus une fois toutes les {@value #INTERVALLE_TRESORERIE_MS} ms
 *       (une vente produit plusieurs écritures).</li>
 * </ul>
 * Un changement annulé par un retour arrière ne provoque qu'une relecture inutile, sans effet.
 */
@Component
public class MouvementsTresorerie {

    private static final Logger log = LoggerFactory.getLogger(MouvementsTresorerie.class);
    static final long VERIFICATION_MS = 5_000;
    static final long INTERVALLE_TRESORERIE_MS = 20_000;
    /** Levés par les écouteurs JPA (qui ne sont pas des beans Spring), lus par la tâche planifiée. */
    private static final AtomicBoolean MOUVEMENT = new AtomicBoolean(false);
    private static final AtomicBoolean NOTE = new AtomicBoolean(false);

    private final WidgetService widgets;
    private final PushService push;
    private final com.mbsc.finapp.repository.JetonWidgetRepository jetons;
    private long dernierEnvoiTresorerie;

    public MouvementsTresorerie(WidgetService widgets, PushService push,
                                com.mbsc.finapp.repository.JetonWidgetRepository jetons) {
        this.widgets = widgets;
        this.push = push;
        this.jetons = jetons;
    }

    static void signaler() {
        MOUVEMENT.set(true);
    }

    static void signalerNote() {
        NOTE.set(true);
    }

    static boolean concerneLaTresorerie(String compte) {
        return compte != null && compte.startsWith("5");
    }

    @Scheduled(fixedDelay = VERIFICATION_MS, initialDelay = VERIFICATION_MS)
    public void prevenir() {
        try {
            java.util.Set<Long> comptes = new java.util.LinkedHashSet<>();
            if (NOTE.getAndSet(false)) comptes.addAll(jetons.comptesAvecWidget());
            long maintenant = System.currentTimeMillis();
            if (maintenant - dernierEnvoiTresorerie >= INTERVALLE_TRESORERIE_MS && MOUVEMENT.getAndSet(false)) {
                dernierEnvoiTresorerie = maintenant;
                comptes.addAll(widgets.abonnesTresorerie());
            }
            // Un seul message par téléphone : il met à jour ses deux widgets.
            if (!comptes.isEmpty()) push.rafraichirWidgets(List.copyOf(comptes));
        } catch (Exception e) {
            log.warn("Mise à jour des widgets non envoyée : {}", e.getMessage());
        }
    }

    /** Notes de frais : tout changement (étape, montant, objet…) peut modifier le widget de plusieurs comptes. */
    public static class EcouteNote {
        @PostPersist
        @PostUpdate
        @PostRemove
        void apres(Object note) {
            signalerNote();
        }
    }

    /** Écritures du grand livre. */
    public static class Ecoute {
        @PostPersist
        @PostUpdate
        @PostRemove
        void apres(EcritureGrandLivre e) {
            try {
                if (e.getCompte() != null && concerneLaTresorerie(e.getCompte().getNumero())) signaler();
            } catch (RuntimeException ignore) {
                signaler();   // compte non chargé : on prévient par prudence
            }
        }
    }

    /** Pièces comptables : changement de statut (comptabilisée, annulée, restaurée). */
    public static class EcoutePiece {
        @PostUpdate
        @PostRemove
        void apres(Object piece) {
            signaler();
        }
    }
}
