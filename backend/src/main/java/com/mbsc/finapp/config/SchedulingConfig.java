package com.mbsc.finapp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Active les tâches planifiées (retransmission des factures normalisées en attente). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
