package com.mbsc.finapp.domain.enums;

import java.util.List;

/**
 * Impôts et cotisations sur salaires, chacun réglé par sa propre note de frais (catégorie
 * {@link CategorieNote#IMPOT_PAIE}). Les comptes sont les dettes créditées par la constatation de la paie (voir
 * {@code PaieComptabilisationService}) et soldées par le paiement de la note : la note d'un organisme débite
 * exactement les comptes que les pièces de constatation du mois ont crédités pour lui (voir
 * {@code PaieNoteService.creerNoteImpot}).
 *
 * <p>Audit DRH du 15/11/2026 : CNSS par branche 4311 / 4312 / 4313 (D-08, 4315 pour les bulletins antérieurs) ;
 * ONEM et INPP sous 4428 « Autres impôts et taxes », en 44281 / 44282 (D-07, 44781 / 44782 pour l'historique).</p>
 */
public enum OrganismePaie {
    IPR(List.of("4472"), List.of(),
        "DGI — Direction générale des impôts", "Impôt professionnel sur les rémunérations (IPR)"),
    CNSS(List.of("4311", "4312", "4313"), List.of("4315"),
        "CNSS — Caisse nationale de sécurité sociale", "Cotisations CNSS (parts salariale et patronale)"),
    INPP(List.of("44282"), List.of("44782"),
        "INPP — Institut national de préparation professionnelle", "Cotisation INPP"),
    ONEM(List.of("44281"), List.of("44781"),
        "ONEM — Office national de l'emploi", "Cotisation ONEM");

    private final List<String> comptes;
    private final List<String> comptesHistoriques;
    private final String beneficiaire;
    private final String libelle;

    OrganismePaie(List<String> comptes, List<String> comptesHistoriques, String beneficiaire, String libelle) {
        this.comptes = comptes;
        this.comptesHistoriques = comptesHistoriques;
        this.beneficiaire = beneficiaire;
        this.libelle = libelle;
    }

    /** Comptes crédités par la constatation actuelle (CNSS : prestations familiales, risques professionnels, retraite). */
    public List<String> comptes() { return comptes; }

    /** Comptes crédités par les constatations antérieures au 16/11/2026, encore réglés par les notes. */
    public List<String> comptesHistoriques() { return comptesHistoriques; }

    /** Vrai si la dette de cet organisme peut être portée par ce compte (actuel ou historique). */
    public boolean porte(String numero) {
        return comptes.contains(numero) || comptesHistoriques.contains(numero);
    }

    /** Comptes actuels pour l'affichage (« 4311/4312/4313 »). */
    public String compte() { return String.join("/", comptes); }

    public String beneficiaire() { return beneficiaire; }
    public String libelle() { return libelle; }
}
