package com.mbsc.finapp.config;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Fuseau horaire de l'entreprise (audit SYSCOHADA du 10/11/2026, C-23) : la date comptable d'une opération de caisse,
 * de banque ou de mobile money est le jour où elle a eu lieu sur place, pas le jour UTC. Une opération saisie à
 * 0 h 30 à Lubumbashi (UTC+2) était datée de la veille.
 *
 * <p>Réglé par la variable d'environnement {@code APP_FUSEAU_HORAIRE} (ex. {@code Africa/Kinshasa} pour UTC+1),
 * {@code Africa/Lubumbashi} par défaut.</p>
 */
public final class FuseauEntreprise {

    public static final ZoneId ZONE = lire();

    private FuseauEntreprise() {
    }

    /** Jour, dans le fuseau de l'entreprise, d'un instant. */
    public static LocalDate jour(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    private static ZoneId lire() {
        String valeur = System.getenv("APP_FUSEAU_HORAIRE");
        if (valeur == null || valeur.isBlank()) valeur = System.getProperty("app.fuseau-horaire");
        try {
            return ZoneId.of(valeur == null || valeur.isBlank() ? "Africa/Lubumbashi" : valeur.trim());
        } catch (RuntimeException e) {
            return ZoneId.of("Africa/Lubumbashi");
        }
    }
}
