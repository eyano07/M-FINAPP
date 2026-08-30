package com.mbsc.finapp.dto.caisse;

import com.mbsc.finapp.domain.enums.TypeCompte;

import java.math.BigDecimal;

/**
 * Une ligne de balance comptable : cumul des debits/credits d'un compte
 * et son solde. Calculee a la volee depuis le Grand Livre.
 *
 * <p>Convention de solde :</p>
 * <ul>
 *   <li>CHARGE / ACTIF : solde = debit - credit</li>
 *   <li>PRODUIT / PASSIF : solde = credit - debit</li>
 * </ul>
 */
public record LigneBalanceResponse(
    String compteNumero,
    String compteLibelle,
    TypeCompte type,
    BigDecimal totalDebit,
    BigDecimal totalCredit,
    BigDecimal solde
) {
    public static LigneBalanceResponse of(String numero, String libelle, TypeCompte type,
                                          BigDecimal debit, BigDecimal credit) {
        BigDecimal d = debit == null ? BigDecimal.ZERO : debit;
        BigDecimal c = credit == null ? BigDecimal.ZERO : credit;
        BigDecimal solde = switch (type == null ? TypeCompte.ACTIF : type) {
            case PRODUIT, PASSIF -> c.subtract(d);
            case CHARGE, ACTIF -> d.subtract(c);
        };
        return new LigneBalanceResponse(numero, libelle, type, d, c, solde);
    }
}
