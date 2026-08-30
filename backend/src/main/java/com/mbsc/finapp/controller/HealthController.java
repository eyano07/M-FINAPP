package com.mbsc.finapp.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Endpoint public de sante, utilise notamment par le NetworkMonitor du desktop JavaFX.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "UP",
            "service", "mbsc-finapp",
            "timestamp", Instant.now().toString()
        );
    }
}
