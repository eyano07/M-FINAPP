package com.mbsc.finapp.dto.comptabilite;

import com.mbsc.finapp.domain.enums.Devise;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Journal d'un compte du plan comptable sur une periode : une ligne par mouvement, avec la note de frais, l'objet, le
 * beneficiaire, le compte de contrepartie, l'entree (debit) ou la sortie (credit) et le solde cumule depuis le solde
 * d'ouverture. Montants dans {@code devise} (USD, devise de base, ou CDF converti ligne par ligne au {@code taux}).
 */
public record JournalCompteResponse(
    String compteNumero,
    String compteLibelle,
    LocalDate du,
    LocalDate au,
    Devise devise,
    BigDecimal taux,
    BigDecimal soldeOuverture,
    BigDecimal totalEntrees,
    BigDecimal totalSorties,
    BigDecimal soldeCloture,
    List<Ligne> lignes
) {

    /**
     * Un mouvement du compte. {@code note} : reference de la note de frais (NF-...) quand il y en a une ;
     * {@code comptes} : compte(s) de contrepartie, le principal d'abord ; {@code annulee} : piece annulee par extourne
     * (elle reste au journal, son extourne la solde).
     */
    public record Ligne(
        LocalDate date,
        String note,
        String objet,
        String beneficiaire,
        String comptes,
        String piece,
        boolean annulee,
        BigDecimal entree,
        BigDecimal sortie,
        BigDecimal solde
    ) {
    }
}
