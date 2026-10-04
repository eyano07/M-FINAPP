package com.mbsc.finapp.dto.restaurant;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Analyse des ventes de la carte (plats et boissons) sur une période
 * arbitraire — chiffre d'affaires, coût réel des ventes (lu sur les sorties
 * de stock historiques, jamais recalculé au CMP courant) et marge, globaux
 * puis ventilés par article, par catégorie et par jour.
 *
 * <p>Tous les montants sont exprimés en USD (devise de base du grand livre),
 * à charge du client de les reconvertir pour l'affichage — même convention
 * que {@code TableauBordRestaurantResponse}.</p>
 */
public record AnalyseVentesResponse(
    LocalDate du,
    LocalDate au,
    BigDecimal totalQuantite,
    BigDecimal totalChiffreAffaires,
    BigDecimal totalCout,
    BigDecimal totalMarge,
    List<ArticleStatResponse> parArticle,
    List<CategorieStatResponse> parCategorie,
    List<VenteJourResponse> parJour
) {
    public record ArticleStatResponse(
        Long articleId,
        String code,
        String libelle,
        String type,
        String categorie,
        BigDecimal quantiteVendue,
        BigDecimal chiffreAffaires,
        BigDecimal cout,
        BigDecimal marge,
        BigDecimal margePourcentage
    ) {}

    public record CategorieStatResponse(
        String categorie,
        String type,
        BigDecimal quantiteVendue,
        BigDecimal chiffreAffaires,
        BigDecimal cout,
        BigDecimal marge
    ) {}

    public record VenteJourResponse(LocalDate date, BigDecimal quantite, BigDecimal chiffreAffaires) {}
}
