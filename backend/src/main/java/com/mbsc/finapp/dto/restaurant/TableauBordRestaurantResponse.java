package com.mbsc.finapp.dto.restaurant;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Tableau de bord du module Restaurant sur une periode : stock de bouteilles
 * et casiers, ventes, achats, profit, classement des boissons, series pour
 * graphiques et repartition des pertes.
 *
 * <p>Toutes les valeurs monetaires sont en USD, devise de base du grand
 * livre — la meme que celle deja utilisee pour le CMUP et la valeur de
 * stock ailleurs dans l'application (voir pages/logistique/stock/index.vue).
 * Aucune conversion n'est necessaire cote client : afficher directement.</p>
 *
 * @param margeBrute    chiffre d'affaires des ventes moins leur cout au CMUP courant
 * @param valeurPertes  valeur des boissons perdues (casse pleine, peremption, cadeaux),
 *                      au CMUP courant — la casse d'un simple vide n'a pas de valeur
 *                      marchande, elle n'y figure pas
 * @param profitNet     margeBrute moins valeurPertes : le resultat reel de la periode
 */
public record TableauBordRestaurantResponse(
    LocalDate du,
    LocalDate au,

    // ── Parc d'emballages ────────────────────────────────────────────
    int totalBouteillesVides,
    int totalCasiersVides,
    int totalBouteillesRestantes,
    int nombreConditionnements,

    // ── Stock de boissons pleines ────────────────────────────────────
    BigDecimal valeurStockPleines,
    int nombreBoissonsSousSeuil,

    // ── Ventes ────────────────────────────────────────────────────────
    BigDecimal quantiteVendue,
    BigDecimal chiffreAffaires,
    int nombreVentes,

    // ── Achats (réceptions) ──────────────────────────────────────────
    BigDecimal quantiteAchetee,
    BigDecimal montantAchats,
    int nombreReceptions,

    // ── Profit ────────────────────────────────────────────────────────
    BigDecimal margeBrute,
    BigDecimal valeurPertes,
    BigDecimal profitNet,

    // ── Détails ───────────────────────────────────────────────────────
    List<BoissonStatResponse> topBoissons,
    List<BoissonStatResponse> parBoisson,
    List<VentesJourResponse> ventesParJour,
    List<PerteTypeResponse> pertesParType
) {
    /** Une ligne du classement / detail par boisson. */
    public record BoissonStatResponse(
        Long articleId,
        String code,
        String libelle,
        BigDecimal quantiteVendue,
        BigDecimal chiffreAffaires,
        BigDecimal stockPleines,
        Integer bouteillesVides,
        Integer casiers
    ) {}

    /** Une journee de la periode, pour le graphique de tendance des ventes. */
    public record VentesJourResponse(LocalDate date, BigDecimal quantite, BigDecimal montant) {}

    /** Pertes regroupées par type (casse pleine, périmé, cadeau, casse de vide). */
    public record PerteTypeResponse(String type, String label, int quantite, BigDecimal valeur) {}
}
