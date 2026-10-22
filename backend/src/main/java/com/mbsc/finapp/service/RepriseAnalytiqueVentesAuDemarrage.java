package com.mbsc.finapp.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Au démarrage de l'application, reflète en comptabilité analytique les ventes validées qui ne le sont pas encore : celles
 * d'avant cette fonctionnalité (reprise unique) et celles restées sans reflet (compte désactivé, période clôturée...).
 * Sans effet quand tout est déjà reflété. Voir {@link AnalytiqueVentesService#reprendreVentesSansReflet}.
 */
@Component
@RequiredArgsConstructor
public class RepriseAnalytiqueVentesAuDemarrage {

    private static final Logger log = LoggerFactory.getLogger(RepriseAnalytiqueVentesAuDemarrage.class);

    private final AnalytiqueVentesService analytique;

    @EventListener(ApplicationReadyEvent.class)
    public void reprendre() {
        try {
            analytique.reprendreVentesSansReflet();
        } catch (RuntimeException e) {
            log.warn("Reprise analytique des ventes interrompue : {}", e.getMessage());
        }
    }
}
