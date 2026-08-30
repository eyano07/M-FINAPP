package com.mbsc.finapp.dto.comptabilite;

import java.math.BigDecimal;

/** Ligne d'un état financier : un compte et son montant en devise de base. */
public record EtatFinancierLigne(
    String numero,
    String libelle,
    Integer classe,
    BigDecimal montant
) {
}
