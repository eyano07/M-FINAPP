package com.mbsc.finapp.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Qui peut quoi dans le restaurant : le responsable cuisine (plats, provisions) et le responsable bar (boissons,
 * bouteilles vides) ne se voient que chez eux, le responsable restaurant garde tout. Les regles sont portees par
 * des {@code @PreAuthorize} : le test les relit, comme {@link SeparationDesTachesTest}, pour qu'un changement
 * involontaire — ou une nouvelle methode oubliee — fasse echouer le build.
 */
class RolesRestaurantGardesTest {

    private static final Pattern ROLE = Pattern.compile("'([A-Z_]+)'");

    /** Roles cites par les gardes de la methode (toutes les surcharges du meme nom). */
    private static Set<String> roles(Class<?> classe, String methode) {
        List<Method> methodes = Arrays.stream(classe.getDeclaredMethods())
            .filter(m -> m.getName().equals(methode)).toList();
        assertThat(methodes).as(classe.getSimpleName() + "." + methode + " existe").isNotEmpty();
        Set<String> roles = new LinkedHashSet<>();
        for (Method m : methodes) {
            PreAuthorize garde = m.getAnnotation(PreAuthorize.class);
            assertThat(garde).as(classe.getSimpleName() + "." + methode + " porte une garde").isNotNull();
            Matcher matcher = ROLE.matcher(garde.value());
            while (matcher.find()) {
                roles.add(matcher.group(1));
            }
        }
        return roles;
    }

    private static final List<String> CUISINE_LECTURE = List.of(
        "consulterRecette", "listerRecettes", "listerProductions", "listerPlatsPerimes", "listerProvisions",
        "etatStockProvisions", "listerLotsProvisions", "grandLivreProvisions", "tableauBordProvisions", "listerSortiesPlats");
    private static final List<String> CUISINE_ECRITURE = List.of(
        "enregistrerRecette", "modifierPrixVenteCarte", "produire", "annulerProduction", "retirerPlatsPerimes",
        "suggererCodeProvision", "creerProvision", "modifierProvision", "annulerMouvementProvision",
        "enregistrerSortieProvision", "enregistrerSortiePlat", "annulerSortiePlat");
    private static final List<String> BAR_LECTURE = List.of(
        "listerLotsBoissons", "listerEmballages", "listerMouvements", "tableauBord", "listerSortiesBoissons");
    private static final List<String> BAR_ECRITURE = List.of(
        "creerEmballage", "modifierEmballage", "enregistrerMouvement", "annulerMouvementEmballage",
        "enregistrerSortieBoisson", "annulerSortieBoisson");
    private static final List<String> PARTAGEES = List.of(
        "listerCarte", "etatStockCarte", "listerEntrepots");
    private static final List<String> SALLES_ET_TABLES = List.of(
        "listerSalles", "listerCommandesTable", "changerStatutTable");
    private static final List<String> ADMIN_SEUL = List.of(
        "creerArticleCarte", "suggererCodeArticleCarte", "modifierArticleCarte", "supprimerArticleCarte",
        "creerSalle", "modifierSalle", "supprimerSalle", "enregistrerPlan");

    // ---------------------------------------------------------------------
    // RestaurantService
    // ---------------------------------------------------------------------

    @Test
    void la_cuisine_est_ouverte_au_responsable_cuisine_et_fermee_au_responsable_bar() {
        for (String methode : CUISINE_LECTURE) {
            Set<String> roles = roles(RestaurantService.class, methode);
            assertThat(roles).as(methode).contains("RESP_RESTAURANT", "RESP_CUISINE").doesNotContain("RESP_BAR");
        }
        for (String methode : CUISINE_ECRITURE) {
            assertThat(roles(RestaurantService.class, methode)).as(methode)
                .containsExactlyInAnyOrder("RESP_RESTAURANT", "RESP_CUISINE", "ADMIN");
        }
    }

    @Test
    void le_bar_est_ouvert_au_responsable_bar_et_ferme_au_responsable_cuisine() {
        for (String methode : BAR_LECTURE) {
            Set<String> roles = roles(RestaurantService.class, methode);
            assertThat(roles).as(methode).contains("RESP_RESTAURANT", "RESP_BAR").doesNotContain("RESP_CUISINE");
        }
        for (String methode : BAR_ECRITURE) {
            assertThat(roles(RestaurantService.class, methode)).as(methode)
                .containsExactlyInAnyOrder("RESP_RESTAURANT", "RESP_BAR", "ADMIN");
        }
    }

    @Test
    void la_carte_le_stock_les_entrepots_et_les_analyses_sont_partages_puis_filtres_par_domaine() {
        for (String methode : PARTAGEES) {
            assertThat(roles(RestaurantService.class, methode)).as(methode).contains("RESP_RESTAURANT", "RESP_CUISINE", "RESP_BAR");
        }
        assertThat(roles(RestaurantService.class, "analyserVentes"))
            .containsExactlyInAnyOrder("RESP_RESTAURANT", "RESP_CUISINE", "RESP_BAR", "DFIN", "DG", "ADMIN");
    }

    @Test
    void ni_la_cuisine_ni_le_bar_n_ont_acces_aux_salles_et_aux_tables() {
        for (String methode : SALLES_ET_TABLES) {
            assertThat(roles(RestaurantService.class, methode)).as(methode).doesNotContain("RESP_CUISINE", "RESP_BAR");
        }
    }

