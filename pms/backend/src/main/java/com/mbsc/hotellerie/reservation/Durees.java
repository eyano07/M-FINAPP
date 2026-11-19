package com.mbsc.hotellerie.reservation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Nombre d'unités facturées d'un séjour : nuitées (dates d'arrivée et de départ), heures entamées, semaines ou mois
 * entamés (30 jours). Toujours au moins une unité.
 */
public final class Durees {

    /**
     * Fuseau de l'entreprise, le même que M-FINAPP : PMS_FUSEAU_HORAIRE (repris de APP_FUSEAU_HORAIRE par docker-compose),
     * Africa/Lubumbashi par défaut. Les heures d'arrivée et de départ des établissements s'y lisent.
     */
    public static final ZoneId FUSEAU = fuseau(System.getenv("PMS_FUSEAU_HORAIRE"));

    static ZoneId fuseau(String valeur) {
        try {
            return ZoneId.of(valeur == null || valeur.isBlank() ? "Africa/Lubumbashi" : valeur.trim());
        } catch (RuntimeException e) {
            return ZoneId.of("Africa/Lubumbashi");
        }
    }
    private static final BigDecimal CENT = BigDecimal.valueOf(100);

    private Durees() {
    }

    public static long quantite(String uniteDuree, OffsetDateTime debut, OffsetDateTime fin) {
        long nuits = ChronoUnit.DAYS.between(debut.atZoneSameInstant(FUSEAU).toLocalDate(), fin.atZoneSameInstant(FUSEAU).toLocalDate());
        long q = switch (uniteDuree) {
            case "HEURE" -> (Duration.between(debut, fin).toMinutes() + 59) / 60;
            case "NUIT" -> nuits;
            case "SEMAINE" -> (nuits + 6) / 7;
            case "MOIS" -> (nuits + 29) / 30;
            default -> throw new IllegalArgumentException("Durée inconnue : " + uniteDuree);
        };
        return Math.max(1, q);
    }

    public static String libelle(String uniteDuree, long quantite) {
        String u = switch (uniteDuree) {
            case "HEURE" -> "heure";
            case "NUIT" -> "nuit";
            case "SEMAINE" -> "semaine";
            default -> "mois";
        };
        return quantite + " " + (quantite > 1 && !"mois".equals(u) ? u + "s" : u);
    }

    /** Montant HT d'une ligne, arrondi au centime (même calcul que M-FINAPP). */
    public static BigDecimal ht(BigDecimal prix, BigDecimal quantite) {
        return prix.multiply(quantite).setScale(2, RoundingMode.HALF_UP);
    }

    /** TVA d'une ligne, arrondie au centime (même calcul que M-FINAPP). */
    public static BigDecimal tva(BigDecimal ht, BigDecimal taux, boolean soumis) {
        return soumis && taux != null && taux.signum() > 0 ? ht.multiply(taux).divide(CENT, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }
}
