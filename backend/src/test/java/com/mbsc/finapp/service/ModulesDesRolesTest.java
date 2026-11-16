package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.domain.enums.RoleType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A quels modules est attache chaque role, et quand un role (ou un compte) disparait avec ses modules.
 */
class ModulesDesRolesTest {

    /** Etat des modules : tous actifs, sauf ceux cites (et, par cascade, les sous-modules DRH si DRH est cite). */
    private static Predicate<ModuleMetier> sauf(ModuleMetier... coupes) {
        Set<ModuleMetier> coupe = EnumSet.noneOf(ModuleMetier.class);
        coupe.addAll(Arrays.asList(coupes));
        if (coupe.contains(ModuleMetier.DRH)) {
            coupe.addAll(List.of(ModuleMetier.DRH_PERSONNEL, ModuleMetier.DRH_PRESENCES, ModuleMetier.DRH_PAIE,
                ModuleMetier.DRH_MISSIONS));
        }
        return module -> !coupe.contains(module);
    }

    /** Garde-fou : un role ajoute a l'enum sans decision ici ne serait ni libre ni lie. */
    @Test
    void chaque_role_est_classe_explicitement_comme_libre_ou_comme_lie() {
        for (RoleType role : RoleType.values()) {
            assertThat(ModulesDesRoles.estLie(role) ^ ModulesDesRoles.estLibre(role))
                .as(role.name() + " doit etre soit libre, soit lie a un module (pas les deux, pas aucun)").isTrue();
        }
    }

    @Test
    void les_roles_de_direction_et_le_comptable_ne_sont_jamais_masques() {
        Predicate<ModuleMetier> toutCoupe = module -> false;
        for (RoleType libre : List.of(RoleType.ADMIN, RoleType.DG, RoleType.DA, RoleType.DFIN, RoleType.DIRECTEUR, RoleType.COMPTABLE)) {
            assertThat(ModulesDesRoles.estLibre(libre)).as(libre.name()).isTrue();
            assertThat(ModulesDesRoles.estDisponible(libre, toutCoupe)).as(libre.name()).isTrue();
        }
    }

    @Test
    void les_trois_roles_du_restaurant_suivent_le_module_restaurant() {
        for (RoleType role : List.of(RoleType.RESP_RESTAURANT, RoleType.RESP_CUISINE, RoleType.RESP_BAR)) {
            assertThat(ModulesDesRoles.proprietaires(role)).as(role.name()).containsExactly(ModuleMetier.RESTAURANT);
            assertThat(ModulesDesRoles.estDisponible(role, sauf())).as(role.name()).isTrue();
            assertThat(ModulesDesRoles.estDisponible(role, sauf(ModuleMetier.RESTAURANT))).as(role.name()).isFalse();
            // Aucun autre module n'y change rien.
            assertThat(ModulesDesRoles.estDisponible(role, sauf(ModuleMetier.LOGISTIQUE, ModuleMetier.CAISSE))).as(role.name()).isTrue();
        }
    }

    @Test
    void le_caissier_reste_quand_la_banque_et_le_mobile_money_sont_coupes_tant_que_la_caisse_est_active() {
        assertThat(ModulesDesRoles.estDisponible(RoleType.CAISSIER, sauf(ModuleMetier.BANQUE))).isTrue();
        assertThat(ModulesDesRoles.estDisponible(RoleType.CAISSIER, sauf(ModuleMetier.MOBILE_MONEY))).isTrue();
        assertThat(ModulesDesRoles.estDisponible(RoleType.CAISSIER, sauf(ModuleMetier.BANQUE, ModuleMetier.MOBILE_MONEY))).isTrue();
    }

    /** Il est le seul operateur de la Banque et du Mobile Money : tant que l'une des trois fonctions tourne, il reste. */
    @Test
    void le_caissier_reste_tant_que_la_caisse_la_banque_ou_le_mobile_money_est_actif() {
        assertThat(ModulesDesRoles.estDisponible(RoleType.CAISSIER, sauf(ModuleMetier.CAISSE))).isTrue();
        assertThat(ModulesDesRoles.estDisponible(RoleType.CAISSIER, sauf(ModuleMetier.CAISSE, ModuleMetier.BANQUE))).isTrue();
        assertThat(ModulesDesRoles.estDisponible(RoleType.CAISSIER,
            sauf(ModuleMetier.CAISSE, ModuleMetier.BANQUE, ModuleMetier.MOBILE_MONEY))).isFalse();
    }

