package com.mbsc.finapp.dto.comptabilite;

import com.mbsc.finapp.domain.enums.Devise;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Balance agee des creances clients : les creances encore ouvertes ventilees
 * par anciennete a une date d'arrete.
 *
 * <p>Le grand livre du compte 4111 donne un solde global mais ne dit pas
 * <i>depuis quand</i> il est du — or c'est l'anciennete, et elle seule, qui
 * declenche une relance, un provisionnement pour depreciation ou un passage en
 * perte. Les tranches suivent le decoupage usuel du recouvrement.</p>
 */
public record BalanceAgeeResponse(
    LocalDate arreteAu,
    int nombreCreances,
    BigDecimal totalDu,
    List<TrancheAge> tranches,
    List<CreanceAgee> creances
) {
    /** @param libelle intitule de la tranche, p. ex. « 31 à 60 jours » */
    public record TrancheAge(
        String cle,
        String libelle,
        int nombre,
        BigDecimal montant,
        /** Part du total du, en pourcentage — sert a lire la concentration du risque. */
        BigDecimal pourcentage
    ) {}

    public record CreanceAgee(
        Long venteId,
        String reference,
        String client,
        LocalDate dateVente,
        int joursEcoules,
        String tranche,
        Devise devise,
        BigDecimal montantDu,
        /** Contre-valeur en devise de base, pour additionner des creances de devises differentes. */
        BigDecimal montantDuBase
    ) {}
}
