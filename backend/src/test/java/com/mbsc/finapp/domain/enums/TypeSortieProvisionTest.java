package com.mbsc.finapp.domain.enums;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Les types de sortie de provision : chacun a un libellé lisible, et la base n'en accepte pas d'autre. */
class TypeSortieProvisionTest {

    @Test
    void chaqueTypeALeSienLibelleLisible() {
        for (TypeSortieProvision type : TypeSortieProvision.values()) {
            assertThat(type.libelle()).as(type.name()).isNotBlank();
        }
        assertThat(Arrays.stream(TypeSortieProvision.values()).map(TypeSortieProvision::libelle).distinct().count())
            .as("deux types ne portent pas le même libellé").isEqualTo(TypeSortieProvision.values().length);
    }

    @Test
    void laListeComprendLesSortiesUsuellesEtUnTypeAutre() {
        assertThat(TypeSortieProvision.values()).extracting(Enum::name).contains(
            "UTILISATION_CUISINE", "CASSE", "PEREMPTION", "AVARIE", "PERTE", "REPAS_PERSONNEL", "DON", "AUTRE");
    }

    /** La contrainte CHECK de la table et l'énumération disent la même chose : en ajouter un côté sans l'autre échoue ici. */
    @Test
    void laContrainteDeLaBaseAccepteExactementLesTypesDeLEnumeration() throws IOException {
        Path migration = Path.of("src/main/resources/db/migration/V117__sorties_provisions_type_et_observation.sql");
        assertThat(migration).as("migration introuvable : " + migration.toAbsolutePath()).exists();
        String sql = Files.readString(migration);
        Matcher check = Pattern.compile("CHECK \\(type_sortie IN \\(([^)]*)\\)\\)").matcher(sql);
        assertThat(check.find()).as("contrainte CHECK sur type_sortie").isTrue();
        Set<String> accepte = Arrays.stream(check.group(1).split(","))
            .map(s -> s.trim().replace("'", ""))
            .collect(Collectors.toSet());
        Set<String> enumeres = Arrays.stream(TypeSortieProvision.values()).map(Enum::name).collect(Collectors.toSet());
        assertThat(accepte).isEqualTo(enumeres);
    }
}
