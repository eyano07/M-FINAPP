package com.mbsc.finapp.dto.drh;

import java.math.BigDecimal;

/**
 * Entrées saisies d'un bulletin de paie, avant calcul. Isolé de l'entité
 * {@code BulletinPaie} pour que {@code PayrollCalculationService} reste une
 * fonction pure, testable sans contexte JPA.
 */
public record EntreesCalculPaie(
    BigDecimal salaireBaseUsd,
    BigDecimal presencePct,
    BigDecimal conge,
    BigDecimal heuresSupplementaires,
    BigDecimal allocationFamiliale,
    BigDecimal primeDiplome,
    BigDecimal primeAnciennete,
    BigDecimal primeRendement,
    BigDecimal avanceSalaire,
    BigDecimal pret,
    Integer nombreEnfants
) {}
