package com.mbsc.finapp.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Règle voulue : les entrées de provisions passent toujours par l'achat (note de frais : DFIN, DA, caisse), jamais par
 * une réception saisie au restaurant, qui passait une écriture d'achat sans aucune approbation.
 */
class EntreeProvisionParAchatTest {

    @Test
    void leServiceNeRecoitPlusDeProvisionDirectement() {
        assertThat(Arrays.stream(RestaurantService.class.getDeclaredMethods()).map(m -> m.getName()))
            .doesNotContain("recevoirProvision");
    }

    @Test
    void aucuneRouteDEntreeDeProvisionN_existe() throws IOException {
        Path controleur = Path.of("src/main/java/com/mbsc/finapp/controller/RestaurantController.java");
        assertThat(controleur).as("contrôleur introuvable : " + controleur.toAbsolutePath()).exists();
        String source = Files.readString(controleur);
        assertThat(source).doesNotContain("provisions/entrees");
        // Les sorties, elles, se saisissent toujours au restaurant.
        assertThat(source).contains("\"/provisions/sorties\"");
    }
}
