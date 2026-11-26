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
        // Audit SYSCOHADA du 10/11/2026, C-20 : le caissier ne rapproche pas ses propres opérations (lecture seule), et
        // le DA, qui rouvre un mois rapproché, a l'écriture sur le module (V138).
        definir(RoleType.CAISSIER,
            modules(CAISSE, BANQUE, MOBILE_MONEY, VENTES, RESTAURANT),
            modules(ModuleMetier.LOGISTIQUE, ModuleMetier.MINE, ModuleMetier.RAPPROCHEMENT));
        // Audit DRH du 15/11/2026, D-11 : le DFIN clôture, rouvre et comptabilise la paie (seul rôle autorisé depuis
        // C-14) ; avec la seule lecture sur DRH_PAIE, ces actions étaient refusées à tout le monde (V146).
        definir(RoleType.DFIN,
            modules(COMPTABILITE, ModuleMetier.BUDGET, ModuleMetier.RAPPROCHEMENT, ModuleMetier.LOGISTIQUE, ModuleMetier.MINE, DRH_PAIE),
            modules(CAISSE, BANQUE, MOBILE_MONEY, VENTES, TRANSPORT, PATRIMOINE, RESTAURANT, ModuleMetier.HOTELLERIE));
        definir(RoleType.DA,
            modules(COMPTABILITE, ModuleMetier.BUDGET, ModuleMetier.RAPPROCHEMENT),
            modules(CAISSE, BANQUE, MOBILE_MONEY, VENTES, ModuleMetier.LOGISTIQUE, ModuleMetier.MINE, TRANSPORT, PATRIMOINE, RESTAURANT,
                ModuleMetier.HOTELLERIE));
        definir(RoleType.DG,
            modules(),
            modules(CAISSE, BANQUE, MOBILE_MONEY, COMPTABILITE, ModuleMetier.BUDGET, ModuleMetier.RAPPROCHEMENT, VENTES,
                ModuleMetier.LOGISTIQUE, ModuleMetier.MINE,
                TRANSPORT, PATRIMOINE, RESTAURANT, ModuleMetier.HOTELLERIE));
        // Le comptable saisit les pièces, le DFIN les valide (C-06) : la comptabilité en écriture (V138). Il élabore
        // aussi les budgets, que le DA approuve et que le DFIN met en exécution (V164).
        definir(RoleType.COMPTABLE,
            modules(COMPTABILITE, ModuleMetier.BUDGET, ModuleMetier.RAPPROCHEMENT),
            modules(CAISSE, VENTES, ModuleMetier.LOGISTIQUE, ModuleMetier.MINE, PATRIMOINE, RESTAURANT,
                ModuleMetier.HOTELLERIE));
        // Le responsable logistique tient aussi l'inventaire et les fiches d'amortissement du patrimoine (V153).
        definir(RoleType.LOGISTIQUE,
            modules(ModuleMetier.LOGISTIQUE, ModuleMetier.MINE, TRANSPORT, PATRIMOINE),
            modules());
        definir(RoleType.GEST_PATRIMOINE,
            modules(PATRIMOINE),
            modules(ModuleMetier.LOGISTIQUE, ModuleMetier.MINE));
        definir(RoleType.RESP_DRH,
            modules(DRH_PERSONNEL, DRH_PRESENCES, DRH_PAIE, DRH_MISSIONS),
            modules());
        definir(RoleType.RESP_RESTAURANT,
            modules(RESTAURANT),
            modules());
        // Responsables cuisine et bar (V124) : le restaurant en ecriture, et la logistique en lecture seule — sans
        // elle le filtre de module renverrait 403 sur les ecrans Logistique (articles, stock, mouvements) que
        // chacun consulte pour son domaine ; les gardes de StockService et PerimetreRestaurant limitent ces lectures
        // aux articles du role. Aucune ecriture logistique.
        definir(RoleType.RESP_CUISINE,
            modules(RESTAURANT),
            modules(ModuleMetier.LOGISTIQUE));
        definir(RoleType.RESP_BAR,
            modules(RESTAURANT),
            modules(ModuleMetier.LOGISTIQUE));
        // Hôtellerie (V152) : les rôles de l'hébergement écrivent par le PMS ; la direction de l'hébergement lit aussi les
        // ventes (folios devenus ventes) de M-FINAPP.
        definir(RoleType.RECEPTIONNISTE,
            modules(ModuleMetier.HOTELLERIE),
            modules());
        definir(RoleType.GOUVERNANTE,
            modules(ModuleMetier.HOTELLERIE),
            modules());
        definir(RoleType.TECHNICIEN,
            modules(ModuleMetier.HOTELLERIE),
            modules());
        definir(RoleType.DIR_HEBERGEMENT,
            modules(ModuleMetier.HOTELLERIE),
            modules(VENTES));
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
