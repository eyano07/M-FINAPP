package com.mbsc.finapp.domain.enums;

/**
 * Modules metier pouvant etre desactives ou soumis a une permission par
 * role (voir {@link NiveauPermission}). Le tableau de bord et les notes de
 * frais restent hors de ce systeme : accessibles a tout utilisateur
 * authentifie, ce sont les fonctions socle de l'application (chaque
 * employe doit toujours pouvoir soumettre ses frais), pas des modules
 * metier optionnels. L'administration (utilisateurs, parametrage) reste
 * elle aussi hors systeme, reservee au role ADMIN de maniere fixe : y
 * inclure ADMIN reviendrait a permettre a un administrateur de se
 * verrouiller lui-meme hors de la configuration.
 *
 * <p>La plupart des valeurs sont des modules autonomes (pas de parent). DRH
 * est le premier module hierarchique : {@code DRH} est un conteneur pur,
 * jamais assigne directement dans {@code role_permissions} (aucun controleur
 * ne s'y rattache), tandis que {@code DRH_PERSONNEL}, {@code DRH_PRESENCES},
 * {@code DRH_PAIE} et {@code DRH_MISSIONS} sont ses sous-modules reels,
 * chacun active/desactive independamment mais desactive en cascade si le
 * parent DRH est lui-meme desactive (voir
 * {@code ModuleConfig#getParentModule()} et
 * {@code PermissionService#niveauEffectif}).</p>
 */
public enum ModuleMetier {
    CAISSE,
    BANQUE,
    MOBILE_MONEY,
    COMPTABILITE,
    /**
     * Budgets prévisionnels et contrôle budgétaire des notes de frais, sous-module de COMPTABILITE.
     * Désactivé, les dépenses ne sont plus rattachées ni contrôlées par rapport au budget.
     */
    BUDGET,
    /** Rapprochement bancaire (relevés des banques et du mobile money), sous-module de COMPTABILITE. */
    RAPPROCHEMENT,
    VENTES,
    /**
     * Facture normalisee DGI (e-MCF), sous-module de VENTES : interrupteur unique de la fonction. Desactive, les
     * ventes validees ne sont plus transmises a la DGI (les avoirs des factures deja certifiees restent emis).
     * Ne garde aucune URL : ses ecrans relevent des modules VENTES et de l'administration.
     */
    FACTURATION_NORMALISEE,
    LOGISTIQUE,
    TRANSPORT,
    /** Biens immobilises et consommables (registre, amortissements). */
    PATRIMOINE,
    /** Conteneur pur : jamais assigne dans role_permissions, jamais rattache a un controleur. */
    DRH,
    /** Agents, fiches, matricules. */
    DRH_PERSONNEL,
    /** Pointage, fiche de presence manuelle, sorties de travailleurs. */
    DRH_PRESENCES,
    /** Bulletins de paie et parametres de paie. */
    DRH_PAIE,
    /** Ordres de mission, sites operationnels, rotations de superviseurs. */
    DRH_MISSIONS,
    /**
     * Restauration : carte (plats, boissons) et parc d'emballages consignes
     * (bouteilles, bacs). Module plat, sans sous-module : l'administrateur
     * l'active ou le coupe d'un seul geste, comme {@link #PATRIMOINE}.
     */
    RESTAURANT;

    /**
     * Module parent dans la hierarchie (DRH et ses quatre sous-modules, COMPTABILITE avec BUDGET et RAPPROCHEMENT,
     * VENTES avec FACTURATION_NORMALISEE), ou {@code null} pour un module autonome. C'est la meme hierarchie que la
     * colonne {@code modules_config.parent_module} (V41, V104, V105, V113) — mais definie ici, dans le code : une
     * ligne recree par une bascule, ou apres une purge de la table, n'avait plus son lien et le module restait actif
     * quand son parent etait coupe. Voir {@link com.mbsc.finapp.service.EtatModules#parentDe}.
     */
    public ModuleMetier parent() {
        return switch (this) {
            case DRH_PERSONNEL, DRH_PRESENCES, DRH_PAIE, DRH_MISSIONS -> DRH;
            case BUDGET, RAPPROCHEMENT -> COMPTABILITE;
            case FACTURATION_NORMALISEE -> VENTES;
            default -> null;
        };
    }
}
