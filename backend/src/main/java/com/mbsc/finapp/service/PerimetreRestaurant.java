package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.enums.TypeArticle;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Ce que chaque role du restaurant a le droit de voir : la <b>cuisine</b> (plats et provisions, les matieres
 * premieres de la cuisine) ou le <b>bar</b> (boissons). Le module RESTAURANT est plat — la grille de permissions
 * ne sait pas separer les deux —, et plat, boisson et provision ne se distinguent que par {@code articles.type} :
 * la separation se fait donc ici, dans les services, sur le type des articles, jamais seulement a l'ecran.
 *
 * <p>Regle : l'appelant est <b>restreint</b> s'il porte un role de domaine (RESP_RESTAURANT, RESP_CUISINE ou
 * RESP_BAR) et AUCUN role « complet » du contexte considere ; il voit alors l'union des domaines de ses roles.
 * Sinon il voit tout. Les roles s'additionnent, comme partout ailleurs dans l'application : un responsable bar qui
 * est aussi DFIN voit toute la carte. Un appelant sans contexte de securite (tache planifiee) n'est pas restreint.</p>
 *
 * <p>Deux contextes, deux listes de roles complets : le restaurant ({@link #restaurant}) et le menu Logistique
 * general ({@link #logistique}), ou le responsable restaurant n'a aucun droit propre et compte donc comme un role
 * de domaine a trois types — il n'y accede que cumule avec un role qui y a acces.</p>
 *
 * <p>Classe pure (aucun etat, aucun bean) : toute la regle se teste sans Spring.</p>
 */
public final class PerimetreRestaurant {

    /** Cuisine : les plats et les provisions (matieres premieres). */
    public static final Set<TypeArticle> CUISINE =
        Collections.unmodifiableSet(EnumSet.of(TypeArticle.PLAT, TypeArticle.PROVISION));
    /** Bar : les boissons. */
    public static final Set<TypeArticle> BAR = Collections.unmodifiableSet(EnumSet.of(TypeArticle.BOISSON));
    /** Tout le restaurant : cuisine et bar. */
    public static final Set<TypeArticle> COMPLET =
        Collections.unmodifiableSet(EnumSet.of(TypeArticle.PLAT, TypeArticle.PROVISION, TypeArticle.BOISSON));

    /** Types vus par chaque role de domaine. */
    private static final Map<String, Set<TypeArticle>> TYPES_PAR_ROLE = Map.of(
        "ROLE_RESP_RESTAURANT", COMPLET,
        "ROLE_RESP_CUISINE", CUISINE,
        "ROLE_RESP_BAR", BAR);

    /** Types d'articles qu'un role peut acheter par note de frais (le responsable global : les deux). */
    private static final Map<String, Set<TypeArticle>> ACHATS_PAR_ROLE = Map.of(
        "ROLE_RESP_RESTAURANT", Collections.unmodifiableSet(EnumSet.of(TypeArticle.BOISSON, TypeArticle.PROVISION)),
        "ROLE_RESP_CUISINE", Collections.unmodifiableSet(EnumSet.of(TypeArticle.PROVISION)),
        "ROLE_RESP_BAR", Collections.unmodifiableSet(EnumSet.of(TypeArticle.BOISSON)));

    /** Roles qui voient tout le restaurant : ceux que RestaurantService.LECTURE admet, sans les deux roles de domaine. */
    public static final Set<String> COMPLETS_RESTAURANT = Set.of(
        "ROLE_RESP_RESTAURANT", "ROLE_DFIN", "ROLE_DG", "ROLE_DA", "ROLE_COMPTABLE", "ROLE_CAISSIER", "ROLE_ADMIN");

    /** Roles qui voient tout le menu Logistique : ceux que StockService admet en lecture, sans les deux roles de domaine. */
    public static final Set<String> COMPLETS_LOGISTIQUE = Set.of(
        "ROLE_LOGISTIQUE", "ROLE_CAISSIER", "ROLE_COMPTABLE", "ROLE_GEST_PATRIMOINE", "ROLE_DFIN", "ROLE_DA",
        "ROLE_DG", "ROLE_ADMIN");

    private PerimetreRestaurant() {
    }

    /** Resultat : tout voir, ou seulement certains types d'articles. */
    public record Perimetre(boolean restreint, Set<TypeArticle> types) {

        public static final Perimetre SANS_RESTRICTION = new Perimetre(false, Set.of());

        /** Vrai si un article de ce type est visible. */
        public boolean autorise(TypeArticle type) {
            return !restreint || (type != null && types.contains(type));
        }

        /** Ne garde que les elements dont le type d'article est visible. */
        public <T> List<T> filtrer(Collection<T> elements, Function<T, TypeArticle> typeDe) {
            if (!restreint) {
                return List.copyOf(elements);
            }
            return elements.stream().filter(e -> autorise(typeDe.apply(e))).collect(Collectors.toList());
        }
    }

    /** Perimetre d'un appelant dans le restaurant (autorites Spring : « ROLE_DFIN »...). */
    public static Perimetre restaurant(Collection<String> autorites) {
        return pour(autorites, COMPLETS_RESTAURANT);
    }

    /** Perimetre d'un appelant dans le menu Logistique general. */
    public static Perimetre logistique(Collection<String> autorites) {
        return pour(autorites, COMPLETS_LOGISTIQUE);
    }

    /** Perimetre de l'appelant courant dans le restaurant (sans contexte de securite : pas de restriction). */
    public static Perimetre restaurantCourant() {
        return restaurant(autoritesCourantes());
    }

    /** Perimetre de l'appelant courant dans le menu Logistique general. */
    public static Perimetre logistiqueCourant() {
        return logistique(autoritesCourantes());
    }

    static Perimetre pour(Collection<String> autorites, Set<String> rolesComplets) {
        if (autorites == null) {
            return Perimetre.SANS_RESTRICTION;
        }
        Set<String> portes = new HashSet<>(autorites);
        Set<TypeArticle> types = EnumSet.noneOf(TypeArticle.class);
        boolean roleDeDomaine = false;
        for (Map.Entry<String, Set<TypeArticle>> role : TYPES_PAR_ROLE.entrySet()) {
            if (portes.contains(role.getKey())) {
                roleDeDomaine = true;
                types.addAll(role.getValue());
            }
        }
        if (!roleDeDomaine) {
            return Perimetre.SANS_RESTRICTION;
        }
        for (String autorite : portes) {
            if (rolesComplets.contains(autorite)) {
                return Perimetre.SANS_RESTRICTION;
            }
        }
        return new Perimetre(true, Collections.unmodifiableSet(types));
    }

    /**
     * Types d'articles qu'un utilisateur dont les roles sont ceux du restaurant peut acheter par note de frais :
     * le responsable global les deux, le responsable cuisine les provisions, le responsable bar les boissons
     * (union si plusieurs).
     */
    public static Set<TypeArticle> typesAchetables(Collection<String> autorites) {
        Set<TypeArticle> types = EnumSet.noneOf(TypeArticle.class);
        if (autorites != null) {
            for (Map.Entry<String, Set<TypeArticle>> role : ACHATS_PAR_ROLE.entrySet()) {
                if (autorites.contains(role.getKey())) {
                    types.addAll(role.getValue());
                }
            }
        }
        return types;
    }

    /** « boissons ou de provisions », « provisions » ou « boissons » : la suite de « une note d'achat de ». */
    public static String libelleAchats(Set<TypeArticle> types) {
        boolean boissons = types.contains(TypeArticle.BOISSON);
        boolean provisions = types.contains(TypeArticle.PROVISION);
        if (boissons && !provisions) {
            return "boissons";
        }
        if (provisions && !boissons) {
            return "provisions";
        }
        return "boissons ou de provisions";
    }

    /** « BOISSON ou PROVISION », « PROVISION » ou « BOISSON » : les types d'article d'une ligne d'achat. */
    public static String libelleTypes(Set<TypeArticle> types) {
        boolean boissons = types.contains(TypeArticle.BOISSON);
        boolean provisions = types.contains(TypeArticle.PROVISION);
        if (boissons && !provisions) {
            return "BOISSON";
        }
        if (provisions && !boissons) {
            return "PROVISION";
        }
        return "BOISSON ou PROVISION";
    }

    /** Autorites de l'appelant courant, ou {@code null} sans contexte de securite (tache planifiee, test). */
    public static Set<String> autoritesCourantes() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }
}
