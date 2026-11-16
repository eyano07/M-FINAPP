package com.mbsc.finapp.domain.enums;

/**
 * Nature de la sortie d'une immobilisation (audit SYSCOHADA du 10/11/2026, C-18). Le SYSCOHADA révisé distingue le
 * renouvellement courant des équipements, dans les activités ordinaires (654 / 754), de la cession exceptionnelle,
 * hors activités ordinaires (812 / 822).
 */
public enum TypeCession {
    /** Cession courante : valeur comptable en 6542, produit en 7542. */
    COURANTE,
    /** Cession hors activités ordinaires : valeur comptable en 812, produit en 822. */
    HAO
}
