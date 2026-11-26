package com.mbsc.finapp.service;

import com.mbsc.finapp.audit.AuditService;
import com.mbsc.finapp.domain.ModuleConfig;
import com.mbsc.finapp.domain.Role;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.repository.ModuleConfigRepository;
import com.mbsc.finapp.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cascade « module desactive -> comptes » : les comptes dont tous les roles sont lies a des modules coupes sont
 * desactives (jetons revoques, drapeau pose), puis reactives avec le module ; rien d'autre n'est touche.
 */
class SynchronisationComptesModulesTest {

    private ModuleConfigRepository modules;
    private UserRepository utilisateurs;
    private AuditService audit;
    private SynchronisationComptesModules service;
    private List<User> comptes;

    @BeforeEach
    void preparer() {
        modules = mock(ModuleConfigRepository.class);
        utilisateurs = mock(UserRepository.class);
        audit = mock(AuditService.class);
        service = new SynchronisationComptesModules(modules, utilisateurs, audit);
        comptes = new ArrayList<>();
        when(utilisateurs.findAll()).thenReturn(comptes);
        coupes();
    }

    /** Etat des modules : tous actifs sauf ceux cites (le conteneur DRH coupe emporte ses sous-modules par cascade). */
    private void coupes(ModuleMetier... coupes) {
        Set<ModuleMetier> coupe = new HashSet<>(List.of(coupes));
        List<ModuleConfig> lignes = new ArrayList<>();
        for (ModuleMetier m : ModuleMetier.values()) {
            ModuleMetier parent = switch (m) {
                case DRH_PERSONNEL, DRH_PRESENCES, DRH_PAIE, DRH_MISSIONS -> ModuleMetier.DRH;
                case BUDGET, RAPPROCHEMENT -> ModuleMetier.COMPTABILITE;
                case FACTURATION_NORMALISEE -> ModuleMetier.VENTES;
                default -> null;
            };
            lignes.add(ModuleConfig.builder().module(m).actif(!coupe.contains(m)).parentModule(parent).build());
        }
        when(modules.findAllPourMiseAJour()).thenReturn(lignes);
        when(modules.findAll()).thenReturn(lignes);
    }

    private User compte(long id, boolean actif, boolean desactiveParModule, RoleType... roles) {
        Set<Role> ensemble = new HashSet<>();
        for (RoleType r : roles) {
            ensemble.add(Role.builder().nom(r).build());
        }
        User u = User.builder().id(id).email("compte" + id).prenom("Prenom" + id).nom("Nom" + id)
            .actif(actif).desactiveParModule(desactiveParModule).roles(ensemble).build();
        comptes.add(u);
        return u;
    }

    // ---------------------------------------------------------------------
    // Desactivation
    // ---------------------------------------------------------------------

    @Test
    void un_compte_dont_le_seul_role_est_lie_a_un_module_coupe_est_desactive_et_ses_jetons_revoques() {
        coupes(ModuleMetier.RESTAURANT);
        User bar = compte(1, true, false, RoleType.RESP_BAR);
        int versionAvant = bar.getVersionJetons();

        SynchronisationComptesModules.Resultat resultat = service.reconcilier();

        assertThat(bar.isActif()).isFalse();
        assertThat(bar.isDesactiveParModule()).isTrue();
        assertThat(bar.getVersionJetons()).isEqualTo(versionAvant + 1);
        assertThat(resultat.desactives()).containsExactly("Prenom1 Nom1 (compte1)");
        assertThat(resultat.reactives()).isEmpty();
        verify(utilisateurs).saveAll(List.of(bar));
    }

    @Test
    void les_trois_roles_du_restaurant_sont_coupes_ensemble() {
        coupes(ModuleMetier.RESTAURANT);
        compte(1, true, false, RoleType.RESP_RESTAURANT);
        compte(2, true, false, RoleType.RESP_CUISINE);
        compte(3, true, false, RoleType.RESP_BAR);
        compte(4, true, false, RoleType.DFIN);

        SynchronisationComptesModules.Resultat resultat = service.reconcilier();

        assertThat(resultat.desactives()).hasSize(3);
        assertThat(comptes.get(3).isActif()).as("le DFIN n'est pas touche").isTrue();
        assertThat(comptes.get(3).isDesactiveParModule()).isFalse();
    }

    @Test
    void un_compte_multi_roles_reste_actif_tant_qu_un_de_ses_roles_est_disponible() {
        coupes(ModuleMetier.RESTAURANT);
        User directeur = compte(1, true, false, RoleType.DFIN, RoleType.RESP_BAR);
        User caissierBar = compte(2, true, false, RoleType.CAISSIER, RoleType.RESP_BAR);

        SynchronisationComptesModules.Resultat resultat = service.reconcilier();

        assertThat(resultat.vide()).isTrue();
        assertThat(directeur.isActif()).isTrue();
        assertThat(caissierBar.isActif()).isTrue();
        verify(utilisateurs, never()).saveAll(any());
    }

