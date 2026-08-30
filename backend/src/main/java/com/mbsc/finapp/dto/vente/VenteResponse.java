package com.mbsc.finapp.dto.vente;

import com.mbsc.finapp.domain.Vente;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.ModeReglement;
import com.mbsc.finapp.domain.enums.StatutVente;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Vue d'une vente.
 *
 * @param pieceReference    piece du journal VENTES (recette + TVA)
 * @param mouvementReference mouvement de sortie de stock (cout des ventes),
 *                          null pour une vente de services seuls
 */
public record VenteResponse(
    Long id,
    String reference,
    LocalDate dateVente,
    Long clientId,
    String clientNom,
    StatutVente statut,
    ModeReglement modeReglement,
    Long etablissementId,
    String etablissementNom,
    Long entrepotId,
    String entrepotNom,
    BigDecimal totalHt,
    BigDecimal totalTva,
    BigDecimal totalTtc,
    Devise devise,
    BigDecimal tauxJournalier,
    BigDecimal tauxTvaApplique,
    String pieceReference,
    String mouvementReference,
    String createdByNom,
    Instant createdAt,
    boolean reglee,
    LocalDate dateReglement,
    List<LigneVenteResponse> lignes
) {
    public static VenteResponse from(Vente v) {
        return from(v, true);
    }

    public static VenteResponse from(Vente v, boolean withLignes) {
        List<LigneVenteResponse> lignesDto = withLignes && v.getLignes() != null
            ? v.getLignes().stream().map(LigneVenteResponse::from).toList()
            : List.of();

        var auteur = v.getCreatedBy();
        String auteurNom = auteur == null ? null
            : ((auteur.getPrenom() == null ? "" : auteur.getPrenom()) + " "
             + (auteur.getNom() == null ? "" : auteur.getNom())).trim();

        return new VenteResponse(
            v.getId(),
            v.getReference(),
            v.getDateVente(),
            v.getClient() == null ? null : v.getClient().getId(),
            v.designationClient(),
            v.getStatut(),
            v.getModeReglement(),
            v.getEtablissement() == null ? null : v.getEtablissement().getId(),
            v.getEtablissement() == null ? null : v.getEtablissement().getNom(),
            v.getEntrepot() == null ? null : v.getEntrepot().getId(),
            v.getEntrepot() == null ? null : v.getEntrepot().getNom(),
            v.getTotalHt(),
            v.getTotalTva(),
            v.getTotalTtc(),
            v.getDevise(),
            v.getTauxJournalier(),
            v.getTauxTvaApplique(),
            v.getPiece() == null ? null : v.getPiece().getReference(),
            v.getMouvement() == null ? null : v.getMouvement().getReference(),
            auteurNom,
            v.getCreatedAt(),
            v.estReglee(),
            v.getDateReglement(),
            lignesDto
        );
    }
}
