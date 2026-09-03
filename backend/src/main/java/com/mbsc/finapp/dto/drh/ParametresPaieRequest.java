package com.mbsc.finapp.dto.drh;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record ParametresPaieRequest(
    @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal tauxLogement,
    @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal tauxTransport,
    @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal tauxCnssOuvriere,
    @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal tauxCnssPatronale,
    @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal tauxOnem,
    @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal tauxInpp,
    @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal reductionIprParEnfant,
    @NotNull @Min(0) @Max(20) Integer plafondEnfantsIpr,
    @NotNull @DecimalMin("0") BigDecimal plancherIprFc,
    @NotNull @Min(1) @Max(31) Integer joursOuvrablesStandard,
    // Conformité RDC — voir V47__drh_conformite_rdc.sql
    boolean cnssDeductibleIpr,
    // Comptabilisation optionnelle de la paie — voir V57__paie_comptabilisation_optionnelle.sql
    boolean comptabiliserPaie,
    @NotNull @DecimalMin("0") BigDecimal smigJournalierFc,
    @NotNull @Min(1) @Max(31) Integer diviseurAllocationFamiliale,
    @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal plafondRetenuePct,
    @NotNull @DecimalMin("0") BigDecimal plafondTransportExonereFcJour,
    @NotNull @Min(1) @Max(80) Integer heuresLegalesHebdo,
    @NotNull @DecimalMin("0") @DecimalMax("5") BigDecimal tauxMajorationHs1,
    @NotNull @DecimalMin("0") @DecimalMax("5") BigDecimal tauxMajorationHs2,
    @NotNull @DecimalMin("0") @DecimalMax("5") BigDecimal tauxMajorationHsFerie,
    @Size(max = 150) String directeurDrh,
    @Size(max = 150) String fonctionDirecteur,
    @Size(max = 80) String villeSignature
) {}
