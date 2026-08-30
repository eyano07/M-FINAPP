package com.mbsc.finapp.dto.logistique;

import com.mbsc.finapp.domain.enums.TypeMouvementStock;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record MouvementRequest(
    @NotNull TypeMouvementStock type,
    @NotNull LocalDate dateMouvement,
    String libelle,
    String compteContrepartieNumero,
    @NotEmpty @Valid List<LigneMouvementRequest> lignes
) {}
