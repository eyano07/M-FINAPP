package com.mbsc.finapp.dto.restaurant;

import com.mbsc.finapp.domain.Production;
import com.mbsc.finapp.domain.enums.StatutProduction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * @param coutUnitaire       cout de revient d'une portion, deduit du cout reel
 *                           des ingredients sortis — c'est lui qui alimente le
 *                           CMP du plat.
 * @param mouvementSortieRef mouvement de sortie des ingredients (journal STOCK).
 * @param mouvementEntreeRef mouvement d'entree des portions (journal STOCK).
 */
public record ProductionResponse(
    Long id,
    String reference,
    LocalDate dateProduction,
    Long platId,
    String platCode,
    String platLibelle,
    String uniteMesure,
    Long entrepotId,
    String entrepotNom,
    BigDecimal quantite,
    BigDecimal coutTotal,
    BigDecimal coutUnitaire,
    StatutProduction statut,
    String mouvementSortieRef,
    String mouvementEntreeRef,
    String createdByNom,
    Instant createdAt,
    List<LigneProductionResponse> lignes
) {
    public static ProductionResponse from(Production p) {
        var plat = p.getPlat();
        var entrepot = p.getEntrepot();
        var auteur = p.getCreatedBy();
        String auteurNom = auteur == null ? null
            : ((auteur.getPrenom() == null ? "" : auteur.getPrenom()) + " "
             + (auteur.getNom() == null ? "" : auteur.getNom())).trim();

        return new ProductionResponse(
            p.getId(),
            p.getReference(),
            p.getDateProduction(),
            plat.getId(), plat.getCode(), plat.getLibelle(), plat.getUniteMesure(),
            entrepot.getId(), entrepot.getNom(),
            p.getQuantite(),
            p.getCoutTotal(),
            p.getCoutUnitaire(),
            p.getStatut(),
            p.getMouvementSortie() == null ? null : p.getMouvementSortie().getReference(),
            p.getMouvementEntree() == null ? null : p.getMouvementEntree().getReference(),
            auteurNom,
            p.getCreatedAt(),
            p.getLignes() == null ? List.of()
                : p.getLignes().stream().map(LigneProductionResponse::from).toList()
        );
    }
}
