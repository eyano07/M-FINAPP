package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ModuleConfig;
import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.dto.parametrage.ModuleConfigResponse;
import com.mbsc.finapp.dto.parametrage.ModuleImpactResponse;
import com.mbsc.finapp.repository.ModuleConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/** Activation/desactivation globale des modules metier par l'administrateur. */
@Service
@RequiredArgsConstructor
public class ModuleConfigService {

    private final ModuleConfigRepository repository;
    /** Desactive les comptes dont tous les roles sont lies a un module coupe, et les reactive avec le module. */
    private final SynchronisationComptesModules synchronisation;

    /**
     * Module activé pour toute l'application, indépendamment des droits de l'utilisateur courant :
     * désactivé lui-même ou via son module parent (même cascade que {@code PermissionService}).
     */
    @Transactional(readOnly = true)
    public boolean estActif(ModuleMetier module) {
        ModuleConfig config = repository.findById(module).orElse(null);
        if (config != null && !config.isActif()) {
            return false;
        }
        // Parent de la ligne, ou celui de la hierarchie du code si la ligne manque ou l'a perdu (voir EtatModules.parentDe).
        ModuleMetier parent = EtatModules.parentDe(config, module);
        return parent == null || repository.findById(parent).map(ModuleConfig::isActif).orElse(true);
    }

    @Transactional(readOnly = true)
    public java.util.List<ModuleConfigResponse> lister() {
        Map<ModuleMetier, ModuleConfig> existants = repository.findAll().stream()
            .collect(Collectors.toMap(ModuleConfig::getModule, mc -> mc));
        return Arrays.stream(ModuleMetier.values())
            .map(m -> new ModuleConfigResponse(m, !existants.containsKey(m) || existants.get(m).isActif(),
                EtatModules.parentDe(existants.get(m), m)))
            .toList();
    }

    /**
     * Apercu d'une bascule, sans rien ecrire : quels roles disparaitraient (ou reviendraient) et quels comptes
     * seraient desactives (ou reactives). Sert a confirmer avant de couper un module qui porte des comptes.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public ModuleImpactResponse impact(ModuleMetier module, boolean actif) {
        SynchronisationComptesModules.Impact impact = synchronisation.simuler(module, actif);
        return new ModuleImpactResponse(module, actif, impact.roles(), impact.comptesADesactiver(), impact.comptesAReactiver());
    }

    /**
     * Active ou desactive un module, puis met en conformite les comptes dont les roles sont lies a des modules
     * coupes (voir {@link SynchronisationComptesModules}), dans la meme transaction : l'etat des modules et celui
     * des comptes ne peuvent pas diverger. La reponse dit combien de comptes ont ete coupes ou remis en service.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ModuleConfigResponse definir(ModuleMetier module, boolean actif) {
        // Verrou sur toutes les lignes : deux bascules simultanees se suivent, elles ne se croisent pas.
        repository.findAllPourMiseAJour();
        ModuleConfig mc = repository.findById(module)
            .orElseGet(() -> ModuleConfig.builder().module(module).build());
        // La ligne garde (ou retrouve) son lien de hierarchie : une ligne creee ici sans parent laissait les
        // sous-modules actifs quand leur parent etait coupe.
        mc.setParentModule(EtatModules.parentDe(mc, module));
        mc.setActif(actif);
        mc = repository.save(mc);
        SynchronisationComptesModules.Resultat resultat = synchronisation.reconcilier();
        return ModuleConfigResponse.from(mc, resultat.desactives().size(), resultat.reactives().size());
    }
}
