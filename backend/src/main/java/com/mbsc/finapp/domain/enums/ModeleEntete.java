package com.mbsc.finapp.domain.enums;

/**
 * Modèle de papier à en-tête choisi par l'administrateur (écran Paramètres). Il habille tous les documents
 * produits par l'application : PDF générés par le serveur (papier à en-tête, ordre de mission, budget) et
 * impressions depuis le navigateur. La couleur vient de {@code ParametresEntreprise.couleurPrimaire}.
 */
public enum ModeleEntete {
    /** Logo et raison sociale à gauche, bandeau incliné en dégradé à droite (RCCM, ID. Nat, NIF). */
    CLASSIQUE,
    /** Bandeau plein de la couleur du thème sur toute la largeur, textes en blanc. */
    BANDEAU,
    /** Minimaliste : nom en couleur, identifiants en gris, un simple filet. */
    EPURE,
    /** Institutionnel : logo et nom centrés, double filet. */
    CENTRE,
    /** Barre verticale de couleur le long du bord gauche de chaque page. */
    LATERAL,
    /** En-tête et pied de page dans des cartouches encadrés, fond teinté. */
    ENCADRE
}
