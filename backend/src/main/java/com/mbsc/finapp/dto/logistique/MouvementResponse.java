package com.mbsc.finapp.dto.logistique;

import com.mbsc.finapp.domain.MouvementStock;
import com.mbsc.finapp.domain.enums.StatutMouvement;
import com.mbsc.finapp.domain.enums.TypeMouvementStock;

import java.time.LocalDate;
import java.util.List;

public record MouvementResponse(
    Long id,
    String reference,
    TypeMouvementStock type,
    LocalDate dateMouvement,
    String libelle,
    StatutMouvement statut,
    String compteContrepartieNumero,
    String pieceReference,
    String createdByEmail,
    List<LigneMouvementResponse> lignes
) {
    public static MouvementResponse from(MouvementStock m) {
        return from(m, true);
    }

    public static MouvementResponse from(MouvementStock m, boolean withLignes) {
        List<LigneMouvementResponse> lignesDto = withLignes && m.getLignes() != null
            ? m.getLignes().stream().map(LigneMouvementResponse::from).toList()
            : List.of();
        return new MouvementResponse(
            m.getId(),
            m.getReference(),
            m.getType(),
            m.getDateMouvement(),
            m.getLibelle(),
            m.getStatut(),
            m.getCompteContrepartie() == null ? null : m.getCompteContrepartie().getNumero(),
            m.getPiece() == null ? null : m.getPiece().getReference(),
            m.getCreatedBy() == null ? null : m.getCreatedBy().getEmail(),
            lignesDto
        );
    }
}
