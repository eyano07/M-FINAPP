package com.mbsc.finapp.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Code d'une nouvelle boisson, déduit de son libellé et de sa société : lisible, sans accents,
 * limité à la colonne, et unique.
 */
class GenerateurCodeArticleTest {

    private static final Predicate<String> RIEN_N_EXISTE = code -> false;

    private static String code(String libelle, String societe) {
        return GenerateurCodeArticle.generer(libelle, societe, RIEN_N_EXISTE);
    }

    // ---------------------------------------------------------------------
    // Le code se lit : société, mots du libellé, contenance
    // ---------------------------------------------------------------------

    @Test
    void les_boissons_de_la_carte_avec_leur_societe() {
        assertThat(code("Primus 55CL", "Bracongo")).isEqualTo("BRAC-PRIM-55CL");
        assertThat(code("Coca Cola 33CL", "Bracongo")).isEqualTo("BRAC-COCA-COLA-33CL");
        assertThat(code("Simba 55CL", "Brasimba")).isEqualTo("BRAS-SIMB-55CL");
    }

    @Test
    void deux_societes_au_nom_voisin_donnent_des_prefixes_distincts() {
        assertThat(code("Primus 55CL", "Bracongo")).startsWith("BRAC-");
        assertThat(code("Primus 55CL", "Bralima")).startsWith("BRAL-");
        assertThat(code("Primus 55CL", "Brasimba")).startsWith("BRAS-");
    }

    @Test
    void sans_societe_le_code_ne_porte_que_le_libelle() {
        assertThat(code("Primus 55CL", null)).isEqualTo("PRIM-55CL");
        assertThat(code("Primus 55CL", "")).isEqualTo("PRIM-55CL");
        assertThat(code("Primus 55CL", "   ")).isEqualTo("PRIM-55CL");
    }

    @Test
    void une_societe_de_plusieurs_mots_donne_ses_initiales() {
        assertThat(code("Primus 55CL", "Brasserie du Congo")).isEqualTo("BC-PRIM-55CL");
        assertThat(code("Jus d'orange 1L", "Coca-Cola Company")).isEqualTo("CCC-JUS-ORAN-1L");
        assertThat(code("Primus 55CL", "Société Générale")).isEqualTo("SG-PRIM-55CL");
    }

    @Test
    void les_formes_juridiques_et_les_mots_vides_de_la_societe_sont_ignores() {
        assertThat(code("Primus 55CL", "Brasserie du Congo SARL")).isEqualTo("BC-PRIM-55CL");
        assertThat(code("Primus 55CL", "Bracongo SA")).isEqualTo("BRAC-PRIM-55CL");
        assertThat(code("Primus 55CL", "SARL")).isEqualTo("PRIM-55CL");
    }

    @Test
    void les_accents_les_majuscules_et_les_ligatures_disparaissent() {
        assertThat(code("Bière légère 33 cl", "Heineken")).isEqualTo("HEIN-BIER-LEGE-33CL");
        assertThat(code("Œuf dur", null)).isEqualTo("OEUF-DUR");
        assertThat(code("café noir", "Café Congo")).isEqualTo("CC-CAFE-NOIR");
    }

    @Test
    void les_mots_vides_du_libelle_sont_ignores() {
        assertThat(code("Jus d'orange 1L", null)).isEqualTo("JUS-ORAN-1L");
        assertThat(code("Eau de source", null)).isEqualTo("EAU-SOUR");
        assertThat(code("Vin de la maison", null)).isEqualTo("VIN-MAIS");
    }

    @Test
    void seuls_les_trois_premiers_mots_du_libelle_comptent() {
        assertThat(code("Whisky Johnnie Walker Red Label 75CL", "Diageo")).isEqualTo("DIAG-WHIS-JOHN-WALK-75CL");
    }

    // ---------------------------------------------------------------------
    // Contenances
    // ---------------------------------------------------------------------

    @Test
    void la_contenance_est_reconnue_sous_toutes_ses_ecritures() {
        assertThat(code("Eau minérale 1,5L", null)).isEqualTo("EAU-MINE-15L");
        assertThat(code("Eau minérale 1.5 L", null)).isEqualTo("EAU-MINE-15L");
        assertThat(code("Coca 33 cl", null)).isEqualTo("COCA-33CL");
        assertThat(code("Coca 33CL", null)).isEqualTo("COCA-33CL");
        assertThat(code("Vin rouge 75 CL", null)).isEqualTo("VIN-ROUG-75CL");
        assertThat(code("Sucre 1 kg", null)).isEqualTo("SUCR-1KG");
    }

