package com.mbsc.finapp.config;

import com.mbsc.finapp.service.SynchronisationComptesModules;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Au demarrage, met les comptes en conformite avec l'etat des modules : un module deja desactive (avant que la
 * cascade n'existe, ou modifie directement en base) doit avoir ses comptes desactives et masques, comme s'il
 * venait d'etre coupe — voir {@link SynchronisationComptesModules}. Ne fait rien quand tout est deja conforme.
 *
 * <p>Ne bloque jamais le demarrage : une erreur est journalisee, l'application demarre quand meme (la
 * conformite sera retablie a la prochaine bascule d'un module).</p>
 */
@Component
@RequiredArgsConstructor
public class ComptesModulesAuDemarrage implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ComptesModulesAuDemarrage.class);

    private final SynchronisationComptesModules synchronisation;

    @Override
    public void run(ApplicationArguments args) {
        try {
            SynchronisationComptesModules.Resultat resultat = synchronisation.reconcilier();
            if (resultat.vide()) {
                log.info("Comptes et modules : tout est conforme.");
            }
        } catch (RuntimeException e) {
            log.error("Mise en conformite des comptes avec les modules impossible au demarrage : {}", e.toString());
        }
    }
}
