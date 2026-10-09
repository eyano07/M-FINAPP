package com.mbsc.finapp.dto.notes;

import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.PrioriteNote;
import com.mbsc.finapp.domain.enums.SensTransaction;
import com.mbsc.finapp.domain.enums.StatutNote;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Vue resumee d'une note de frais (listes, tableaux de bord).
 */
public record NoteFraisResponse(
    Long id,
    String reference,
    String objet,
    String beneficiaire,
    BigDecimal montant,
    Devise devise,
    StatutNote statut,
    SensTransaction sens,
    PrioriteNote priorite,
    String createurNom,
    int nombreLignes,
    int nombrePiecesJointes,
    Instant dateCreation,
    Instant dateMaj,
    /** Controle budgetaire releve a la soumission (null avant) : la liste signale les notes hors budget. */
    com.mbsc.finapp.domain.enums.StatutControleBudget statutBudget,
    /** STANDARD, PAIE ou IMPOT_PAIE (notes du module DRH, comptes imposés). */
    com.mbsc.finapp.domain.enums.CategorieNote categorie,
    Integer paieMois,
    Integer paieAnnee,
    com.mbsc.finapp.domain.enums.OrganismePaie organismePaie
) {
    public static NoteFraisResponse from(NoteFrais n) {
        var createur = n.getCreateur();
        return new NoteFraisResponse(
            n.getId(),
            n.getReference(),
            n.getObjet(),
            n.getBeneficiaire(),
            n.getMontant(),
            n.getDevise(),
            n.getStatut(),
            n.getSens(),
            n.getPriorite(),
            createur == null ? null : nomComplet(createur.getPrenom(), createur.getNom()),
            n.getLignes().size(),
            n.getPiecesJointes().size(),
            n.getDateCreation(),
            n.getDateMaj(),
            n.getStatutBudget(),
            n.getCategorie(),
            n.getPaieMois(),
            n.getPaieAnnee(),
            n.getOrganismePaie()
        );
    }

    private static String nomComplet(String prenom, String nom) {
        return ((prenom == null ? "" : prenom) + " " + (nom == null ? "" : nom)).trim();
    }
}
