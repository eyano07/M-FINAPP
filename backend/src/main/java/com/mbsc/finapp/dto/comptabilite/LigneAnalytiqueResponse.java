package com.mbsc.finapp.dto.comptabilite;

import java.math.BigDecimal;

/**
 * Ligne de l'état de comptabilité analytique (comptes SYSCOHADA 92 à 99).
 * Les montants d'un compte de regroupement incluent ceux de ses sous-comptes.
 */
public record LigneAnalytiqueResponse(
    Long id,
    String numero,
    String libelle,
    int niveau,
    String parentNumero,
    boolean rubrique,
    boolean manuel,
    boolean imputable,
    boolean actif,
    BigDecimal soldeAnterieurDebit,
    BigDecimal soldeAnterieurCredit,
    BigDecimal mouvementDebit,
    BigDecimal mouvementCredit,
    BigDecimal soldeFinalDebiteur,
    BigDecimal soldeFinalCrediteur
) {}
