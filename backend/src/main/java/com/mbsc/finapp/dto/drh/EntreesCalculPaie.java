package com.mbsc.finapp.dto.drh;

import java.math.BigDecimal;

/**
 * Entrées saisies d'un bulletin de paie, avant calcul. Isolé de l'entité
 * {@code BulletinPaie} pour que {@code PayrollCalculationService} reste une
 * fonction pure, testable sans contexte JPA.
 *
 * @param marie            l'agent est marié (situation familiale MARIE) : son conjoint compte comme personne à
 *                         charge si {@code ParametresPaie.conjointACharge}
 * @param personnesACharge personnes à charge déjà figées sur le bulletin (recalcul) ; nul = enfants + conjoint
 * @param effectif         nombre d'employés actifs au moment du calcul (tranche INPP) ; nul = première tranche
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
    Integer nombreEnfants,
    boolean marie,
    Integer personnesACharge,
    Integer effectif
) {
    /** Agent non marié, personnes à charge = enfants, effectif inconnu (première tranche INPP). */
    public EntreesCalculPaie(BigDecimal salaireBaseUsd, BigDecimal presencePct, BigDecimal conge,
                             BigDecimal heuresSupplementaires, BigDecimal allocationFamiliale, BigDecimal primeDiplome,
                             BigDecimal primeAnciennete, BigDecimal primeRendement, BigDecimal avanceSalaire,
                             BigDecimal pret, Integer nombreEnfants) {
        this(salaireBaseUsd, presencePct, conge, heuresSupplementaires, allocationFamiliale, primeDiplome,
            primeAnciennete, primeRendement, avanceSalaire, pret, nombreEnfants, false, null, null);
    }
}
