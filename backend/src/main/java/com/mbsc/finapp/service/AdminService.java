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
        return userRepository.findAllByOrderByNomAscPrenomAsc().stream()
            .map(UserResponse::from)
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
        if (userRepository.existsByEmail(req.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email déjà utilisé.");
        }
        User u = User.builder()
            .nom(req.nom())
            .prenom(req.prenom())
            .email(req.email())
            .motDePasse(passwordEncoder.encode(req.motDePasse()))
            .actif(true)
            .roles(resoudreRoles(req.roles()))
            .build();
        return UserResponse.from(userRepository.save(u));
    }

    /** Modifie un utilisateur existant (ADMIN). */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public UserResponse modifierUtilisateur(Long id, UserUpdateRequest req) {
        User u = userRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable."));
        u.setNom(req.nom());
        u.setPrenom(req.prenom());
        if (req.motDePasse() != null && !req.motDePasse().isBlank()) {
            u.setMotDePasse(passwordEncoder.encode(req.motDePasse()));
        }
        u.setRoles(resoudreRoles(req.roles()));
        return UserResponse.from(userRepository.save(u));
    }

    /** Active ou désactive un utilisateur (ADMIN). */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public UserResponse toggleActif(Long id) {
        User u = userRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable."));
        u.setActif(!u.isActif());
        return UserResponse.from(userRepository.save(u));
    }
}
