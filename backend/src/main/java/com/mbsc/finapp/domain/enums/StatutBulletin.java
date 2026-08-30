package com.mbsc.finapp.domain.enums;

/**
 * Cycle de vie d'un bulletin de paie.
 *
 * <p>Volontairement pas de valeur COMPTABILISE : cet état se lit en
 * interrogeant {@code bulletin.getPieceComptable().getStatut()} (la pièce
 * liée, gérée par {@code ComptabiliteService}), pour éviter deux sources de
 * vérité qui pourraient diverger.</p>
 */
public enum StatutBulletin {
    BROUILLON,
    VALIDE,
    ANNULE
}