    @Test
    void sans_contenance_le_code_s_arrete_au_libelle() {
        assertThat(code("Whisky", "Johnnie")).isEqualTo("JOHN-WHIS");
        assertThat(code("Omelette", null)).isEqualTo("OMEL");
    }

    @Test
    void un_libelle_reduit_a_une_contenance_garde_la_societe() {
        assertThat(code("33CL", "Bracongo")).isEqualTo("BRAC-33CL");
        assertThat(code("33", null)).isEqualTo("33");
    }

    // ---------------------------------------------------------------------
    // Cas limites
    // ---------------------------------------------------------------------

    @Test
    void un_libelle_vide_ou_sans_lettre_ni_chiffre_ne_donne_pas_de_code() {
        assertThat(code(null, "Bracongo")).isEmpty();
        assertThat(code("", "Bracongo")).isEmpty();
        assertThat(code("   ", "Bracongo")).isEmpty();
        assertThat(code("!!! -- ???", "Bracongo")).isEmpty();
    }

    @Test
    void un_libelle_fait_de_mots_vides_donne_quand_meme_un_code() {
        assertThat(code("De la", null)).isEqualTo("DE");
    }

    @Test
    void le_code_ne_contient_que_des_majuscules_chiffres_et_tirets() {
        for (String libelle : new String[] {"Bière « Spéciale » 50 cl", "Jus d'ananas (bio)", "Crème brûlée & café", "Ñandú 1,5l"}) {
            assertThat(code(libelle, "Société À&B")).matches("[A-Z0-9]+(-[A-Z0-9]+)*");
        }
    }

    @Test
    void le_code_ne_depasse_jamais_la_colonne() {
        String libelle = "Extraordinairement Interminable Dénomination Commerciale 123456789012345678901234567890CL";
        String code = code(libelle, "Compagnie Internationale Générale Nationale Universelle");

        assertThat(code.length()).isLessThanOrEqualTo(GenerateurCodeArticle.LONGUEUR_MAX);
        assertThat(code).doesNotEndWith("-");
    }

    // ---------------------------------------------------------------------
    // Unicité
    // ---------------------------------------------------------------------

    @Test
    void un_code_deja_pris_recoit_un_numero() {
        Set<String> pris = new HashSet<>(Set.of("BRAC-PRIM-55CL"));

        assertThat(GenerateurCodeArticle.generer("Primus 55CL", "Bracongo", pris::contains)).isEqualTo("BRAC-PRIM-55CL-2");

        pris.add("BRAC-PRIM-55CL-2");
        assertThat(GenerateurCodeArticle.generer("Primus 55CL", "Bracongo", pris::contains)).isEqualTo("BRAC-PRIM-55CL-3");
    }

    @Test
    void le_numero_ne_fait_pas_depasser_la_colonne() {
        String base = GenerateurCodeArticle.codeDeBase(
            "Extraordinairement Interminable Dénomination Commerciale 123456789012345678901234567890CL",
            "Compagnie Internationale Générale Nationale Universelle");
        Set<String> pris = new HashSet<>(Set.of(base));

        String code = GenerateurCodeArticle.generer(
            "Extraordinairement Interminable Dénomination Commerciale 123456789012345678901234567890CL",
            "Compagnie Internationale Générale Nationale Universelle", pris::contains);

        assertThat(code).endsWith("-2");
        assertThat(code.length()).isLessThanOrEqualTo(GenerateurCodeArticle.LONGUEUR_MAX);
        assertThat(pris).doesNotContain(code);
    }

    @Test
    void les_codes_manuels_deja_en_place_ne_genent_pas() {
        // Les codes de la carte actuelle (saisis à la main) ne suivent pas ce modèle : aucune collision.
        Set<String> pris = Set.of("BRA-01", "BRA-02", "BRSIM-01", "DEJ-01");

        assertThat(GenerateurCodeArticle.generer("Primus 55CL", "Bracongo", pris::contains)).isEqualTo("BRAC-PRIM-55CL");
    }

    @Test
    void la_meme_saisie_donne_toujours_le_meme_code() {
        assertThat(code("Primus 55CL", "Bracongo")).isEqualTo(code("  primus   55cl ", " BRACONGO "));
    }
}
