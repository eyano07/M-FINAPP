package com.mbsc.finapp.dto.comptabilite;

import com.mbsc.finapp.domain.enums.TypeCompte;

import java.math.BigDecimal;
import java.util.List;

public record LigneBalanceVerificationResponse(
    String compteNumero,
    String compteLibelle,
    TypeCompte type,
    BigDecimal totalDebit,
    BigDecimal totalCredit,
    BigDecimal soldeDebiteur,
    BigDecimal soldeCrediteur
) {
    public static LigneBalanceVerificationResponse of(String numero, String libelle, TypeCompte type,
                                                      BigDecimal debit, BigDecimal credit) {
        BigDecimal d = debit == null ? BigDecimal.ZERO : debit;
        BigDecimal c = credit == null ? BigDecimal.ZERO : credit;
        BigDecimal net = d.subtract(c);
        BigDecimal soldeDebiteur = net.signum() > 0 ? net : BigDecimal.ZERO;
        BigDecimal soldeCrediteur = net.signum() < 0 ? net.abs() : BigDecimal.ZERO;
        return new LigneBalanceVerificationResponse(numero, libelle, type, d, c, soldeDebiteur, soldeCrediteur);
    }
}
