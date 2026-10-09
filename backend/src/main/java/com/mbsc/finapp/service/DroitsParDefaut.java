package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.domain.enums.NiveauPermission;
import com.mbsc.finapp.domain.enums.RoleType;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import static com.mbsc.finapp.domain.enums.ModuleMetier.BANQUE;
import static com.mbsc.finapp.domain.enums.ModuleMetier.CAISSE;
import static com.mbsc.finapp.domain.enums.ModuleMetier.COMPTABILITE;
import static com.mbsc.finapp.domain.enums.ModuleMetier.DRH_MISSIONS;
import static com.mbsc.finapp.domain.enums.ModuleMetier.DRH_PAIE;
import static com.mbsc.finapp.domain.enums.ModuleMetier.DRH_PERSONNEL;
import static com.mbsc.finapp.domain.enums.ModuleMetier.DRH_PRESENCES;
import static com.mbsc.finapp.domain.enums.ModuleMetier.MOBILE_MONEY;
import static com.mbsc.finapp.domain.enums.ModuleMetier.PATRIMOINE;
import static com.mbsc.finapp.domain.enums.ModuleMetier.RESTAURANT;
import static com.mbsc.finapp.domain.enums.ModuleMetier.TRANSPORT;
import static com.mbsc.finapp.domain.enums.ModuleMetier.VENTES;

/**
 * Droits d'origine de chaque role : la grille livree par les migrations V28 a
 * V84, telle qu'elle est avant toute modification par l'administrateur.
 *
 * <p>Sert de repli a {@link PermissionService#appliquerDroitsParDefaut} quand
 * un role n'a aucune ligne dans {@code role_permissions} (base neuve, purge).
 * Ce tableau reproduit les migrations : le mettre a jour avec toute nouvelle
 * migration qui change la grille d'origine. ADMIN n'y figure pas (acces
 * complet fixe) ; DIRECTEUR non plus, aucune migration ne lui a jamais
 * attribue de module.</p>
 */
public final class DroitsParDefaut {

    private static final Map<RoleType, Map<ModuleMetier, NiveauPermission>> PAR_ROLE = new EnumMap<>(RoleType.class);

    static {
        definir(RoleType.CAISSIER,
            modules(CAISSE, BANQUE, MOBILE_MONEY, VENTES, RESTAURANT, ModuleMetier.RAPPROCHEMENT),
            modules(ModuleMetier.LOGISTIQUE));
        definir(RoleType.DFIN,
            modules(COMPTABILITE, ModuleMetier.BUDGET, ModuleMetier.RAPPROCHEMENT, ModuleMetier.LOGISTIQUE),
            modules(CAISSE, BANQUE, MOBILE_MONEY, VENTES, TRANSPORT, PATRIMOINE, DRH_PAIE, RESTAURANT));
        definir(RoleType.DA,
            modules(COMPTABILITE, ModuleMetier.BUDGET),
            modules(CAISSE, BANQUE, MOBILE_MONEY, ModuleMetier.RAPPROCHEMENT, VENTES, ModuleMetier.LOGISTIQUE, TRANSPORT, PATRIMOINE, RESTAURANT));
        definir(RoleType.DG,
            modules(),
            modules(CAISSE, BANQUE, MOBILE_MONEY, COMPTABILITE, ModuleMetier.BUDGET, ModuleMetier.RAPPROCHEMENT, VENTES,
                ModuleMetier.LOGISTIQUE,
                TRANSPORT, PATRIMOINE, RESTAURANT));
        definir(RoleType.COMPTABLE,
            modules(ModuleMetier.RAPPROCHEMENT),
            modules(CAISSE, COMPTABILITE, ModuleMetier.BUDGET, VENTES, ModuleMetier.LOGISTIQUE, PATRIMOINE, RESTAURANT));
        definir(RoleType.LOGISTIQUE,
            modules(ModuleMetier.LOGISTIQUE, TRANSPORT),
            modules());
        definir(RoleType.GEST_PATRIMOINE,
            modules(PATRIMOINE),
            modules(ModuleMetier.LOGISTIQUE));
        definir(RoleType.RESP_DRH,
            modules(DRH_PERSONNEL, DRH_PRESENCES, DRH_PAIE, DRH_MISSIONS),
            modules());
        definir(RoleType.RESP_RESTAURANT,
            modules(RESTAURANT),
            modules());
    }

    private DroitsParDefaut() {
    }

    /** Droits d'origine du role, module par module ; vide si le role n'en a jamais eu (ADMIN, DIRECTEUR). */
    public static Map<ModuleMetier, NiveauPermission> pour(RoleType role) {
        return PAR_ROLE.getOrDefault(role, Map.of());
    }

    private static ModuleMetier[] modules(ModuleMetier... modules) {
        return modules;
    }

    private static void definir(RoleType role, ModuleMetier[] ecriture, ModuleMetier[] lecture) {
        Map<ModuleMetier, NiveauPermission> droits = new EnumMap<>(ModuleMetier.class);
        for (ModuleMetier m : ecriture) {
            droits.put(m, NiveauPermission.ECRITURE);
        }
        for (ModuleMetier m : lecture) {
            droits.put(m, NiveauPermission.LECTURE);
        }
        PAR_ROLE.put(role, Collections.unmodifiableMap(droits));
    }
}
