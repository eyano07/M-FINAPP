package com.mbsc.finapp.dto.provision;

import com.mbsc.finapp.domain.Provision;
import com.mbsc.finapp.domain.enums.StatutProvision;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ProvisionResponse(
    Long id,
    String reference,
    String libelle,
    String compteProvisionNumero,
    String compteProvisionLibelle,
    String compteDotationNumero,
    BigDecimal montantConstitue,
    BigDecimal montantRepris,
    BigDecimal soldeRestant,
    StatutProvision statut,
    LocalDate dateConstitution,
    String pieceConstitutionReference,
    String createdByNom,
    List<RepriseResponse> reprises
) {
    public static ProvisionResponse from(Provision p) {
        return from(p, false);
    }

    public static ProvisionResponse from(Provision p, boolean withReprises) {
        var auteur = p.getCreatedBy();
        String auteurNom = auteur == null ? null
            : ((auteur.getPrenom() == null ? "" : auteur.getPrenom()) + " "
             + (auteur.getNom() == null ? "" : auteur.getNom())).trim();

        return new ProvisionResponse(
            p.getId(),
            p.getReference(),
            p.getLibelle(),
            p.getCompteProvision().getNumero(),
            p.getCompteProvision().getLibelle(),
            p.getCompteDotation().getNumero(),
            p.getMontantConstitue(),
            p.getMontantRepris(),
            p.soldeRestant(),
            p.getStatut(),
            p.getDateConstitution(),
            p.getPieceConstitution() == null ? null : p.getPieceConstitution().getReference(),
            auteurNom,
            withReprises && p.getReprises() != null
                ? p.getReprises().stream().map(RepriseResponse::from).toList()
                : List.of()
        );
    }
}
