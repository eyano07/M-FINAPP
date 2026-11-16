package com.mbsc.finapp.dto.notes;

import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.TypeArticle;

import java.math.BigDecimal;
import java.util.List;

/**
 * Ce qu'il faut a la boite de dialogue de reduction d'une note payee (GET /notes-frais/{id}/retours-fonds/preparation) :
 * la note est-elle reductible, une demande attend-elle deja la caisse, et, ligne par ligne, ce qui peut etre reduit —
 * avec l'unite, la contenance d'un casier pour une boisson conditionnee et le stock restant pour un achat de marchandise.
 */
public record PreparationRetourFondsResponse(
    boolean reductible,
    /** Raison quand la note n'est pas reductible (null sinon). */
    String raison,
    RetourFondsResponse enAttente,
    Devise devise,
    BigDecimal montantNote,
    List<Ligne> lignes
) {
    public record Ligne(
        Long id,
        String description,
        String compteImputation,
        String compteImputationLibelle,
        boolean achatMarchandise,
        boolean fraisApproche,
        /** false pour des frais d'approche : leur cout est deja incorpore aux marchandises. */
        boolean reductible,
        Long articleId,
        String articleCode,
        String articleLibelle,
        TypeArticle articleType,
        String unite,
        String entrepotNom,
        /** Prix unitaire HT (achat de marchandise) ou montant HT de la ligne. */
        BigDecimal montant,
        BigDecimal quantite,
        BigDecimal montantTtc,
        boolean soumisTva,
        BigDecimal tauxTva,
        boolean echangeConsigne,
        /** Bouteilles par casier pour une boisson conditionnee (null sinon). */
        Integer contenanceCasier,
        /** Quantite encore en stock dans l'entrepot de la ligne (achat de marchandise), plafond de la reduction. */
        BigDecimal stockDisponible
    ) {}
}
