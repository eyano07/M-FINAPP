package com.mbsc.finapp.dto.logistique;

import com.mbsc.finapp.domain.enums.TypeArticle;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * @param type              MARCHANDISE (stockee) ou SERVICE ; MARCHANDISE par defaut.
 * @param entrepotId        entrepot d'affectation facultatif (ignore pour un SERVICE,
 *                          qui n'a pas de stock) ; les quantites se gerent entrepot
 *                          par entrepot depuis l'etat du stock, pas depuis ce champ.
 * @param prixVente         prix de vente unitaire hors taxes, en devise de base (FC).
 * @param compteProduitNumero compte de produit credite a la vente (7011, 7061...).
 * @param soumisTva         false pour un article exonere de TVA.
 */
public record ArticleRequest(
    @NotBlank String code,
    @NotBlank String libelle,
    String uniteMesure,
    TypeArticle type,
    Long entrepotId,
    String compteStockNumero,
    String compteChargeNumero,
    String compteProduitNumero,
    @PositiveOrZero BigDecimal prixVente,
    Boolean soumisTva,
    @PositiveOrZero BigDecimal stockMin,
    Boolean actif
) {}