    @Test
    void l_administrateur_n_est_jamais_desactive_meme_avec_un_role_de_module() {
        coupes(ModuleMetier.RESTAURANT);
        User admin = compte(1, true, false, RoleType.ADMIN, RoleType.RESP_RESTAURANT);

        assertThat(service.reconcilier().vide()).isTrue();
        assertThat(admin.isActif()).isTrue();
    }

    @Test
    void un_compte_sans_aucun_role_n_est_jamais_touche() {
        coupes(ModuleMetier.RESTAURANT, ModuleMetier.CAISSE);
        User sansRole = compte(1, true, false);

        assertThat(service.reconcilier().vide()).isTrue();
        assertThat(sansRole.isActif()).isTrue();
    }

    @Test
    void le_caissier_n_est_touche_ni_par_la_banque_ni_par_le_mobile_money_tant_que_la_caisse_est_active() {
        coupes(ModuleMetier.BANQUE, ModuleMetier.MOBILE_MONEY);
        User caissier = compte(1, true, false, RoleType.CAISSIER);

        assertThat(service.reconcilier().vide()).isTrue();
        assertThat(caissier.isActif()).isTrue();

        coupes(ModuleMetier.CAISSE, ModuleMetier.BANQUE, ModuleMetier.MOBILE_MONEY);
        assertThat(service.reconcilier().desactives()).hasSize(1);
        assertThat(caissier.isActif()).isFalse();
    }

    @Test
    void le_responsable_drh_disparait_avec_le_conteneur_drh() {
        coupes(ModuleMetier.DRH);
        User drh = compte(1, true, false, RoleType.RESP_DRH);
        User drhEtPatrimoine = compte(2, true, false, RoleType.RESP_DRH, RoleType.GEST_PATRIMOINE);

        SynchronisationComptesModules.Resultat resultat = service.reconcilier();

        assertThat(drh.isActif()).isFalse();
        assertThat(drhEtPatrimoine.isActif()).isTrue();
        assertThat(resultat.desactives()).hasSize(1);

        // Puis le patrimoine : le second compte n'a plus rien de disponible.
        coupes(ModuleMetier.DRH, ModuleMetier.PATRIMOINE);
        assertThat(service.reconcilier().desactives()).hasSize(1);
        assertThat(drhEtPatrimoine.isActif()).isFalse();
    }

    @Test
    void le_responsable_drh_disparait_aussi_quand_les_lignes_des_sous_modules_n_ont_plus_de_parent() {
        // Base purgee puis recreee par l'ecran : DRH coupe, sous-modules actifs mais sans lien de parente.
        when(modules.findAllPourMiseAJour()).thenReturn(List.of(
            ModuleConfig.builder().module(ModuleMetier.DRH).actif(false).build(),
            ModuleConfig.builder().module(ModuleMetier.DRH_PERSONNEL).actif(true).build(),
            ModuleConfig.builder().module(ModuleMetier.DRH_PRESENCES).actif(true).build(),
            ModuleConfig.builder().module(ModuleMetier.DRH_PAIE).actif(true).build(),
            ModuleConfig.builder().module(ModuleMetier.DRH_MISSIONS).actif(true).build()));
        User drh = compte(1, true, false, RoleType.RESP_DRH);

        assertThat(service.reconcilier().desactives()).hasSize(1);
        assertThat(drh.isActif()).isFalse();
    }

    // ---------------------------------------------------------------------
    // Reactivation
    // ---------------------------------------------------------------------

    @Test
    void la_reactivation_du_module_remet_en_service_les_comptes_coupes_par_la_cascade() {
        coupes(ModuleMetier.RESTAURANT);
        User bar = compte(1, true, false, RoleType.RESP_BAR);
        service.reconcilier();
        int versionApresCoupure = bar.getVersionJetons();
        assertThat(bar.isActif()).isFalse();

        coupes();
        SynchronisationComptesModules.Resultat resultat = service.reconcilier();

        assertThat(bar.isActif()).isTrue();
        assertThat(bar.isDesactiveParModule()).isFalse();
        // Les jetons d'avant la coupure restent invalides : la reactivation ne les ressuscite pas.
        assertThat(bar.getVersionJetons()).isEqualTo(versionApresCoupure);
        assertThat(resultat.reactives()).containsExactly("Prenom1 Nom1 (compte1)");
    }

    @Test
    void une_desactivation_manuelle_n_est_jamais_annulee_ni_touchee() {
        // Coupe a la main avant la bascule du module : pas de drapeau.
        User manuel = compte(1, false, false, RoleType.RESP_BAR);
        int version = manuel.getVersionJetons();

        coupes(ModuleMetier.RESTAURANT);
        assertThat(service.reconcilier().vide()).isTrue();
        assertThat(manuel.isActif()).isFalse();
        assertThat(manuel.getVersionJetons()).isEqualTo(version);

        coupes();
        assertThat(service.reconcilier().vide()).isTrue();
        assertThat(manuel.isActif()).as("jamais reactive automatiquement").isFalse();
    }

