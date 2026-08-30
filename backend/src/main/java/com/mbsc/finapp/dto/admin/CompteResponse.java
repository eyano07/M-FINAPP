package com.mbsc.finapp.dto.admin;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.enums.TypeCompte;

/**
 * Vue d'un compte du plan comptable OHADA.
 *
 * <p>Volontairement sans les rubriques du referentiel commente : le plan
 * compte plus de 1300 comptes et ces textes pesent plusieurs centaines de
 * kilo-octets. La liste ne porte que l'indicateur {@code commente} ; le
 * detail est servi par {@code GET /admin/comptes/{id}}.</p>
 */
public record CompteResponse(
    Long id,
    String numero,
    String libelle,
    TypeCompte type,
    Integer classe,
    boolean manuel,
    boolean imputable,
    boolean actif,
    String parentNumero,
    boolean commente
) {
    public static CompteResponse from(CompteOHADA c) {
        return new CompteResponse(
            c.getId(),
            c.getNumero(),
            c.getLibelle(),
            c.getType(),
            c.getClasse(),
            c.isManuel(),
            c.isImputable(),
            c.isActif(),
            c.getParent() != null ? c.getParent().getNumero() : null,
            c.estCommente()
        );
    }
}
