package com.mbsc.finapp.dto.notes;

import com.mbsc.finapp.domain.LigneNoteFrais;

import java.math.BigDecimal;

/**
 * @param montant        prix unitaire HT si {@code achatMarchandise}, sinon montant HT total de la ligne.
 * @param montantHtTotal montant HT total (= montant x quantite pour un achat de marchandise).
 * @param montantTtc     montant HT total + TVA le cas echeant, ce qui est reellement decaisse pour cette ligne.
 */
public record LigneNoteFraisResponse(
    Long id,
    BigDecimal montant,
    BigDecimal montantHtTotal,
    BigDecimal montantTtc,
    BigDecimal tauxTvaApplique,
    String compteImputation,
    String compteImputationLibelle,
    String description,
    boolean achatMarchandise,
    BigDecimal quantiteMarchandise,
    Long articleId,
    String articleCode,
    String articleLibelle,
    Long entrepotId,
    String entrepotNom,
    boolean soumisTva,
    String compteTva,
    String compteTvaLibelle,
    boolean echangeConsigne
) {
    public static LigneNoteFraisResponse from(LigneNoteFrais l) {
        var compte = l.getCompteImputation();
        var compteTva = l.getCompteTva();
        var article = l.getArticle();
        var entrepot = l.getEntrepot();
        return new LigneNoteFraisResponse(
            l.getId(),
            l.getMontant(),
            l.montantHtTotal(),
            l.montantTtc(),
            l.getTauxTvaApplique(),
            compte == null ? null : compte.getNumero(),
            compte == null ? null : compte.getLibelle(),
            l.getDescription(),
            l.isAchatMarchandise(),
            l.getQuantiteMarchandise(),
            article == null ? null : article.getId(),
            article == null ? null : article.getCode(),
            article == null ? null : article.getLibelle(),
            entrepot == null ? null : entrepot.getId(),
            entrepot == null ? null : entrepot.getNom(),
            l.isSoumisTva(),
            compteTva == null ? null : compteTva.getNumero(),
            compteTva == null ? null : compteTva.getLibelle(),
            l.isEchangeConsigne()
        );
    }
}
