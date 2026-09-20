package com.mbsc.finapp.dto.vente;

import com.mbsc.finapp.domain.enums.Devise;

import java.math.BigDecimal;
import java.util.List;

/** Resultat du reglement groupe de toutes les creances ouvertes d'une table (voir VenteService.reglerAdditionTable). */
public record AdditionReglementResponse(
    List<VenteResponse> ventesReglees,
    BigDecimal totalEncaisse,
    Devise deviseTotalEncaisse
) {}
