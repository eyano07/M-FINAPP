package com.mbsc.finapp.domain.enums;

/**
 * Impôts et cotisations sur salaires, chacun réglé par sa propre note de frais (catégorie
 * {@link CategorieNote#IMPOT_PAIE}). Le compte est la dette créditée par la constatation de la paie
 * (voir {@code PaieComptabilisationService}) et soldée par le paiement de la note.
 */
public enum OrganismePaie {
    IPR("4472", "DGI — Direction générale des impôts", "Impôt professionnel sur les rémunérations (IPR)"),
    CNSS("431.1", "CNSS — Caisse nationale de sécurité sociale", "Cotisations CNSS (parts salariale et patronale)"),
    INPP("4478.2", "INPP — Institut national de préparation professionnelle", "Cotisation INPP"),
    ONEM("4478.1", "ONEM — Office national de l'emploi", "Cotisation ONEM");

    private final String compte;
    private final String beneficiaire;
    private final String libelle;

    OrganismePaie(String compte, String beneficiaire, String libelle) {
        this.compte = compte;
        this.beneficiaire = beneficiaire;
        this.libelle = libelle;
    }

    public String compte() { return compte; }
    public String beneficiaire() { return beneficiaire; }
    public String libelle() { return libelle; }
}
