package com.mbsc.finapp.service;

import com.mbsc.finapp.audit.AuditService;
import com.mbsc.finapp.domain.ModuleConfig;
import com.mbsc.finapp.domain.Role;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.repository.ModuleConfigRepository;
import com.mbsc.finapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cascade « module desactive -> roles et comptes ». Quand l'etat d'un module change, les comptes dont TOUS les
 * roles sont lies a des modules coupes sont <b>desactives</b> (et masques de la gestion des utilisateurs, voir
 * AdminService) ; ils sont <b>reactives</b> des que l'un de leurs roles redevient disponible.
 *
 * <p>Regles (voir {@link ModulesDesRoles}) : un compte qui cumule un role libre ou disponible reste actif ; un
 * compte sans role n'est jamais touche ; une desactivation faite a la main par l'administrateur n'est jamais
 * annulee automatiquement — seuls les comptes que <i>cette cascade</i> a coupes (drapeau
 * {@code desactive_par_module}) sont remis en service. La desactivation revoque les jetons du compte : sans cela,
 * la reactivation rendrait de nouveau valides les sessions ouvertes avant la coupure.</p>
 *
 * <p>Idempotente : la rappeler sans changement d'etat ne modifie rien. Appelee par
 * {@link ModuleConfigService#definir} (dans la meme transaction, apres l'ecriture de l'etat) et au demarrage de
 * l'application ({@code ComptesModulesAuDemarrage}) pour que les modules deja coupes soient en conformite. Elle
 * verrouille toutes les lignes {@code modules_config} : deux bascules simultanees ne doivent pas laisser un
 * compte bloque mais actif. Elle lit {@link ModuleConfigRepository} et non {@link ModuleConfigService}, qui l'appelle.</p>
 */
@Service
@RequiredArgsConstructor
public class SynchronisationComptesModules {

    private static final Logger log = LoggerFactory.getLogger(SynchronisationComptesModules.class);

    private final ModuleConfigRepository modules;
    private final UserRepository utilisateurs;
    private final AuditService audit;

    /** Comptes coupes et remis en service par une passe (identifiants de connexion et noms, pour les messages et l'audit). */
    public record Resultat(List<String> desactives, List<String> reactives) {
        public static final Resultat AUCUN = new Resultat(List.of(), List.of());

        public boolean vide() {
            return desactives.isEmpty() && reactives.isEmpty();
        }
    }

    /** Ce que changerait une bascule, sans rien ecrire : les roles dont la disponibilite change, et les comptes touches. */
    public record Impact(List<RoleType> roles, List<String> comptesADesactiver, List<String> comptesAReactiver) {
    }

    /** Ce que la passe decide pour chaque compte, sans l'appliquer. */
    private record Plan(List<User> aDesactiver, List<User> aReactiver) {
    }

    /**
     * Met les comptes en conformite avec l'etat actuel des modules. Verrouille les lignes des modules pour la
     * duree de la transaction de l'appelant.
     */
    @Transactional
    public Resultat reconcilier() {
        EtatModules etat = EtatModules.de(modules.findAllPourMiseAJour());
        Plan plan = planifier(etat, utilisateurs.findAll());

        List<String> desactives = new ArrayList<>();
        for (User u : plan.aDesactiver()) {
            u.setActif(false);
            u.setDesactiveParModule(true);
            // Les sessions ouvertes sont coupees des la requete suivante, et ne reviendront pas a la reactivation.
            u.revoquerJetons();
            desactives.add(nomAffiche(u));
        }
        List<String> reactives = new ArrayList<>();
        for (User u : plan.aReactiver()) {
            u.setActif(true);
            u.setDesactiveParModule(false);
            reactives.add(nomAffiche(u));
        }
        if (!plan.aDesactiver().isEmpty() || !plan.aReactiver().isEmpty()) {
            utilisateurs.saveAll(plan.aDesactiver());
            utilisateurs.saveAll(plan.aReactiver());
        }

        Resultat resultat = new Resultat(desactives, reactives);
        if (!resultat.vide()) {
            tracer("DESACTIVES_PAR_MODULE", desactives, "desactive(s) : leurs roles sont lies a un module desactive");
            tracer("REACTIVES_PAR_MODULE", reactives, "reactive(s) : un de leurs roles est de nouveau disponible");
        }
        return resultat;
    }

    /**
     * Ce qui se passerait si ce module prenait cet etat, sans rien ecrire : alimente la confirmation avant de couper
     * un module. Les comptes deja incoherents avec l'etat actuel y figurent aussi, puisque la passe reelle les
     * corrigerait au meme moment.
     */
    @Transactional(readOnly = true)
    public Impact simuler(ModuleMetier module, boolean actif) {
        EtatModules avant = EtatModules.de(modules.findAll());
        EtatModules apres = avant.avec(module, actif);
        Plan plan = planifier(apres, utilisateurs.findAll());

        List<RoleType> roles = new ArrayList<>();
        for (RoleType role : RoleType.values()) {
            if (ModulesDesRoles.estLie(role)
                && ModulesDesRoles.estDisponible(role, avant::estActif) != ModulesDesRoles.estDisponible(role, apres::estActif)) {
                roles.add(role);
            }
        }
        return new Impact(roles,
            plan.aDesactiver().stream().map(SynchronisationComptesModules::nomAffiche).toList(),
            plan.aReactiver().stream().map(SynchronisationComptesModules::nomAffiche).toList());
    }

    /** Un compte est bloque quand il a au moins un role et que tous ses roles sont lies a des modules coupes. */
    public static boolean estBloque(User u, EtatModules etat) {
        return ModulesDesRoles.estBloque(rolesDe(u), etat::estActif);
    }

    private static Plan planifier(EtatModules etat, List<User> comptes) {
        List<User> aDesactiver = new ArrayList<>();
        List<User> aReactiver = new ArrayList<>();
        for (User u : comptes) {
            boolean bloque = estBloque(u, etat);
            if (bloque && u.isActif()) {
                aDesactiver.add(u);
            } else if (!bloque && u.isDesactiveParModule()) {
                aReactiver.add(u);
            }
        }
        return new Plan(aDesactiver, aReactiver);
    }

    private static List<RoleType> rolesDe(User u) {
        return u.getRoles().stream().map(Role::getNom).toList();
    }

    /** « Prenom Nom (identifiant) » : de quoi reconnaitre le compte dans un message ou le journal d'audit. */
    static String nomAffiche(User u) {
        String nom = ((u.getPrenom() == null ? "" : u.getPrenom()) + " " + (u.getNom() == null ? "" : u.getNom())).trim();
        return nom.isEmpty() ? u.getEmail() : nom + " (" + u.getEmail() + ")";
    }

    /** Une ligne de journal par sens : le journal generique ne dit ni la direction d'une bascule ni les comptes touches. */
    private void tracer(String operation, List<String> comptes, String libelle) {
        if (comptes.isEmpty()) {
            return;
        }
        Map<String, Object> ligne = new LinkedHashMap<>();
        ligne.put("type", "CASCADE_MODULES");
        ligne.put("module", "comptes");
        ligne.put("operation", operation);
        ligne.put("email", acteur());
        ligne.put("reussi", true);
        ligne.put("detail", comptes.size() + " compte(s) " + libelle + " : " + String.join(", ", comptes));
        audit.enregistrer(ligne);
        log.info("Cascade des modules : {} compte(s) {} [{}]", comptes.size(), libelle, String.join(", ", comptes));
    }

    /** L'administrateur qui a bascule le module, ou « systeme » (demarrage de l'application). */
    private static String acteur() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getName() != null ? auth.getName() : "systeme";
    }
}
