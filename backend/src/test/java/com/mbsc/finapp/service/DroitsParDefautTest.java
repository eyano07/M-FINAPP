package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.RolePermission;
import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.domain.enums.NiveauPermission;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.repository.ModuleConfigRepository;
import com.mbsc.finapp.repository.RolePermissionRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Droits d'origine des roles et leur pose a la creation d'un utilisateur : un
 * role sans aucune ligne dans la grille n'ouvre aucun module, et la page
 * d'accueil d'un role de module renvoyait alors vers elle-meme sans fin.
 */
class DroitsParDefautTest {

    @Test
    void admin_et_directeur_n_ont_pas_de_droits_d_origine() {
        assertThat(DroitsParDefaut.pour(RoleType.ADMIN)).isEmpty();
        assertThat(DroitsParDefaut.pour(RoleType.DIRECTEUR)).isEmpty();
    }

    @Test
    void tous_les_autres_roles_ont_des_droits_d_origine() {
        for (RoleType role : RoleType.values()) {
            if (role == RoleType.ADMIN || role == RoleType.DIRECTEUR) {
                continue;
            }
            assertThat(DroitsParDefaut.pour(role)).as(role.name()).isNotEmpty();
        }
    }

    /** Page d'accueil = un module : sans acces a ce module, la connexion tournerait en boucle. */
    @Test
    void les_roles_dont_l_accueil_est_un_module_y_ont_acces_en_ecriture() {
        assertThat(DroitsParDefaut.pour(RoleType.RESP_RESTAURANT))
            .containsEntry(ModuleMetier.RESTAURANT, NiveauPermission.ECRITURE);
        assertThat(DroitsParDefaut.pour(RoleType.LOGISTIQUE))
            .containsEntry(ModuleMetier.LOGISTIQUE, NiveauPermission.ECRITURE);
        assertThat(DroitsParDefaut.pour(RoleType.GEST_PATRIMOINE))
            .containsEntry(ModuleMetier.PATRIMOINE, NiveauPermission.ECRITURE);
        assertThat(DroitsParDefaut.pour(RoleType.RESP_DRH))
            .containsEntry(ModuleMetier.DRH_PAIE, NiveauPermission.ECRITURE);
    }

    @Test
    void le_conteneur_drh_n_est_jamais_attribue_et_aucun_droit_n_est_vide() {
        for (RoleType role : RoleType.values()) {
            assertThat(DroitsParDefaut.pour(role)).as(role.name()).doesNotContainKey(ModuleMetier.DRH);
            assertThat(DroitsParDefaut.pour(role).values()).as(role.name()).doesNotContain(NiveauPermission.AUCUN);
        }
    }

    /**
     * Total de la grille livree par les migrations V28 a V104 : 49 lignes une fois V29, V30 et V40 appliquees,
     * plus les 4 droits du module BUDGET (V104 : DFIN, DA, DG, COMPTABLE) et les 5 du module RAPPROCHEMENT (V105).
     */
    @Test
    void la_grille_d_origine_reprend_les_58_droits_des_migrations() {
        int total = 0;
        for (RoleType role : RoleType.values()) {
            total += DroitsParDefaut.pour(role).size();
        }
        assertThat(total).isEqualTo(58);
    }

    // ---------------------------------------------------------------------
    // Pose des droits : seulement pour un role qui n'a encore aucune ligne
    // ---------------------------------------------------------------------

    private final RolePermissionRepository repository = mock(RolePermissionRepository.class);
    private final PermissionService service = new PermissionService(
        mock(ModuleConfigRepository.class), repository, mock(CurrentUserProvider.class));

    @Test
    void un_role_sans_aucune_ligne_recoit_ses_droits_d_origine() {
        when(repository.existsByRole(RoleType.RESP_RESTAURANT)).thenReturn(false);

        List<RoleType> appliques = service.appliquerDroitsParDefaut(List.of(RoleType.RESP_RESTAURANT));

        assertThat(appliques).containsExactly(RoleType.RESP_RESTAURANT);
        ArgumentCaptor<RolePermission> pose = ArgumentCaptor.forClass(RolePermission.class);
        verify(repository).save(pose.capture());
        assertThat(pose.getValue().getRole()).isEqualTo(RoleType.RESP_RESTAURANT);
        assertThat(pose.getValue().getModule()).isEqualTo(ModuleMetier.RESTAURANT);
        assertThat(pose.getValue().getNiveau()).isEqualTo(NiveauPermission.ECRITURE);
    }

    @Test
    void un_role_deja_configure_n_est_jamais_modifie() {
        when(repository.existsByRole(RoleType.DA)).thenReturn(true);

        assertThat(service.appliquerDroitsParDefaut(List.of(RoleType.DA))).isEmpty();

        verify(repository, never()).save(any());
    }

    @Test
    void admin_et_un_role_sans_droits_d_origine_sont_ignores() {
        assertThat(service.appliquerDroitsParDefaut(List.of(RoleType.ADMIN, RoleType.DIRECTEUR))).isEmpty();

        verify(repository, never()).save(any());
    }

    @Test
    void plusieurs_roles_et_doublons_chaque_role_traite_une_seule_fois() {
        when(repository.existsByRole(RoleType.LOGISTIQUE)).thenReturn(false);
        when(repository.existsByRole(RoleType.GEST_PATRIMOINE)).thenReturn(false);

        List<RoleType> appliques = service.appliquerDroitsParDefaut(
            List.of(RoleType.LOGISTIQUE, RoleType.GEST_PATRIMOINE, RoleType.LOGISTIQUE));

        assertThat(appliques).containsExactly(RoleType.LOGISTIQUE, RoleType.GEST_PATRIMOINE);
        // LOGISTIQUE : 2 modules, GEST_PATRIMOINE : 2 modules.
        verify(repository, times(4)).save(any(RolePermission.class));
    }
}
