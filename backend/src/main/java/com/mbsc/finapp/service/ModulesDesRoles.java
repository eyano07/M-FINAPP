package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.domain.enums.RoleType;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A quels modules est attache chaque role : quand un module est desactive, le role qui n'existe que pour lui
 * disparait de la gestion des utilisateurs, et les comptes qui n'ont plus que de tels roles sont desactives puis
 * masques (voir {@link SynchronisationComptesModules}).
 *
 * <p>Un role est <b>disponible</b> s'il est libre, ou si AU MOINS UN de ses modules est actif. Les roles de
 * direction et d'administration (ADMIN, DG, DA, DFIN, DIRECTEUR) et le comptable ne dependent d'aucun module : ils
 * ne disparaissent jamais. Banque et Mobile Money n'ont pas de role propre : leur operateur est le caissier, rattache
 * a la Caisse — couper l'un d'eux ne touche donc au caissier que si la Caisse est coupee aussi.</p>
 *
 * <p>Classe pure : la regle se teste sans Spring ; {@code ModulesDesRolesTest} impose que chaque {@link RoleType}
 * soit classe ici, pour qu'un futur role ne passe pas inapercu.</p>
 */
public final class ModulesDesRoles {

    /** Roles sans module : toujours disponibles. */
    private static final Set<RoleType> LIBRES = Collections.unmodifiableSet(EnumSet.of(
        RoleType.ADMIN, RoleType.DG, RoleType.DA, RoleType.DFIN, RoleType.DIRECTEUR, RoleType.COMPTABLE));

    private static final Map<RoleType, Set<ModuleMetier>> PROPRIETAIRES = new EnumMap<>(RoleType.class);

    static {
        lier(RoleType.RESP_RESTAURANT, ModuleMetier.RESTAURANT);
        lier(RoleType.RESP_CUISINE, ModuleMetier.RESTAURANT);
        lier(RoleType.RESP_BAR, ModuleMetier.RESTAURANT);
        // Le conteneur DRH n'est pas cite : un sous-module n'est actif que si son parent l'est (EtatModules).
        lier(RoleType.RESP_DRH, ModuleMetier.DRH_PERSONNEL, ModuleMetier.DRH_PRESENCES, ModuleMetier.DRH_PAIE,
            ModuleMetier.DRH_MISSIONS);
        lier(RoleType.GEST_PATRIMOINE, ModuleMetier.PATRIMOINE);
        lier(RoleType.LOGISTIQUE, ModuleMetier.LOGISTIQUE, ModuleMetier.TRANSPORT);
        // Le caissier opere la Caisse, mais aussi la Banque et le Mobile Money (seul role autorise a y ecrire) :
        // il reste tant que l'une de ces trois fonctions tourne, et en tout cas tant que la Caisse est active.
        lier(RoleType.CAISSIER, ModuleMetier.CAISSE, ModuleMetier.BANQUE, ModuleMetier.MOBILE_MONEY);
    }

    private ModulesDesRoles() {
    }

    private static void lier(RoleType role, ModuleMetier... modules) {
        PROPRIETAIRES.put(role, Collections.unmodifiableSet(EnumSet.copyOf(java.util.List.of(modules))));
    }

    /** Vrai si le role est attache a un ou plusieurs modules (faux pour un role libre). */
    public static boolean estLie(RoleType role) {
        return PROPRIETAIRES.containsKey(role);
    }

    /** Modules propres au role ; vide pour un role libre. */
    public static Set<ModuleMetier> proprietaires(RoleType role) {
        return PROPRIETAIRES.getOrDefault(role, Set.of());
    }

    /** Role classe explicitement comme libre (jamais masque). */
    public static boolean estLibre(RoleType role) {
        return LIBRES.contains(role);
    }

    /** Vrai si le role est libre, ou si au moins un de ses modules est actif. */
    public static boolean estDisponible(RoleType role, Predicate<ModuleMetier> moduleActif) {
        Set<ModuleMetier> modules = proprietaires(role);
        return modules.isEmpty() || modules.stream().anyMatch(moduleActif);
    }

    /**
     * Un compte est <b>bloque</b> quand il a au moins un role et que TOUS ses roles sont lies a des modules coupes.
     * Un seul role libre ou disponible suffit a le garder : un directeur qui cumule un role de module reste actif.
     * Sans aucun role, il n'est jamais bloque (un ensemble vide serait vacuement « tout indisponible »).
     */
    public static boolean estBloque(Collection<RoleType> rolesDuCompte, Predicate<ModuleMetier> moduleActif) {
        if (rolesDuCompte.isEmpty()) {
            return false;
        }
        return rolesDuCompte.stream().noneMatch(role -> estDisponible(role, moduleActif));
    }
}
