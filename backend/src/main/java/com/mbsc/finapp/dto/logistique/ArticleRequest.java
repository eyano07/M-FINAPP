package com.mbsc.finapp.dto.logistique;

import com.mbsc.finapp.domain.enums.TypeArticle;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * @param type              MARCHANDISE (stockee) ou SERVICE ; MARCHANDISE par defaut.
 * @param entrepotId        entrepot d'affectation facultatif (ignore pour un SERVICE,
 *                          qui n'a pas de stock) ; les quantites se gerent entrepot
 *                          par entrepot depuis l'etat du stock, pas depuis ce champ.
 * @param prixVente         prix de vente unitaire hors taxes, en devise de base (FC).
 * @param prixAchat         prix d'achat unitaire indicatif (FC), facultatif : prerempli
 *                          lors d'un achat saisi a la caisse, jamais impose.
 * @param compteProduitNumero compte de produit credite a la vente (7011, 7061...).
 * @param compteAchatNumero compte d'achat (601x), debite au reglement d'une note
 *                          de frais d'achat de cette marchandise (voir NoteFraisService).
 * @param minerais          true pour un minerais, suivi camion par camion (chaque
 *                          chargement a son propre prix de vente) ; reserve aux
 *                          marchandises stockees.
 * @param soumisTva         false pour un article exonere de TVA.
 * @param categorie         sous-classification libre (ex. Alcool, Vin, Whisky) ;
 *                          pertinente uniquement pour une BOISSON de la carte du
 *                          restaurant, ignoree pour tout autre type.
 * @param societe           societe du produit (brasserie, fabricant ou fournisseur) ;
 *                          facultative, retenue uniquement pour une BOISSON.
 */
public record ArticleRequest(
    @NotBlank String code,
    @NotBlank String libelle,
    String uniteMesure,
    TypeArticle type,
    String categorie,
    @Size(max = 100) String societe,
    Long entrepotId,
    String compteStockNumero,
    String compteChargeNumero,
    String compteProduitNumero,
    String compteAchatNumero,
    @PositiveOrZero BigDecimal prixVente,
    @PositiveOrZero BigDecimal prixAchat,
    Boolean minerais,
    Boolean soumisTva,
    @PositiveOrZero BigDecimal stockMin,
    Boolean actif
) {}
