package com.mbsc.finapp.dto.etablissement;

import com.mbsc.finapp.domain.EtablissementTresorerie;
import com.mbsc.finapp.domain.enums.TypeEtablissement;

import java.math.BigDecimal;

/**
 * Vue d'un etablissement de tresorerie.
 *
 * @param solde        solde du compte dedie, en devise de base (CDF)
 * @param supprimable  true si le solde est nul : seul un etablissement
 *                     sans encours peut etre retire.
 */
public record EtablissementResponse(
    Long id,
    String nom,
    TypeEtablissement type,
    String compteNumero,
    String compteLibelle,
    boolean actif,
    BigDecimal solde,
    boolean supprimable
) {
    public static EtablissementResponse from(EtablissementTresorerie e, BigDecimal solde) {
        BigDecimal montant = solde == null ? BigDecimal.ZERO : solde;
        return new EtablissementResponse(
            e.getId(),
            e.getNom(),
            e.getType(),
            e.getCompte().getNumero(),
            e.getCompte().getLibelle(),
            e.isActif(),
            montant,
            montant.signum() == 0
        );
    }
}
