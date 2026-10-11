package com.mbsc.finapp.service;

import com.mbsc.finapp.exception.TransitionInvalideException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Tâche planifiée des plats périmés : toutes les 30 minutes, elle retire du stock les portions périmées ou prévient qu'il y en
 * a, selon le mode choisi par l'administrateur (voir {@link PlatsPerimesAutomatiqueService}). Une portion devient périmée le
 * lendemain de sa date d'expiration ; l'application tourne en UTC, donc le basculement se fait à 02 h, heure de Lubumbashi,
 * et la tâche s'en aperçoit dans la demi-heure.
 *
 * <p>Séparée du service pour que celui-ci passe par son proxy transactionnel : l'échec d'un retrait annule toute sa
 * transaction, y compris les notifications, et l'alerte qui le signale doit partir dans une transaction à part.</p>
 */
@Component
@RequiredArgsConstructor
public class PlatsPerimesPlanificateur {

    private static final Logger log = LoggerFactory.getLogger(PlatsPerimesPlanificateur.class);

    private static final int LONGUEUR_CAUSE = 200;

    private final PlatsPerimesAutomatiqueService service;

    /** Toutes les 30 minutes, la première 5 minutes après le démarrage ; réglable par {@code app.plats-perimes.*} (application.yml). */
    @Scheduled(fixedDelayString = "${app.plats-perimes.intervalle-ms:1800000}",
               initialDelayString = "${app.plats-perimes.delai-initial-ms:300000}")
    public void verifier() {
        LocalDate jour = LocalDate.now();
        try {
            PlatsPerimesAutomatiqueService.Issue issue = service.traiter(jour);
            log.debug("Vérification des plats périmés : {}", issue);
        } catch (RuntimeException e) {
            if (estRegleMetier(e)) {   // période clôturée, compte manquant... : le message suffit, la pile n'apprend rien
                log.warn("Vérification des plats périmés impossible : {}", e.getMessage());
            } else {
                log.error("Vérification des plats périmés en échec", e);
            }
            try {
                service.signalerEchec(jour, cause(e));
            } catch (RuntimeException e2) {
                log.error("Alerte de l'échec du retrait des plats périmés impossible", e2);
            }
        }
    }

    /**
     * Ce que l'on dit au responsable : le message d'une règle métier (période clôturée, compte manquant...) est écrit pour
     * lui ; une erreur technique ne lui apprend rien et reste dans les journaux du serveur.
     */
    static String cause(RuntimeException e) {
        String message = estRegleMetier(e) && e.getMessage() != null ? e.getMessage().strip() : "";
        if (message.isEmpty()) {
            return "erreur technique, voir les journaux du serveur";
        }
        return message.length() > LONGUEUR_CAUSE ? message.substring(0, LONGUEUR_CAUSE - 1) + "…" : message;
    }

    /** Un refus d'une règle de gestion, au message écrit pour l'utilisateur, plutôt qu'une panne. */
    private static boolean estRegleMetier(RuntimeException e) {
        return e instanceof IllegalStateException || e instanceof IllegalArgumentException || e instanceof TransitionInvalideException;
    }
}
