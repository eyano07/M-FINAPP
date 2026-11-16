package com.mbsc.finapp.dto.drh;

import java.math.BigDecimal;

/**
 * Sortie complète du moteur de calcul, un champ par colonne du formulaire DEBOURS MBSC, plus le détail introduit
 * par la mise en conformité du 16/11/2026 (législation congolaise ; voir {@code PayrollCalculationService}).
 */
public record ResultatCalculPaie(
    BigDecimal salaireBrut,          // R, prorate par la presence
    BigDecimal indemniteLogement,    // I
    BigDecimal indemniteTransport,   // J
    BigDecimal baseImposableInpp,    // base cotisable (meme base que CNSS et ONEM)
    BigDecimal baseImposableInss,    // base cotisable = H + gains imposables
    BigDecimal baseImposableIpr,     // base cotisable, moins la CNSS salariale si cnssDeductibleIpr
    BigDecimal cnssOuvriere,         // CNSS salariale (pensions), deduite du net
    BigDecimal cnssPatronale,        // total des trois branches patronales
    BigDecimal onem,                 // charge patronale
    BigDecimal totalInss,            // CNSS salariale + patronale
    BigDecimal inpp,                 // charge patronale, taux selon l'effectif
    BigDecimal ipr,                  // deduit du net
    BigDecimal salaireNet,           // AC
    BigDecimal tauxChangeApplique,   // fige sur le bulletin pour reproductibilite
    BigDecimal netFc,                // AD, affichage uniquement
    /** Ecarts au droit congolais, consultatifs : n'influencent aucun montant ci-dessus. */
    ControleConformite conformite,
    /** Appointements H = salaire brut − logement − transport (débit 6611/6621). */
    BigDecimal appointements,
    /** Gains imposables : congé, heures supplémentaires, primes, excédent d'allocation familiale. */
    BigDecimal gainsImposables,
    /** Part de l'allocation familiale exonérée (minimum légal). */
    BigDecimal allocationExoneree,
    /** CNSS patronale, branche pensions (4313). */
    BigDecimal cnssPatronalePension,
    /** CNSS patronale, prestations familiales (4311). */
    BigDecimal cnssPatronalePf,
    /** CNSS patronale, risques professionnels (4312). */
    BigDecimal cnssPatronaleRp,
    /** Taux INPP appliqué selon l'effectif. */
    BigDecimal tauxInppApplique,
    /** Effectif retenu pour la tranche INPP (nul si inconnu). */
    Integer effectif,
    /** Personnes à charge (enfants + conjoint), avant le plafond de la réduction IPR. */
    Integer personnesACharge
) {
    /** Base cotisable (CNSS, ONEM, INPP). */
    public BigDecimal baseCotisable() {
        return baseImposableInss;
    }

    /** Base IPR. */
    public BigDecimal baseIpr() {
        return baseImposableIpr;
    }
}