    @Test
    void la_carte_et_le_plan_des_salles_restent_a_l_administrateur_seul() {
        for (String methode : ADMIN_SEUL) {
            assertThat(roles(RestaurantService.class, methode)).as(methode).containsExactly("ADMIN");
        }
    }

    /** Une methode gardee ajoutee plus tard doit etre classee ici : sinon elle echapperait a la separation. */
    @Test
    void chaque_methode_gardee_du_service_restaurant_est_classee() {
        List<String> classees = new ArrayList<>();
        classees.addAll(CUISINE_LECTURE);
        classees.addAll(CUISINE_ECRITURE);
        classees.addAll(BAR_LECTURE);
        classees.addAll(BAR_ECRITURE);
        classees.addAll(PARTAGEES);
        classees.addAll(SALLES_ET_TABLES);
        classees.addAll(ADMIN_SEUL);
        classees.add("analyserVentes");

        List<String> gardees = Arrays.stream(RestaurantService.class.getDeclaredMethods())
            .filter(m -> m.isAnnotationPresent(PreAuthorize.class))
            .map(Method::getName).distinct().toList();

        assertThat(gardees).as("methodes gardees non classees").allMatch(classees::contains);
        assertThat(classees).as("methodes classees mais disparues").allMatch(gardees::contains);
    }

    /** Les methodes publiques sans garde sont les variantes internes (autorisation portee par l'appelant). */
    @Test
    void les_seules_methodes_publiques_sans_garde_sont_les_variantes_internes() {
        List<String> sansGarde = Arrays.stream(RestaurantService.class.getDeclaredMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers()))
            .filter(m -> !m.isAnnotationPresent(PreAuthorize.class))
            .filter(m -> !m.isSynthetic())
            .map(Method::getName).distinct().toList();

        assertThat(sansGarde).allMatch(nom -> nom.endsWith("Interne") || nom.equals("appliquer"),
            "methodes publiques sans garde : " + sansGarde);
    }

    // ---------------------------------------------------------------------
    // Paramètres, analyses IA
    // ---------------------------------------------------------------------

    @Test
    void les_parametres_se_lisent_dans_les_deux_domaines_mais_s_ecrivent_comme_avant() {
        assertThat(roles(ParametresRestaurantService.class, "consulter")).contains("RESP_CUISINE", "RESP_BAR");
        assertThat(roles(ParametresRestaurantService.class, "enregistrer")).containsExactlyInAnyOrder("RESP_RESTAURANT", "ADMIN");
        assertThat(roles(ParametresRestaurantService.class, "definirRetraitPerimes")).containsExactly("ADMIN");
    }

    @Test
    void l_analyse_ia_du_bar_va_au_bar_et_celle_des_provisions_a_la_cuisine() {
        assertThat(roles(RestaurantAnalyseIaService.class, "analyser")).contains("RESP_BAR").doesNotContain("RESP_CUISINE");
        assertThat(roles(RestaurantAnalyseIaService.class, "analyserProvisions")).contains("RESP_CUISINE").doesNotContain("RESP_BAR");
    }

    // ---------------------------------------------------------------------
    // Notes de frais (achats)
    // ---------------------------------------------------------------------

    /** Un garde qui cite le responsable restaurant doit citer aussi les deux autres responsables du restaurant. */
    @Test
    void les_gardes_des_notes_qui_citent_le_responsable_restaurant_citent_aussi_cuisine_et_bar() {
        int gardesCitantLeResponsable = 0;
        for (Method m : NoteFraisService.class.getDeclaredMethods()) {
            PreAuthorize garde = m.getAnnotation(PreAuthorize.class);
            if (garde == null || !garde.value().contains("'RESP_RESTAURANT'")) {
                continue;
            }
            gardesCitantLeResponsable++;
            assertThat(garde.value()).as(m.getName()).contains("'RESP_CUISINE'").contains("'RESP_BAR'");
        }
        assertThat(gardesCitantLeResponsable).isEqualTo(9);
    }

    // ---------------------------------------------------------------------
    // Menu Logistique : lecture seule pour la cuisine et le bar
    // ---------------------------------------------------------------------

    private static final List<String> LOGISTIQUE_LECTURES = List.of(
        "listerArticles", "consulterArticle", "listerEntrepots", "listerMouvements", "consulterMouvement",
        "etatStock", "grandLivreStock");

    @Test
    void la_cuisine_et_le_bar_lisent_la_logistique_sans_pouvoir_y_ecrire() {
        for (String methode : LOGISTIQUE_LECTURES) {
            assertThat(roles(StockService.class, methode)).as(methode).contains("RESP_CUISINE", "RESP_BAR");
            assertThat(roles(StockService.class, methode)).as(methode).doesNotContain("RESP_RESTAURANT");
        }
        List<String> ecritures = Arrays.stream(StockService.class.getDeclaredMethods())
            .filter(m -> m.isAnnotationPresent(PreAuthorize.class))
            .filter(m -> !LOGISTIQUE_LECTURES.contains(m.getName()))
            .filter(m -> {
                String garde = m.getAnnotation(PreAuthorize.class).value();
                return garde.contains("RESP_CUISINE") || garde.contains("RESP_BAR");
            })
            .map(Method::getName).toList();
        assertThat(ecritures).as("methodes d'ecriture ouvertes a la cuisine ou au bar").isEmpty();
    }
}