    @Test
    void le_responsable_drh_suit_le_conteneur_drh_et_disparait_avec_tous_ses_sous_modules() {
        assertThat(ModulesDesRoles.estDisponible(RoleType.RESP_DRH, sauf())).isTrue();
        // Un seul sous-module coupe : il lui reste les autres.
        assertThat(ModulesDesRoles.estDisponible(RoleType.RESP_DRH, sauf(ModuleMetier.DRH_PAIE))).isTrue();
        assertThat(ModulesDesRoles.estDisponible(RoleType.RESP_DRH,
            sauf(ModuleMetier.DRH_PAIE, ModuleMetier.DRH_PERSONNEL, ModuleMetier.DRH_PRESENCES))).isTrue();
        assertThat(ModulesDesRoles.estDisponible(RoleType.RESP_DRH,
            sauf(ModuleMetier.DRH_PAIE, ModuleMetier.DRH_PERSONNEL, ModuleMetier.DRH_PRESENCES, ModuleMetier.DRH_MISSIONS))).isFalse();
        // Le conteneur coupe emporte ses quatre sous-modules (cascade de EtatModules, reproduite par le predicat).
        assertThat(ModulesDesRoles.estDisponible(RoleType.RESP_DRH, sauf(ModuleMetier.DRH))).isFalse();
    }

    @Test
    void la_logistique_reste_tant_que_le_stock_ou_le_transport_est_actif() {
        assertThat(ModulesDesRoles.estDisponible(RoleType.LOGISTIQUE, sauf(ModuleMetier.LOGISTIQUE))).isTrue();
        assertThat(ModulesDesRoles.estDisponible(RoleType.LOGISTIQUE, sauf(ModuleMetier.TRANSPORT))).isTrue();
        assertThat(ModulesDesRoles.estDisponible(RoleType.LOGISTIQUE, sauf(ModuleMetier.LOGISTIQUE, ModuleMetier.TRANSPORT))).isFalse();
    }

    @Test
    void le_gestionnaire_du_patrimoine_suit_le_module_patrimoine() {
        assertThat(ModulesDesRoles.estDisponible(RoleType.GEST_PATRIMOINE, sauf())).isTrue();
        assertThat(ModulesDesRoles.estDisponible(RoleType.GEST_PATRIMOINE, sauf(ModuleMetier.PATRIMOINE))).isFalse();
        assertThat(ModulesDesRoles.estDisponible(RoleType.GEST_PATRIMOINE, sauf(ModuleMetier.LOGISTIQUE))).isTrue();
    }

    // ---------------------------------------------------------------------
    // Comptes : bloques seulement si TOUS leurs roles sont indisponibles
    // ---------------------------------------------------------------------

    @Test
    void un_compte_dont_tous_les_roles_sont_coupes_est_bloque() {
        Predicate<ModuleMetier> restaurantCoupe = sauf(ModuleMetier.RESTAURANT);

        assertThat(ModulesDesRoles.estBloque(List.of(RoleType.RESP_BAR), restaurantCoupe)).isTrue();
        assertThat(ModulesDesRoles.estBloque(List.of(RoleType.RESP_RESTAURANT, RoleType.RESP_CUISINE, RoleType.RESP_BAR), restaurantCoupe)).isTrue();
    }

    @Test
    void un_seul_role_libre_ou_disponible_garde_le_compte() {
        Predicate<ModuleMetier> restaurantCoupe = sauf(ModuleMetier.RESTAURANT);

        assertThat(ModulesDesRoles.estBloque(List.of(RoleType.DFIN, RoleType.RESP_BAR), restaurantCoupe)).isFalse();
        assertThat(ModulesDesRoles.estBloque(List.of(RoleType.RESP_BAR, RoleType.CAISSIER), restaurantCoupe)).isFalse();
        // ADMIN + role lie : l'administrateur n'est jamais bloque, y compris dans la tache planifiee des plats perimes.
        assertThat(ModulesDesRoles.estBloque(List.of(RoleType.ADMIN, RoleType.RESP_RESTAURANT), restaurantCoupe)).isFalse();
        assertThat(ModulesDesRoles.estBloque(List.of(RoleType.COMPTABLE), sauf(ModuleMetier.COMPTABILITE))).isFalse();
    }

    @Test
    void un_compte_sans_aucun_role_n_est_jamais_bloque() {
        assertThat(ModulesDesRoles.estBloque(List.of(), module -> false)).isFalse();
    }

    @Test
    void tous_les_modules_coupes_ne_bloquent_que_les_comptes_qui_n_ont_que_des_roles_lies() {
        Predicate<ModuleMetier> toutCoupe = module -> false;

        assertThat(ModulesDesRoles.estBloque(List.of(RoleType.CAISSIER), toutCoupe)).isTrue();
        assertThat(ModulesDesRoles.estBloque(List.of(RoleType.LOGISTIQUE, RoleType.GEST_PATRIMOINE), toutCoupe)).isTrue();
        for (RoleType libre : List.of(RoleType.ADMIN, RoleType.DG, RoleType.DA, RoleType.DFIN, RoleType.DIRECTEUR, RoleType.COMPTABLE)) {
            assertThat(ModulesDesRoles.estBloque(List.of(libre), toutCoupe)).as(libre.name()).isFalse();
        }
    }
}
