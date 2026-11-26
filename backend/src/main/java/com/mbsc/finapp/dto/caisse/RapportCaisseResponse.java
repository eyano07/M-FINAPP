package com.mbsc.finapp.dto.caisse;

import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.PeriodeRapport;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Rapport de caisse d'un jour, d'une semaine ou d'un mois : solde d'ouverture, chaque entree et chaque sortie de la
 * periode, resume par jour et par nature, solde de cloture. Tous les montants sont dans {@code devise} (USD, devise de
 * base, ou CDF) et converts au meme taux ; ils s'additionnent exactement : {@code soldeCloture = soldeOuverture +
 * totalEntrees - totalSorties}.
 *
 * @param date     date de reference demandee (la periode est celle qui la contient)
 * @param taux     francs pour 1 dollar quand les montants sont convertis, {@code null} sinon
 * @param dateTaux jour pour lequel le taux a ete resolu ({@code null} sans conversion)
 * @param jours    jours ayant au moins un mouvement, dans l'ordre, avec le solde de caisse en fin de journee
 * @param natures  mouvements regroupes par compte de contrepartie principal
 */
public record RapportCaisseResponse(
    PeriodeRapport periode,
    LocalDate date,
    LocalDate du,
    LocalDate au,
    Devise devise,
    BigDecimal taux,
    LocalDate dateTaux,
    BigDecimal soldeOuverture,
    BigDecimal totalEntrees,
    BigDecimal totalSorties,
    BigDecimal soldeCloture,
    List<Jour> jours,
    List<Mouvement> entrees,
    List<Mouvement> sorties,
    List<Nature> natures
) {

    /**
     * Un mouvement de la caisse. {@code compteNumero}/{@code compteLibelle} : contrepartie principale (la plus grosse
     * des autres lignes de la piece) ; {@code autresComptes} : les autres, separes par des virgules (ex. la TVA).
     * {@code heure}, {@code numeroRecu} et {@code caissierNom} ne sont connus que des operations passees par la caisse.
     */
    public record Mouvement(
        LocalDate date,
        Instant heure,
        String pieceReference,
        String numeroRecu,
        String libelle,
        String compteNumero,
        String compteLibelle,
        String autresComptes,
        String caissierNom,
        boolean annulee,
        BigDecimal montant
    ) {
    }

    /** Resume d'une journee : ce qui est entre, ce qui est sorti, la variation et le solde de caisse a la fin du jour. */
    public record Jour(
        LocalDate date,
        BigDecimal entrees,
        BigDecimal sorties,
        BigDecimal net,
        BigDecimal soldeCloture,
        int nombreEntrees,
        int nombreSorties
    ) {
    }

    /** Resume par compte de contrepartie ({@code compteNumero} vide : mouvement sans contrepartie identifiable). */
    public record Nature(
        String compteNumero,
        String compteLibelle,
        BigDecimal entrees,
        BigDecimal sorties,
        int nombre
    ) {
    }
}
