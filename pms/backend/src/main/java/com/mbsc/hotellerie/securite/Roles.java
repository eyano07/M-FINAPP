package com.mbsc.hotellerie.securite;

/**
 * Rôles M-FINAPP utiles à l'hôtellerie. L'administrateur de M-FINAPP règle l'hôtellerie mais n'encaisse pas
 * (séparation des tâches) ; la direction (DG, DFIN, DA) et la comptabilité consultent.
 */
public final class Roles {

    public static final String RECEPTION = "RECEPTIONNISTE";
    public static final String MENAGE = "GOUVERNANTE";
    public static final String TECHNICIEN = "TECHNICIEN";
    public static final String DIRECTION = "DIR_HEBERGEMENT";
    public static final String ADMIN = "ADMIN";

    /** Réservations, arrivées et départs (sans encaisser pour l'administrateur). */
    public static final String[] EXPLOITATION = {RECEPTION, DIRECTION, ADMIN};
    /** Encaissement des folios, acomptes et cautions. */
    public static final String[] ENCAISSEMENT = {RECEPTION, DIRECTION};
    /** Établissements, unités et tarifs. */
    public static final String[] PARAMETRAGE = {DIRECTION, ADMIN};
    /** Ménage : état des unités et tâches. */
    public static final String[] MENAGE_ECRITURE = {MENAGE, RECEPTION, DIRECTION, ADMIN};
    /** Maintenance : tout le personnel signale une panne ; le technicien et la direction la traitent. */
    public static final String[] SIGNALEMENT = {RECEPTION, MENAGE, TECHNICIEN, DIRECTION, ADMIN};
    public static final String[] MAINTENANCE = {TECHNICIEN, DIRECTION, ADMIN};
    /** Achats de pièces (note de frais M-FINAPP) : technicien et direction (jamais l'administrateur : il ne décaisse pas). */
    public static final String[] ACHATS = {TECHNICIEN, DIRECTION};
    /** Baux, échéances et compteurs des appartements. */
    public static final String[] BAUX = {RECEPTION, DIRECTION, ADMIN};

    private Roles() {
    }
}
