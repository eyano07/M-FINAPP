package com.mbsc.finapp.dto.logistique;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.enums.TypeArticle;

import java.math.BigDecimal;

/**
 * @param vendable true si l'article peut figurer sur une vente
 *                 (actif, prix de vente et compte de produit renseignes).
 */
public record ArticleResponse(
    Long id,
    String code,
    String libelle,
    String uniteMesure,
    TypeArticle type,
    Long entrepotId,
    String entrepotNom,
    String compteStockNumero,
    String compteChargeNumero,
    String compteProduitNumero,
    String compteAchatNumero,
    BigDecimal prixVente,
    BigDecimal prixAchat,
    boolean minerais,
    boolean soumisTva,
    BigDecimal stockMin,
    boolean actif,
    boolean vendable
) {
    public static ArticleResponse from(Article a) {
        var entrepot = a.getEntrepot();
        return new ArticleResponse(
            a.getId(),
            a.getCode(),
            a.getLibelle(),
            a.getUniteMesure(),
            a.getType(),
            entrepot == null ? null : entrepot.getId(),
            entrepot == null ? null : entrepot.getNom(),
            a.getCompteStock() == null ? null : a.getCompteStock().getNumero(),
            a.getCompteCharge() == null ? null : a.getCompteCharge().getNumero(),
            a.getCompteProduit() == null ? null : a.getCompteProduit().getNumero(),
            a.getCompteAchat() == null ? null : a.getCompteAchat().getNumero(),
            a.getPrixVente(),
            a.getPrixAchat(),
            a.isMinerais(),
            a.isSoumisTva(),
            a.getStockMin(),
            a.isActif(),
            a.estVendable()
        );
    }
}
