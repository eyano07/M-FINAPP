package com.mbsc.finapp.dto.budget;

import java.math.BigDecimal;
import java.util.List;

public record RepartitionResponse(List<BigDecimal> mensuel, String mode, String explication) {}
