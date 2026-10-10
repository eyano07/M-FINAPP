package com.mbsc.finapp.dto.emcf;

import com.mbsc.finapp.domain.FactureNormalisee;

import java.math.BigDecimal;
import java.time.Instant;

/** Etat fiscal d'une facture (ou d'un avoir) de vente. */
public record FactureNormaliseeResponse(
    Long id,
    Long venteId,
    String venteReference,
    String clientNom,
    BigDecimal totalTtc,
    String devise,
    String type,
    String statut,
    String mode,
    boolean simulation,
    String uid,
    String signature,
    String numeroDef,
    Instant dateFiscale,
    String codeQr,
    int tentatives,
    String derniereErreur,
    Instant prochaineTentative,
    Instant dateCreation
) {
    public static FactureNormaliseeResponse from(FactureNormalisee f) {
        var v = f.getVente();
        return new FactureNormaliseeResponse(f.getId(), v.getId(), v.getReference(), v.designationClient(), v.getTotalTtc(),
            v.getDevise().name(), f.getType().name(), f.getStatut().name(), f.getMode(), "SIMULATION".equals(f.getMode()),
            f.getUid(), f.getSignature(), f.getNumeroDef(), f.getDateFiscale(), f.getCodeQr(), f.getTentatives(),
            f.getDerniereErreur(), f.getProchaineTentative(), f.getDateCreation());
    }
}
