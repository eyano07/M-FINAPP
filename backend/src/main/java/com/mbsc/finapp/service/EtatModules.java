package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ModuleConfig;
import com.mbsc.finapp.domain.enums.ModuleMetier;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/**
 * Etat actif / inactif de chaque module metier a un instant donne, avec la cascade hierarchique : un module n'est
 * actif que s'il l'est lui-meme ET que son module parent l'est (DRH et ses quatre sous-modules, COMPTABILITE et
 * BUDGET / RAPPROCHEMENT, VENTES et FACTURATION_NORMALISEE). Une ligne absente vaut « actif, sans parent ».
 *
 * <p>Classe pure, sans acces a la base : l'etat se construit d'une liste de lignes {@code modules_config}, et
 * {@link #avec} produit l'etat hypothetique « tel module coupe » pour simuler une bascule sans rien ecrire (apercu
 * d'impact, voir {@link SynchronisationComptesModules#simuler}). La regle reproduit {@code ModuleConfigService.estActif}
 * et {@code PermissionService.niveauEffectif} ; {@code EtatModulesTest} garantit l'equivalence.</p>
 */
public final class EtatModules {

    private final Map<ModuleMetier, ModuleConfig> configs;

    private EtatModules(Map<ModuleMetier, ModuleConfig> configs) {
        this.configs = configs;
    }

    public static EtatModules de(Collection<ModuleConfig> lignes) {
        Map<ModuleMetier, ModuleConfig> configs = new EnumMap<>(ModuleMetier.class);
        for (ModuleConfig ligne : lignes) {
            configs.put(ligne.getModule(), ligne);
        }
        return new EtatModules(configs);
    }

    /**
     * Parent d'un module : celui de sa ligne {@code modules_config} s'il y en a un, sinon celui de la hierarchie du
     * code ({@link ModuleMetier#parent}). La ligne peut manquer ou avoir perdu son lien (recreee par une bascule,
     * table purgee) : sans ce repli, couper un parent laissait ses sous-modules actifs.
     */
    public static ModuleMetier parentDe(ModuleConfig config, ModuleMetier module) {
        return config != null && config.getParentModule() != null ? config.getParentModule() : module.parent();
    }

    /** Module actif pour toute l'application : lui-meme et son parent (cascade a un seul niveau). */
    public boolean estActif(ModuleMetier module) {
        ModuleConfig config = configs.get(module);
        if (config != null && !config.isActif()) {
            return false;
        }
        ModuleMetier parent = parentDe(config, module);
        if (parent == null) {
            return true;
        }
        ModuleConfig configParent = configs.get(parent);
        return configParent == null || configParent.isActif();
    }

    /** Le meme etat, sauf ce module force a actif ou inactif — sans toucher a l'etat d'origine. */
    public EtatModules avec(ModuleMetier module, boolean actif) {
        Map<ModuleMetier, ModuleConfig> copie = new EnumMap<>(ModuleMetier.class);
        copie.putAll(configs);
        ModuleConfig existant = configs.get(module);
        copie.put(module, ModuleConfig.builder()
            .module(module)
            .actif(actif)
            .parentModule(parentDe(existant, module))
            .build());
        return new EtatModules(copie);
    }
}
