package com.mbsc.finapp.dto.budget;

import java.math.BigDecimal;
import java.util.List;

/**
 * Budget annuel propose (non enregistre) : l'ecran pre-remplit l'editeur, le DFIN relit, corrige et enregistre.
 * {@code source} vaut IA (modele de langage) ou CALCUL_LOCAL (meme methode sans IA : realise de reference,
 * annualise, avec sa saisonnalite).
 */
public record PropositionBudgetResponse(
    Integer exercice,
    String source,
    String periodeReference,
    String synthese,
    List<LigneProposee> lignes,
    List<String> avertissements
) {
    public record LigneProposee(
        String compteNumero,
        String compteLibelle,
        String section,
        List<BigDecimal> mensuel,
        BigDecimal montantAnnuel,
        /** Realise de la periode de reference sur ce compte et ses sous-comptes. */
        BigDecimal realiseReference,
        String justification
    ) {}
}
