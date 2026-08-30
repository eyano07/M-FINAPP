package com.mbsc.finapp.dto.restaurant;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Tableau de bord des provisions de cuisine (vivres, épices, charbon...) sur
 * une période.
 *
 * <p>Contrairement au tableau de bord de la carte, il n'y a ni ventes ni
 * profit : les provisions sont un centre de coût (achetées puis consommées en
 * interne), jamais un centre de revenu. L'indicateur qui compte est la
 * consommation et sa valeur, pas une marge.</p>
 */
public record TableauBordProvisionsResponse(
    LocalDate du,
    LocalDate au,

    // ── Stock actuel ──────────────────────────────────────────────────
    int nombreProvisions,
    BigDecimal valeurStockActuel,
    int nombreSousSeuil,

    // ── Achats (réceptions) ──────────────────────────────────────────
    BigDecimal quantiteAchetee,
    BigDecimal montantAchats,
    int nombreReceptions,

    // ── Consommation (sorties : cuisine, casse, péremption) ──────────
    BigDecimal quantiteConsommee,
    BigDecimal montantConsomme,
    int nombreSorties,

    // ── Détails ───────────────────────────────────────────────────────
    List<ProvisionStatResponse> topConsommees,
    List<ProvisionStatResponse> parProvision,
    List<MouvementJourResponse> mouvementsParJour
) {
    /** Une ligne du classement / detail par provision. */
    public record ProvisionStatResponse(
        Long articleId,
        String code,
        String libelle,
        String uniteMesure,
        BigDecimal quantiteAchetee,
        BigDecimal quantiteConsommee,
        BigDecimal stockActuel,
        BigDecimal valeurStock,
        boolean sousSeuil
    ) {}

    /** Une journee de la periode, pour le graphique entrées/sorties. */
    public record MouvementJourResponse(LocalDate date, BigDecimal quantiteEntree, BigDecimal quantiteSortie) {}
}
