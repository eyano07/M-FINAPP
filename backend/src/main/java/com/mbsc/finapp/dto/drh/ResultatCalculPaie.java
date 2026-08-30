package com.mbsc.finapp.dto.drh;

import java.math.BigDecimal;

/** Sortie complète du moteur de calcul, un champ par colonne du formulaire DEBOURS MBSC. */
public record ResultatCalculPaie(
    BigDecimal salaireBrut,          // R, prorate par la presence
    BigDecimal indemniteLogement,    // I
    BigDecimal indemniteTransport,   // J
    BigDecimal baseImposableInpp,    // Q
    BigDecimal baseImposableInss,    // S = H
    BigDecimal baseImposableIpr,     // Y = H, ou H - CNSS ouvriere si cnssDeductibleIpr
    BigDecimal cnssOuvriere,         // T, deduite du net
    BigDecimal cnssPatronale,        // U, charge patronale
    BigDecimal onem,                 // V, charge patronale
    BigDecimal totalInss,            // W = T + U
    BigDecimal inpp,                 // X, charge patronale
    BigDecimal ipr,                  // Z, deduite du net
    BigDecimal salaireNet,           // AC
    BigDecimal tauxChangeApplique,   // fige sur le bulletin pour reproductibilite
    BigDecimal netFc,                // AD, affichage uniquement
    /** Ecarts au droit congolais, consultatifs : n'influencent aucun montant ci-dessus. */
    ControleConformite conformite
) {}
