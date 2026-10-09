package com.mbsc.finapp.dto.budget;

import com.mbsc.finapp.domain.LigneBudget;
import com.mbsc.finapp.service.MoteurBudgetaire;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * Une ligne de budget en lecture : compte, section (produits, charges, investissements), nature SYSCOHADA
 * (compte a deux chiffres), douze montants mensuels et total annuel.
 */
public record LigneBudgetResponse(
    Long id,
    String compteNumero,
    String compteLibelle,
    String section,
    String nature,
    List<BigDecimal> mensuel,
    BigDecimal montantPrevu,
    String commentaire
) {
    public static LigneBudgetResponse from(LigneBudget l) {
        var compte = l.getCompte();
        String numero = compte == null ? null : compte.getNumero();
        BigDecimal[] mensuel = MoteurBudgetaire.mensuelDepuis(l.getMois());
        return new LigneBudgetResponse(
            l.getId(),
            numero,
            compte == null ? null : compte.getLibelle(),
            compte == null ? null : MoteurBudgetaire.section(numero, compte.getType()).name(),
            MoteurBudgetaire.nature(numero),
            Arrays.asList(mensuel),
            MoteurBudgetaire.total(mensuel),
            l.getCommentaire()
        );
    }
}
