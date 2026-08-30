package com.mbsc.finapp.dto.notes;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Une ligne de depense d'une note de frais : montant, compte d'imputation
 * (optionnel) et description propres a cette ligne.
 *
 * @param achatMarchandise   true si cette depense est un achat de marchandises ;
 *                           impose alors (articleId ou articleNom) et quantiteMarchandise ;
 *                           le compte de stock de l'article prime sur compteImputation.
 *                           L'entrepot n'est plus choisi par l'utilisateur : l'unique
 *                           entrepot actif est retenu automatiquement.
 * @param quantiteMarchandise nombre d'unites achetees, portees au stock au paiement
 * @param articleId          article de marchandise existant (prioritaire sur articleNom)
 * @param articleNom         nom d'un article a retrouver (par libelle) ou a creer a la
 *                           volee s'il n'existe pas encore ; ignore si articleId est fourni
 * @param entrepotId         entrepot de destination explicite (facultatif — voir achatMarchandise)
 * @param soumisTva          true si l'achat est soumis a la TVA : le montant (TTC) sera
 *                           ventile a la comptabilisation entre le compte d'imputation (HT)
 *                           et le compte de TVA recuperable sur achats (4452), retenu
 *                           automatiquement — l'utilisateur ne choisit plus ce compte
 * @param echangeConsigne    true si cet achat de boisson (module Restaurant) rend les
 *                           bouteilles vides equivalentes au paiement ; sans effet pour
 *                           tout autre type d'article
 */
public record LigneNoteFraisRequest(

    @NotNull
    @DecimalMin(value = "0.01", message = "Le montant de la ligne doit etre strictement positif")
    BigDecimal montant,

    /** Numero du compte OHADA d'imputation (optionnel : fallback 6588 au paiement ; ignore si achatMarchandise). */
    String compteImputation,

    @Size(max = 500)
    String description,

    Boolean achatMarchandise,
    BigDecimal quantiteMarchandise,
    Long articleId,
    @Size(max = 200)
    String articleNom,
    Long entrepotId,
    Boolean soumisTva,
    String compteTva,
    Boolean echangeConsigne
) {}