    @Test
    void un_compte_non_bloque_et_sans_drapeau_n_est_jamais_reactive() {
        User manuel = compte(1, false, false, RoleType.DFIN);

        assertThat(service.reconcilier().vide()).isTrue();
        assertThat(manuel.isActif()).isFalse();
    }

    // ---------------------------------------------------------------------
    // Idempotence, verrou, audit
    // ---------------------------------------------------------------------

    @Test
    void une_seconde_passe_sans_changement_ne_fait_rien() {
        coupes(ModuleMetier.RESTAURANT);
        User bar = compte(1, true, false, RoleType.RESP_BAR);

        service.reconcilier();
        int version = bar.getVersionJetons();
        SynchronisationComptesModules.Resultat seconde = service.reconcilier();

        assertThat(seconde.vide()).isTrue();
        assertThat(bar.getVersionJetons()).as("jetons revoques une seule fois").isEqualTo(version);
    }

    @Test
    void les_lignes_des_modules_sont_verrouillees_avant_toute_lecture_des_comptes() {
        coupes(ModuleMetier.RESTAURANT);
        compte(1, true, false, RoleType.RESP_BAR);

        service.reconcilier();

        InOrder ordre = inOrder(modules, utilisateurs);
        ordre.verify(modules).findAllPourMiseAJour();
        ordre.verify(utilisateurs).findAll();
    }

    @Test
    void chaque_sens_est_trace_dans_le_journal_d_audit_et_rien_n_est_trace_sans_changement() {
        coupes(ModuleMetier.RESTAURANT);
        compte(1, true, false, RoleType.RESP_BAR);

        service.reconcilier();

        ArgumentCaptor<Map<String, Object>> ligne = ArgumentCaptor.forClass(Map.class);
        verify(audit).enregistrer(ligne.capture());
        assertThat(ligne.getValue()).containsEntry("type", "CASCADE_MODULES").containsEntry("operation", "DESACTIVES_PAR_MODULE");
        assertThat(String.valueOf(ligne.getValue().get("detail"))).contains("1 compte(s)").contains("compte1");

        // Une seconde passe identique n'ecrit rien de plus.
        service.reconcilier();
        verify(audit, org.mockito.Mockito.times(1)).enregistrer(anyMap());
    }

    // ---------------------------------------------------------------------
    // Simulation : l'apercu avant de couper un module
    // ---------------------------------------------------------------------

    @Test
    void la_simulation_dit_ce_qui_serait_coupe_sans_rien_ecrire() {
        User cuisine = compte(1, true, false, RoleType.RESP_CUISINE);
        User directeur = compte(2, true, false, RoleType.DFIN, RoleType.RESP_BAR);

        SynchronisationComptesModules.Impact impact = service.simuler(ModuleMetier.RESTAURANT, false);

        assertThat(impact.roles()).containsExactly(RoleType.RESP_RESTAURANT, RoleType.RESP_CUISINE, RoleType.RESP_BAR);
        assertThat(impact.comptesADesactiver()).containsExactly("Prenom1 Nom1 (compte1)");
        assertThat(impact.comptesAReactiver()).isEmpty();
        // Rien n'a bouge.
        assertThat(cuisine.isActif()).isTrue();
        assertThat(cuisine.isDesactiveParModule()).isFalse();
        assertThat(directeur.isActif()).isTrue();
        verify(utilisateurs, never()).saveAll(any());
        verify(modules, never()).findAllPourMiseAJour();
        verify(audit, never()).enregistrer(anyMap());
    }

    @Test
    void la_simulation_d_un_module_sans_role_propre_ne_touche_personne() {
        compte(1, true, false, RoleType.CAISSIER);
        compte(2, true, false, RoleType.RESP_BAR);

        SynchronisationComptesModules.Impact impact = service.simuler(ModuleMetier.COMPTABILITE, false);

        assertThat(impact.roles()).isEmpty();
        assertThat(impact.comptesADesactiver()).isEmpty();
    }

    @Test
    void la_simulation_d_une_reactivation_annonce_les_comptes_qui_reviendraient() {
        coupes(ModuleMetier.RESTAURANT);
        compte(1, true, false, RoleType.RESP_BAR);
        service.reconcilier();

        coupes(ModuleMetier.RESTAURANT);
        SynchronisationComptesModules.Impact impact = service.simuler(ModuleMetier.RESTAURANT, true);

        assertThat(impact.comptesAReactiver()).containsExactly("Prenom1 Nom1 (compte1)");
        assertThat(impact.comptesADesactiver()).isEmpty();
    }
}
