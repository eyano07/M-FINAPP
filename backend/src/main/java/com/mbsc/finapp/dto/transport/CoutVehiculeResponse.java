package com.mbsc.finapp.dto.transport;

import java.math.BigDecimal;

public record CoutVehiculeResponse(
    Long vehiculeId,
    String immatriculation,
    BigDecimal totalDepenses,
    BigDecimal totalDistanceKm,
    BigDecimal coutParKm
) {}
