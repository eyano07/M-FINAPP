package com.mbsc.finapp.dto.logistique;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Une ligne de sortie de consommable, avec son mouvement (page Patrimoine › Sorties de consommables). */
public record SortieConsommableResponse(
    Long mouvementId,
    String reference,
    LocalDate dateSortie,
    String libelle,
    String statut,
    Long articleId,
    String articleCode,
    String articleLibelle,
    String uniteMesure,
    String entrepotNom,
    BigDecimal quantite,
    BigDecimal coutUnitaire,
    BigDecimal montant,
    String compteCharge,
    String pieceReference,
    String auteur
) { }
