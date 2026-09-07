package com.mbsc.finapp.dto.drh;

import com.mbsc.finapp.domain.ParametresPaie;

import java.math.BigDecimal;

public record ParametresPaieResponse(
    BigDecimal tauxLogement,
    BigDecimal tauxTransport,
    BigDecimal tauxCnssOuvriere,
    BigDecimal tauxCnssPatronale,
    BigDecimal tauxOnem,
    BigDecimal tauxInpp,
    BigDecimal reductionIprParEnfant,
    Integer plafondEnfantsIpr,
    boolean calculEnfantsActif,
    BigDecimal plancherIprFc,
    Integer joursOuvrablesStandard,
    boolean cnssDeductibleIpr,
    boolean comptabiliserPaie,
    BigDecimal smigJournalierFc,
    Integer diviseurAllocationFamiliale,
    BigDecimal plafondRetenuePct,
    BigDecimal plafondTransportExonereFcJour,
    Integer heuresLegalesHebdo,
    BigDecimal tauxMajorationHs1,
    BigDecimal tauxMajorationHs2,
    BigDecimal tauxMajorationHsFerie,
    String directeurDrh,
    String fonctionDirecteur,
    String villeSignature
) {
    public static ParametresPaieResponse from(ParametresPaie p) {
        return new ParametresPaieResponse(
            p.getTauxLogement(), p.getTauxTransport(), p.getTauxCnssOuvriere(), p.getTauxCnssPatronale(),
            p.getTauxOnem(), p.getTauxInpp(), p.getReductionIprParEnfant(), p.getPlafondEnfantsIpr(),
            p.isCalculEnfantsActif(), p.getPlancherIprFc(), p.getJoursOuvrablesStandard(),
            p.isCnssDeductibleIpr(), p.isComptabiliserPaie(), p.getSmigJournalierFc(), p.getDiviseurAllocationFamiliale(),
            p.getPlafondRetenuePct(), p.getPlafondTransportExonereFcJour(), p.getHeuresLegalesHebdo(),
            p.getTauxMajorationHs1(), p.getTauxMajorationHs2(), p.getTauxMajorationHsFerie(),
            p.getDirecteurDrh(), p.getFonctionDirecteur(), p.getVilleSignature());
    }
}
