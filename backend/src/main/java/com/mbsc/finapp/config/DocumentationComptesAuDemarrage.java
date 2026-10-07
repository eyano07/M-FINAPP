package com.mbsc.finapp.config;

import com.mbsc.finapp.service.DocumentationCompteService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Au démarrage, rédige la fiche de documentation des comptes ajoutés avant que
 * chaque création n'en reçoive une (comptes manuels sans aucune rubrique) — voir
 * {@link DocumentationCompteService#documenterComptesExistants()}. Ne fait rien une
 * fois tous les comptes documentés ; une fiche existante n'est jamais modifiée.
 */
@Component
@RequiredArgsConstructor
public class DocumentationComptesAuDemarrage implements ApplicationRunner {

    private final DocumentationCompteService documentation;

    @Override
    public void run(ApplicationArguments args) {
        documentation.documenterComptesExistants();
    }
}
