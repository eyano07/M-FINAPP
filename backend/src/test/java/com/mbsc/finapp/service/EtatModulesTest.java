package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ModuleConfig;
import com.mbsc.finapp.domain.Role;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.domain.enums.NiveauPermission;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.repository.ModuleConfigRepository;
import com.mbsc.finapp.repository.RolePermissionRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import com.mbsc.finapp.security.UserPrincipal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.mbsc.finapp.service.instance.InstanceService;

/**
 * L'etat effectif d'un module (lui-meme ET son parent actifs) existe trois fois dans le code : ici, dans
 * {@code ModuleConfigService.estActif} et dans {@code PermissionService.niveauEffectif}. Ce test garantit
 * qu'elles donnent le meme resultat, pour toutes les combinaisons de modules coupes parmi ceux qui comptent.
 */
class EtatModulesTest {

    /** Les liens parent -> enfant livres par les migrations (V41 DRH, V104 BUDGET, V105 RAPPROCHEMENT, V113 e-MCF). */
    private static final Map<ModuleMetier, ModuleMetier> PARENTS = Map.of(
        ModuleMetier.DRH_PERSONNEL, ModuleMetier.DRH,
        ModuleMetier.DRH_PRESENCES, ModuleMetier.DRH,
        ModuleMetier.DRH_PAIE, ModuleMetier.DRH,
        ModuleMetier.DRH_MISSIONS, ModuleMetier.DRH,
        ModuleMetier.BUDGET, ModuleMetier.COMPTABILITE,
        ModuleMetier.RAPPROCHEMENT, ModuleMetier.COMPTABILITE,
        ModuleMetier.FACTURATION_NORMALISEE, ModuleMetier.VENTES);

    /** Les modules dont on fait varier l'etat : un parent, un de ses enfants, un module autonome, et un parent isole. */
    private static final List<ModuleMetier> VARIABLES = List.of(
        ModuleMetier.DRH, ModuleMetier.DRH_PAIE, ModuleMetier.COMPTABILITE, ModuleMetier.BUDGET,
        ModuleMetier.VENTES, ModuleMetier.FACTURATION_NORMALISEE, ModuleMetier.RESTAURANT, ModuleMetier.CAISSE);

    private static List<ModuleConfig> lignes(int masqueCoupes) {
        return lignes(masqueCoupes, true);
    }

    /**
     * Les lignes de {@code modules_config}. Sans parents : comme dans une base dont la table a ete purgee puis
     * recreee ligne a ligne par les bascules de l'ecran (l'etat de la base locale de l'utilisateur le 15/10/2026) —
     * la hierarchie du code ({@link ModuleMetier#parent()}) doit alors suffire.
     */
    private static List<ModuleConfig> lignes(int masqueCoupes, boolean avecParents) {
        List<ModuleConfig> lignes = new ArrayList<>();
        for (ModuleMetier module : ModuleMetier.values()) {
            int indice = VARIABLES.indexOf(module);
            boolean actif = indice < 0 || (masqueCoupes & (1 << indice)) == 0;
            lignes.add(ModuleConfig.builder().module(module).actif(actif)
                .parentModule(avecParents ? PARENTS.get(module) : null).build());
        }
        return lignes;
    }

    @Test
    void l_etat_pur_est_equivalent_a_ModuleConfigService_estActif_pour_toutes_les_combinaisons() {
        ModuleConfigRepository repository = mock(ModuleConfigRepository.class);
        ModuleConfigService service = new ModuleConfigService(repository, mock(SynchronisationComptesModules.class));
        Map<ModuleMetier, ModuleConfig> courant = new EnumMap<>(ModuleMetier.class);
        when(repository.findById(any())).thenAnswer(i -> Optional.ofNullable(courant.get((ModuleMetier) i.getArgument(0))));

        for (boolean avecParents : new boolean[] {true, false}) {
            for (int masque = 0; masque < (1 << VARIABLES.size()); masque++) {
                List<ModuleConfig> lignes = lignes(masque, avecParents);
                courant.clear();
                lignes.forEach(l -> courant.put(l.getModule(), l));
                EtatModules etat = EtatModules.de(lignes);
                for (ModuleMetier module : ModuleMetier.values()) {
                    assertThat(etat.estActif(module)).as("module=%s masque=%d parents=%s", module, masque, avecParents)
                        .isEqualTo(service.estActif(module));
                }
            }
        }
    }

    @Test
    void l_etat_pur_est_equivalent_a_la_resolution_de_PermissionService_pour_un_administrateur() {
        ModuleConfigRepository repository = mock(ModuleConfigRepository.class);
        CurrentUserProvider utilisateur = mock(CurrentUserProvider.class);
        User admin = User.builder().id(1L).roles(Set.of(Role.builder().nom(RoleType.ADMIN).build())).build();
        when(utilisateur.requirePrincipal()).thenReturn(new UserPrincipal(admin));
        when(utilisateur.requireUser()).thenReturn(admin);
        PermissionService permissions = new PermissionService(repository, mock(RolePermissionRepository.class), utilisateur, mock(InstanceService.class));
        Map<ModuleMetier, ModuleConfig> courant = new EnumMap<>(ModuleMetier.class);
        when(repository.findById(any())).thenAnswer(i -> Optional.ofNullable(courant.get((ModuleMetier) i.getArgument(0))));

        for (boolean avecParents : new boolean[] {true, false}) {
            for (int masque = 0; masque < (1 << VARIABLES.size()); masque++) {
                List<ModuleConfig> lignes = lignes(masque, avecParents);
                courant.clear();
                lignes.forEach(l -> courant.put(l.getModule(), l));
                EtatModules etat = EtatModules.de(lignes);
                Map<ModuleMetier, NiveauPermission> niveaux = permissions.mesPermissions();
                for (ModuleMetier module : ModuleMetier.values()) {
                    // L'administrateur a l'ecriture sur tout module actif, rien sur un module coupe.
                    assertThat(etat.estActif(module)).as("module=%s masque=%d parents=%s", module, masque, avecParents)
                        .isEqualTo(niveaux.get(module) == NiveauPermission.ECRITURE);
                }
            }
        }
    }

