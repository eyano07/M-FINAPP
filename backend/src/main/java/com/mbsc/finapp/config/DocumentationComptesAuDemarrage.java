package com.mbsc.finapp.config;

import com.mbsc.finapp.service.DocumentationCompteService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import com.mbsc.finapp.service.instance.InstanceService;

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
    private final InstanceService instance;

    @Override
    public void run(ApplicationArguments args) {
        // Copie en ligne (ou ancien serveur local) : rien n'est ecrit ici, les donnees viennent du serveur principal (V132).
        if (instance.lectureSeule()) {
            return;
        }
        documentation.documenterComptesExistants();
    }
}
