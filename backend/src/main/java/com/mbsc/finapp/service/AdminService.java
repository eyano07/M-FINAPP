package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.Role;
import com.mbsc.finapp.domain.TauxChange;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.dto.admin.CompteDetailResponse;
import com.mbsc.finapp.dto.admin.CompteRequest;
import com.mbsc.finapp.dto.admin.CompteResponse;
import com.mbsc.finapp.dto.admin.CompteUpdateRequest;
import com.mbsc.finapp.dto.admin.TauxChangeRequest;
import com.mbsc.finapp.dto.admin.TauxChangeResponse;
import com.mbsc.finapp.dto.admin.UserCreateRequest;
import com.mbsc.finapp.dto.admin.UserResponse;
import com.mbsc.finapp.dto.admin.UserUpdateRequest;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.RoleRepository;
import com.mbsc.finapp.repository.TauxChangeRepository;
import com.mbsc.finapp.repository.UserRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import com.mbsc.finapp.security.IdentifiantConnexion;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lectures d'administration : plan comptable, annuaire et taux de change.
 */
@Service
@RequiredArgsConstructor
public class AdminService {

    private final CompteOHADARepository compteRepository;
    private final EcritureGrandLivreRepository ecritureRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final TauxChangeRepository tauxChangeRepository;
    private final PasswordEncoder passwordEncoder;
    /** Auteur des modifications de taux, trace comme sur le taux de TVA. */
    private final CurrentUserProvider currentUser;
    /** Fiche de documentation de chaque compte créé (voir ajouterCompte). */
    private final DocumentationCompteService documentation;
    private final PermissionService permissionService;
    /** Etat des modules : un role dont le module est coupe n'est plus propose, ni ses comptes montres. */
    private final RolesDisponibles rolesDisponibles;

    /** Plan comptable OHADA, accessible a tout utilisateur authentifie. */
    @Transactional(readOnly = true)
    public List<CompteResponse> listerComptes() {
        return compteRepository.findAllByOrderByNumeroAsc().stream()
            .map(CompteResponse::from)
            .toList();
    }

