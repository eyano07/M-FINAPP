package com.mbsc.finapp.dto.caisse;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Corps de POST /caisse/achats — achat de marchandise reglee en especes,
 * saisi par le caissier.
 *
 * @param articleId       marchandise achetee, enregistree en amont par la logistique.
 * @param entrepotId      entrepot ou la marchandise entre en stock.
 * @param quantite        nombre d'unites achetees.
 * @param prixUnitaire    prix reellement paye a l'unite, <b>en devise de base</b>
 *                        (comme tout montant de caisse, voir TransactionCaisseRequest)
 *                        et non en FC. {@code Article.prixAchat} etant stocke en FC
 *                        — meme convention que {@code prixVente} —, c'est au client
 *                        de le convertir avant de preremplir ce champ, exactement
 *                        comme la saisie d'une vente convertit le prix de vente.
 *                        Le caissier reste libre de corriger la valeur proposee :
 *                        c'est ce montant-ci qui vaut cout d'entree en stock (CMP),
 *                        jamais le prix indicatif du catalogue.
 * @param compteAchatNumero      compte de charge debite par l'achat (601x). Sans lui,
 *                        la sortie de tresorerie ne serait pas constatee en charge.
 * @param compteStockNumero      compte de stock debite par l'entree (311x).
 * @param compteVariationNumero  compte de variation de stock credite (6031).
 * @param libelle         libelle libre, a defaut construit depuis l'article.
 */
public record AchatMarchandiseRequest(
    @NotNull Long articleId,
    @NotNull Long entrepotId,
    @NotNull @Positive BigDecimal quantite,
    @NotNull @Positive BigDecimal prixUnitaire,
    @NotNull String compteAchatNumero,
    @NotNull String compteStockNumero,
    @NotNull String compteVariationNumero,
    String libelle
) {}
