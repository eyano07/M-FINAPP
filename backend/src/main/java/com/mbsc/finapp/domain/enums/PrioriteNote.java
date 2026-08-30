package com.mbsc.finapp.domain.enums;

/**
 * Niveau de priorite de paiement d'une note de frais.
 *
 * <p>La priorite est definie par le Directeur Administratif (DA) une fois
 * la note validee (etat {@link StatutNote#VALIDEE_DA}). Elle oriente l'ordre
 * de traitement par la caisse.</p>
 */
public enum PrioriteNote {
    BASSE,
    MOYENNE,
    HAUTE
}