    /** La hierarchie du code reproduit exactement celle des migrations : les memes sept liens, rien de plus. */
    @Test
    void la_hierarchie_du_code_est_celle_des_migrations() {
        for (ModuleMetier module : ModuleMetier.values()) {
            assertThat(module.parent()).as(module.name()).isEqualTo(PARENTS.get(module));
        }
        // Un parent n'a pas de parent : la cascade reste a un seul niveau.
        for (ModuleMetier parent : PARENTS.values()) {
            assertThat(parent.parent()).as(parent.name()).isNull();
        }
    }

    @Test
    void couper_le_parent_coupe_ses_sous_modules_meme_quand_leurs_lignes_n_ont_plus_de_parent() {
        // Ligne DRH coupee, lignes des sous-modules actives mais sans lien (base purgee, lignes recreees par l'ecran).
        EtatModules etat = EtatModules.de(List.of(
            ModuleConfig.builder().module(ModuleMetier.DRH).actif(false).build(),
            ModuleConfig.builder().module(ModuleMetier.DRH_PAIE).actif(true).build(),
            ModuleConfig.builder().module(ModuleMetier.FACTURATION_NORMALISEE).actif(true).build()));

        assertThat(etat.estActif(ModuleMetier.DRH_PAIE)).isFalse();
        assertThat(etat.estActif(ModuleMetier.DRH_MISSIONS)).as("sans aucune ligne non plus").isFalse();
        // Et le meme repli vaut pour un sous-module sans ligne dont le parent est coupe.
        EtatModules comptaCoupee = EtatModules.de(List.of(
            ModuleConfig.builder().module(ModuleMetier.COMPTABILITE).actif(false).build()));
        assertThat(comptaCoupee.estActif(ModuleMetier.BUDGET)).isFalse();
        assertThat(comptaCoupee.estActif(ModuleMetier.RAPPROCHEMENT)).isFalse();
        assertThat(comptaCoupee.estActif(ModuleMetier.VENTES)).isTrue();
    }

    @Test
    void la_liste_des_modules_rend_leur_parent_meme_quand_la_ligne_l_a_perdu() {
        ModuleConfigRepository repository = mock(ModuleConfigRepository.class);
        when(repository.findAll()).thenReturn(List.of(
            ModuleConfig.builder().module(ModuleMetier.DRH_PAIE).actif(true).build()));
        ModuleConfigService service = new ModuleConfigService(repository, mock(SynchronisationComptesModules.class));

        assertThat(service.lister()).filteredOn(m -> m.module() == ModuleMetier.DRH_PAIE || m.module() == ModuleMetier.BUDGET)
            .extracting(m -> m.module() + "<" + m.parentModule())
            .containsExactlyInAnyOrder("DRH_PAIE<DRH", "BUDGET<COMPTABILITE");
    }

    @Test
    void une_ligne_absente_vaut_actif_et_sans_parent() {
        EtatModules vide = EtatModules.de(List.of());
        for (ModuleMetier module : ModuleMetier.values()) {
            assertThat(vide.estActif(module)).isTrue();
        }
        // Un parent sans ligne ne coupe pas son enfant.
        EtatModules enfantSeul = EtatModules.de(List.of(
            ModuleConfig.builder().module(ModuleMetier.BUDGET).actif(true).parentModule(ModuleMetier.COMPTABILITE).build()));
        assertThat(enfantSeul.estActif(ModuleMetier.BUDGET)).isTrue();
    }

    @Test
    void couper_le_parent_coupe_ses_enfants_et_les_couper_ne_touche_pas_le_parent() {
        EtatModules etat = EtatModules.de(lignes(0));

        EtatModules drhCoupe = etat.avec(ModuleMetier.DRH, false);
        assertThat(drhCoupe.estActif(ModuleMetier.DRH)).isFalse();
        assertThat(drhCoupe.estActif(ModuleMetier.DRH_PAIE)).isFalse();
        assertThat(drhCoupe.estActif(ModuleMetier.DRH_MISSIONS)).isFalse();
        assertThat(drhCoupe.estActif(ModuleMetier.RESTAURANT)).isTrue();

        EtatModules paieCoupee = etat.avec(ModuleMetier.DRH_PAIE, false);
        assertThat(paieCoupee.estActif(ModuleMetier.DRH_PAIE)).isFalse();
        assertThat(paieCoupee.estActif(ModuleMetier.DRH)).isTrue();
        assertThat(paieCoupee.estActif(ModuleMetier.DRH_PERSONNEL)).isTrue();
    }

    @Test
    void la_simulation_ne_modifie_jamais_l_etat_d_origine() {
        EtatModules origine = EtatModules.de(lignes(0));

        EtatModules simule = origine.avec(ModuleMetier.RESTAURANT, false);

        assertThat(simule.estActif(ModuleMetier.RESTAURANT)).isFalse();
        assertThat(origine.estActif(ModuleMetier.RESTAURANT)).isTrue();
        // Et on peut aussi simuler la reactivation d'un module absent des lignes.
        assertThat(EtatModules.de(List.of()).avec(ModuleMetier.TRANSPORT, false).estActif(ModuleMetier.TRANSPORT)).isFalse();
    }
}
