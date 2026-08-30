package com.mbsc.finapp.dto.transport;

import com.mbsc.finapp.domain.enums.TypeDepenseVehicule;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DepenseRequest(
    @NotNull Long vehiculeId,
    Long trajetId,
    @NotNull TypeDepenseVehicule type,
    @NotNull @Positive BigDecimal montant,
    @NotNull LocalDate dateDepense,
    String compteChargeNumero
) {}
