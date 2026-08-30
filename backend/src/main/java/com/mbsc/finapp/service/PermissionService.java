package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ModuleConfig;
import com.mbsc.finapp.domain.Role;
import com.mbsc.finapp.domain.RolePermission;
import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.domain.enums.NiveauPermission;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.dto.parametrage.RolePermissionResponse;
import com.mbsc.finapp.repository.ModuleConfigRepository;
import com.mbsc.finapp.repository.RolePermissionRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolution des droits d'acces aux modules metier desactivables, a partir
 * de deux sources : l'etat actif/inactif du module ({@link ModuleConfig})
 * et le niveau associe a chaque role ({@link RolePermission}).
 *
 * <p>ADMIN n'est jamais lu depuis la table : il beneficie d'un acces
 * ECRITURE total, code en dur, pour qu'aucune modification de la grille ne
 * puisse verrouiller un administrateur hors de l'application (y compris
 * hors de la page de configuration qui permettrait de reparer l'erreur).</p>
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final ModuleConfigRepository moduleConfigRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final CurrentUserProvider currentUser;

    /** Les roles de l'utilisateur courant (entite metier, pas les autorites Spring). */
    private List<RoleType> mesRoles() {
        return currentUser.requireUser().getRoles().stream().map(Role::getNom).toList();
    }

    private boolean estAdmin() {
        return currentUser.requirePrincipal().getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    /** Vrai si l'utilisateur courant dispose au moins du niveau requis sur le module (module actif inclus). */
    @Transactional(readOnly = true)
    public boolean autorise(ModuleMetier module, NiveauPermission requis) {
        return niveauEffectif(module).auMoins(requis);
    }

    /** Carte module -> niveau effectif pour l'utilisateur courant (front : navigation, garde de page). */
    @Transactional(readOnly = true)
    public Map<ModuleMetier, NiveauPermission> mesPermissions() {
        Map<ModuleMetier, NiveauPermission> resultat = new EnumMap<>(ModuleMetier.class);
        for (ModuleMetier m : ModuleMetier.values()) {
            resultat.put(m, niveauEffectif(m));
        }
        return resultat;
    }

    private NiveauPermission niveauEffectif(ModuleMetier module) {
        // Un module desactive coupe l'acces pour tout le monde, ADMIN inclus :
        // c'est une action deliberee de l'administrateur, pas une
        // mauvaise config accidentelle a compenser. /admin/modules reste
        // hors de ce filtre (voir ModuleAccessFilter), donc ADMIN garde
        // toujours la main pour reactiver le module.
        ModuleConfig config = moduleConfigRepository.findById(module).orElse(null);
        boolean actif = config == null || config.isActif();
        // Cascade hierarchique (DRH et ses sous-modules) : un sous-module ne
        // peut jamais etre plus permissif que son parent. Le parent lui-meme
        // n'a pas de parentModule, donc cette verification ne boucle pas.
        if (actif && config != null && config.getParentModule() != null) {
            actif = moduleConfigRepository.findById(config.getParentModule())
                .map(ModuleConfig::isActif)
                .orElse(true);
        }
        if (!actif) {
            return NiveauPermission.AUCUN;
        }
        if (estAdmin()) {
            return NiveauPermission.ECRITURE;
        }
        List<RoleType> roles = mesRoles();
        if (roles.isEmpty()) {
            return NiveauPermission.AUCUN;
        }
        return rolePermissionRepository.findByRoleIn(roles).stream()
            .filter(rp -> rp.getModule() == module)
            .map(RolePermission::getNiveau)
            .max(java.util.Comparator.naturalOrder())
            .orElse(NiveauPermission.AUCUN);
    }

    // ---------------------------------------------------------------------
    // Administration de la grille (ecran Permissions)
    // ---------------------------------------------------------------------

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public List<RolePermissionResponse> listerGrille() {
        Map<String, NiveauPermission> existantes = rolePermissionRepository.findAll().stream()
            .collect(java.util.stream.Collectors.toMap(
                rp -> rp.getRole() + "|" + rp.getModule(), RolePermission::getNiveau));

        List<RolePermissionResponse> grille = new java.util.ArrayList<>();
        for (RoleType role : ROLES_CONFIGURABLES) {
            for (ModuleMetier module : ModuleMetier.values()) {
                NiveauPermission niveau = existantes.getOrDefault(role + "|" + module, NiveauPermission.AUCUN);
                grille.add(new RolePermissionResponse(role, module, niveau));
            }
        }
        return grille;
    }

    /** ADMIN est exclu de la grille : acces total fixe, non editable (voir la Javadoc de la classe). */
    private static final List<RoleType> ROLES_CONFIGURABLES = List.of(
        RoleType.DG, RoleType.DA, RoleType.DFIN, RoleType.DIRECTEUR, RoleType.CAISSIER, RoleType.COMPTABLE,
        RoleType.LOGISTIQUE, RoleType.GEST_PATRIMOINE, RoleType.RESP_DRH, RoleType.RESP_RESTAURANT);

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public RolePermissionResponse definir(RoleType role, ModuleMetier module, NiveauPermission niveau) {
        if (role == RoleType.ADMIN) {
            throw new IllegalArgumentException("Le role ADMIN dispose d'un acces complet fixe, non modifiable");
        }
        RolePermission rp = rolePermissionRepository.findByRoleAndModule(role, module)
            .orElseGet(() -> RolePermission.builder().role(role).module(module).build());
        rp.setNiveau(niveau);
        rolePermissionRepository.save(rp);
        return new RolePermissionResponse(role, module, niveau);
    }
}