    /**
     * Detail d'un compte, avec les rubriques du referentiel SYSCOHADA
     * commente. Servi a part de la liste : ces textes sont volumineux et
     * n'ont d'interet qu'a la consultation d'un compte precis.
     */
    @Transactional(readOnly = true)
    public CompteDetailResponse consulterCompte(Long id) {
        return CompteDetailResponse.from(compteRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("CompteOHADA", id)));
    }

    /** Ajoute un compte manuellement, héritant du type et de la classe de son parent (ADMIN, DFIN). */
    @PreAuthorize("hasAnyRole('ADMIN', 'DFIN')")
    @Transactional
    public CompteResponse ajouterCompte(CompteRequest req) {
        CompteOHADA parent = compteRepository.findByNumero(req.parentNumero())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Compte parent introuvable : " + req.parentNumero()));

        String nouveauNumero = req.parentNumero() + "." + req.suffixe().trim();
        if (compteRepository.existsByNumero(nouveauNumero)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Numéro de compte déjà existant : " + nouveauNumero);
        }

        CompteOHADA c = CompteOHADA.builder()
            .numero(nouveauNumero)
            .libelle(req.libelle())
            .type(parent.getType())
            .classe(parent.getClasse())
            .parent(parent)
            .manuel(true)
            .build();
        // Tout compte créé reçoit sa fiche (contenu, fonctionnement, contrôle...), comme ceux
        // du référentiel : rôle déduit du parent, origine, note facultative de l'utilisateur.
        documentation.documenterSaisieManuelle(c, req.note());
        return CompteResponse.from(compteRepository.save(c));
    }

    /**
     * Supprime un compte manuel (ADMIN, DFIN). Les comptes pré-chargés ne peuvent
     * pas être supprimés, ni aucun compte déjà mouvementé dans le Grand Livre
     * (règle Sage/QuickBooks : on désactive, on ne supprime pas l'historique).
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'DFIN')")
    @Transactional
    public void supprimerCompte(Long id) {
        CompteOHADA c = compteRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte introuvable."));
        if (!c.isManuel()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Seuls les comptes ajoutés manuellement peuvent être supprimés.");
        }
        if (ecritureRepository.existsByCompteId(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Ce compte a déjà des écritures : il ne peut pas être supprimé. Désactivez-le à la place.");
        }
        compteRepository.delete(c);
    }

    /**
     * Renomme un compte manuel (ADMIN, DFIN). Autorisé même si le compte est
     * déjà mouvementé : contrairement à la suppression, corriger un libellé
     * ne remet en cause ni le numéro ni l'historique des écritures qui s'y
     * rattachent — c'est au contraire le cas d'usage principal (un compte
     * créé automatiquement à l'import hérite parfois d'un libellé impropre).
     * Les comptes du référentiel officiel (non manuels) restent en lecture
     * seule ici.
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'DFIN')")
    @Transactional
    public CompteResponse modifierCompte(Long id, CompteUpdateRequest req) {
        CompteOHADA c = compteRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte introuvable."));
        if (!c.isManuel()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Seuls les comptes ajoutés manuellement peuvent être renommés.");
        }
        c.setLibelle(req.libelle().trim());
        return CompteResponse.from(compteRepository.save(c));
    }

    /** Active ou désactive un compte (ADMIN). Un compte désactivé n'accepte plus d'imputation. */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CompteResponse toggleCompteActif(Long id) {
        CompteOHADA c = compteRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte introuvable."));
        c.setActif(!c.isActif());
        return CompteResponse.from(compteRepository.save(c));
    }

    /** Annuaire des utilisateurs, reserve aux administrateurs. */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public List<UserResponse> listerUtilisateurs() {
        EtatModules etat = rolesDisponibles.etat();
        return userRepository.findAllByOrderByNomAscPrenomAsc().stream()
            // Un compte dont tous les roles sont lies a un module desactive est masque (et desactive : voir
            // SynchronisationComptesModules). Les roles masques ne figurent pas non plus sur les autres comptes.
            .filter(u -> !SynchronisationComptesModules.estBloque(u, etat))
            .map(u -> UserResponse.from(u, role -> ModulesDesRoles.estDisponible(role, etat::estActif)))
            .toList();
    }

    /** Taux de change actuel + historique (tout utilisateur authentifie). */
    @Transactional(readOnly = true)
    public TauxChangeResponse getTauxChange() {
        List<TauxChange> historique = tauxChangeRepository.findAllByOrderByDateEffetDescCreatedAtDesc();
        BigDecimal tauxActuel = historique.isEmpty() ? BigDecimal.ZERO : historique.get(0).getTaux();
        LocalDate dateActuelle = historique.isEmpty() ? LocalDate.now() : historique.get(0).getDateEffet();
        List<TauxChangeResponse.HistoriqueEntry> entries = historique.stream()
            .map(TauxChangeResponse.HistoriqueEntry::from)
            .toList();
        return new TauxChangeResponse(tauxActuel, dateActuelle, entries);
    }

    /** Enregistre un nouveau taux de change (ADMIN uniquement). */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public TauxChangeResponse.HistoriqueEntry enregistrerTaux(TauxChangeRequest req) {
        // Un taux ne peut pas etre date du futur : tauxCourant() ne retient
        // que les dates <= aujourd'hui, un taux futur serait donc enregistre
        // puis ignore — l'administrateur croirait l'avoir applique.
        //
        // Tolerance d'un jour : le serveur raisonne en UTC alors que les
        // utilisateurs sont en UTC+1/+2 (RDC). En soiree, leur « aujourd'hui »
        // est deja le lendemain cote serveur ; sans cette marge, la saisie du
        // taux du jour serait refusee a tort chaque soir.
        LocalDate limite = LocalDate.now().plusDays(1);
        if (req.dateEffet().isAfter(limite)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Un taux ne peut pas etre date du futur (date demandee : " + req.dateEffet()
                + ", limite : " + limite + ").");
        }

        // Garde-fou de saisie : une variation brutale par rapport au dernier
        // taux connu revele presque toujours une faute de frappe (2840 saisi
        // 28400, ou 28,40). Le taux etant applique a toutes les conversions,
        // l'erreur se propagerait a l'ensemble de la comptabilite.
        tauxChangeRepository.findFirstByDateEffetLessThanEqualOrderByDateEffetDescCreatedAtDesc(req.dateEffet())
            .map(TauxChange::getTaux)
            .filter(precedent -> precedent.signum() > 0)
            .ifPresent(precedent -> {
                BigDecimal rapport = req.taux().divide(precedent, 4, RoundingMode.HALF_UP);
                if (rapport.compareTo(new BigDecimal("2")) > 0
                    || rapport.compareTo(new BigDecimal("0.5")) < 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Taux refuse : " + req.taux() + " s'ecarte de plus de 50 % du dernier taux connu ("
                        + precedent + "). Verifiez la saisie.");
                }
            });

        // Un seul taux par date d'effet (contrainte uq_taux_change_date_effet) :
        // une nouvelle saisie pour une date deja renseignee corrige le taux
        // existant au lieu d'echouer sur une violation de contrainte.
        TauxChange t = tauxChangeRepository.findByDateEffet(req.dateEffet())
            .orElseGet(() -> TauxChange.builder().dateEffet(req.dateEffet()).build());
        t.setTaux(req.taux());
        t.setNote(req.note());
        t.setSource(req.source());
        t.setCreatedBy(currentUser.requireUser());
        return TauxChangeResponse.HistoriqueEntry.from(tauxChangeRepository.save(t));
    }

    // ─── Gestion des utilisateurs ──────────────────────────────────────────────

    private Set<Role> resoudreRoles(List<String> noms) {
        return noms.stream().map(n -> {
            RoleType rt = RoleType.valueOf(n);
            return roleRepository.findByNom(rt)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role inconnu : " + n));
        }).collect(Collectors.toSet());
    }

    /** Crée un nouvel utilisateur (ADMIN). */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public UserResponse creerUtilisateur(UserCreateRequest req) {
        // Identifiant de connexion : adresse e-mail ou nom d'utilisateur, enregistré en minuscules et
        // unique sans tenir compte de la casse (« Dfin_Bruno » et « dfin_bruno » sont le même compte).
        String identifiant = IdentifiantConnexion.normaliser(req.email());
        if (userRepository.existsByEmailIgnoreCase(identifiant)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cet identifiant de connexion est déjà utilisé.");
        }
        EtatModules etat = rolesDisponibles.etat();
        Set<Role> roles = resoudreRoles(req.roles());
        exigerRolesDisponibles(roles, Set.of(), etat);
        User u = User.builder()
            .nom(req.nom())
            .prenom(req.prenom())
            .email(identifiant)
            .motDePasse(passwordEncoder.encode(req.motDePasse()))
            .telephone(videEnNull(req.telephone()))
            .emailNotification(emailNotification(req.emailNotification()))
            .fonction(videEnNull(req.fonction()))
            .affectation(videEnNull(req.affectation()))
            .actif(true)
            .roles(roles)
            .build();
        return avecDroitsParDefaut(userRepository.save(u), etat);
    }

    /**
     * Refuse (409) un role dont le module est desactive, sauf s'il est deja porte par le compte : un role masque deja
     * detenu n'est jamais retire par une modification, il reste en place pour le retour du module.
     */
    private static void exigerRolesDisponibles(Set<Role> demandes, Set<RoleType> dejaPortes, EtatModules etat) {
        for (Role role : demandes) {
            RoleType type = role.getNom();
            if (!dejaPortes.contains(type) && !ModulesDesRoles.estDisponible(type, etat::estActif)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Le rôle " + type + " n'est pas disponible : le module auquel il est lié est désactivé "
                        + "(Administration > Modules).");
            }
        }
    }

    /**
     * Les droits sont rattaches au role : un role sans aucune ligne dans la
     * grille (base neuve, purge) ne donnerait acces a rien a ses utilisateurs
     * et, pour un role de module, les ferait tourner en boucle a la connexion.
     * On pose donc les droits d'origine des roles de l'utilisateur qui n'en ont
     * aucun ; un role deja configure n'est jamais modifie.
     */
    private UserResponse avecDroitsParDefaut(User u, EtatModules etat) {
        List<RoleType> noms = u.getRoles().stream().map(Role::getNom).toList();
        return UserResponse.from(u, role -> ModulesDesRoles.estDisponible(role, etat::estActif))
            .avecDroitsParDefaut(permissionService.appliquerDroitsParDefaut(noms));
    }

    /** Modifie un utilisateur existant (ADMIN). */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public UserResponse modifierUtilisateur(Long id, UserUpdateRequest req) {
        User u = userRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable."));
        EtatModules etat = rolesDisponibles.etat();
        exigerCompteVisible(u, etat);
        u.setNom(req.nom());
        u.setPrenom(req.prenom());
        if (req.motDePasse() != null && !req.motDePasse().isBlank()) {
            u.setMotDePasse(passwordEncoder.encode(req.motDePasse()));
            // Mot de passe réinitialisé : les sessions ouvertes avec l'ancien
            // (y compris par un tiers qui le connaissait) sont fermées.
            u.revoquerJetons();
        }
        u.setTelephone(videEnNull(req.telephone()));
        u.setEmailNotification(emailNotification(req.emailNotification()));
        u.setFonction(videEnNull(req.fonction()));
        u.setAffectation(videEnNull(req.affectation()));
        u.setRoles(rolesApresModification(u, resoudreRoles(req.roles()), etat));
        return avecDroitsParDefaut(userRepository.save(u), etat);
    }

    /**
     * Les roles du compte apres modification : ceux que l'ecran envoie, plus les roles masques (module desactive)
     * qu'il portait deja et que l'ecran ne montre pas — sans cela, enregistrer un compte multi-roles lui retirerait
     * en silence le role masque, qui ne reviendrait pas avec le module. Refuse (409) un role indisponible
     * nouvellement demande, et un resultat qui ne laisserait au compte que des roles indisponibles.
     */
    private static Set<Role> rolesApresModification(User u, Set<Role> demandes, EtatModules etat) {
        Set<RoleType> dejaPortes = u.getRoles().stream().map(Role::getNom).collect(Collectors.toSet());
        exigerRolesDisponibles(demandes, dejaPortes, etat);
        Set<Role> resultat = new java.util.HashSet<>(demandes);
        for (Role porte : u.getRoles()) {
            if (!ModulesDesRoles.estDisponible(porte.getNom(), etat::estActif)) {
                resultat.add(porte);
            }
        }
        List<RoleType> types = resultat.stream().map(Role::getNom).toList();
        if (ModulesDesRoles.estBloque(types, etat::estActif)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Ce compte n'aurait plus que des rôles liés à des modules désactivés : choisissez au moins un rôle disponible.");
        }
        return resultat;
    }

    /** Un compte masque (tous ses roles lies a des modules desactives) ne se modifie ni ne s'active qu'une fois le module reactive. */
    private static void exigerCompteVisible(User u, EtatModules etat) {
        if (SynchronisationComptesModules.estBloque(u, etat)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Ce compte est lié à un module désactivé : réactivez le module pour le retrouver.");
        }
    }

    private String emailNotification(String s) {
        String v = videEnNull(s);
        return v == null ? null : v.toLowerCase();
    }

    /** {@code null}/vide -> {@code null} (evite de stocker une chaine vide plutot qu'une absence de valeur). */
    private String videEnNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** Active ou désactive un utilisateur (ADMIN). */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public UserResponse toggleActif(Long id) {
        User u = userRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable."));
        EtatModules etat = rolesDisponibles.etat();
        exigerCompteVisible(u, etat);
        u.setActif(!u.isActif());
        if (!u.isActif()) {
            // Un compte désactivé perd immédiatement l'accès : ses jetons
            // (accès et rafraîchissement) ne sont plus acceptés.
            u.revoquerJetons();
        }
        return UserResponse.from(userRepository.save(u), role -> ModulesDesRoles.estDisponible(role, etat::estActif));
    }

    /** Roles qu'on peut attribuer aujourd'hui (ceux dont le module n'est pas desactive). */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public List<RoleType> rolesDisponibles() {
        return rolesDisponibles.disponibles();
    }
}
