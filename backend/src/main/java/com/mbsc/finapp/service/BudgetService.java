package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Budget;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.LigneBudget;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.StatutBudget;
import com.mbsc.finapp.dto.budget.BudgetRequest;
import com.mbsc.finapp.dto.budget.BudgetResponse;
import com.mbsc.finapp.dto.budget.BudgetResumeResponse;
import com.mbsc.finapp.dto.budget.CompteBudgetableResponse;
import com.mbsc.finapp.dto.budget.LigneBudgetRequest;
import com.mbsc.finapp.dto.budget.RepartitionRequest;
import com.mbsc.finapp.dto.budget.RepartitionResponse;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse;
import com.mbsc.finapp.dto.notes.ActionWorkflowRequest;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.BudgetRepository;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Budgets previsionnels annuels, ventiles par mois (voir {@link StatutBudget} pour le circuit).
 *
 * <p>Regles : une ligne par compte budgetable (classes 2, 6, 7, 8 du plan SYSCOHADA, hors 28/29), sans
 * recouvrement entre lignes ; montants mensuels positifs ou nuls ; un seul budget en execution par exercice
 * (une revision le remplace quand elle est mise en execution) ; un budget n'est modifiable qu'en brouillon.</p>
 */
@Service
@RequiredArgsConstructor
public class BudgetService {

    private static final Logger log = LoggerFactory.getLogger(BudgetService.class);

    static final String LECTURE = "hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')";
    static final String ECRITURE = "hasAnyRole('DFIN', 'ADMIN')";
    static final String APPROBATION = "hasAnyRole('DA', 'ADMIN')";

    /** Revisions encore en cours : une seule a la fois pour un meme budget. */
    private static final EnumSet<StatutBudget> REVISION_EN_COURS =
        EnumSet.of(StatutBudget.BROUILLON, StatutBudget.SOUMIS, StatutBudget.APPROUVE);

    private final BudgetRepository budgetRepository;
    private final CompteOHADARepository compteRepository;
    private final ReferenceGenerator referenceGenerator;
    private final CurrentUserProvider currentUser;
    private final SuiviBudgetaireService suivi;

    // ---------------------------------------------------------------------
    // Lecture
    // ---------------------------------------------------------------------

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public List<BudgetResumeResponse> lister() {
        List<Budget> budgets = budgetRepository.findAllByOrderByExerciceDescDateCreationDesc();
        // Une seule lecture du grand livre par exercice, quel que soit le nombre de budgets.
        Map<Integer, Map<String, SuiviBudgetaireService.MouvementsCompte>> mouvements = new HashMap<>();
        List<BudgetResumeResponse> resultat = new ArrayList<>();
        for (Budget b : budgets) {
            Map<String, SuiviBudgetaireService.MouvementsCompte> mvts =
                mouvements.computeIfAbsent(b.getExercice(), suivi::mouvementsDeLExercice);
            BudgetResponse detail = BudgetResponse.from(b);
            Map<MoteurBudgetaire.Section, BigDecimal[]> realise = suivi.realiseParSection(b, mvts);
            BigDecimal rProduits = MoteurBudgetaire.total(realise.get(MoteurBudgetaire.Section.PRODUITS));
            BigDecimal rCharges = MoteurBudgetaire.total(realise.get(MoteurBudgetaire.Section.CHARGES));
            BigDecimal rInvest = MoteurBudgetaire.total(realise.get(MoteurBudgetaire.Section.INVESTISSEMENTS));
            BigDecimal prevuDepenses = detail.totalCharges().add(detail.totalInvestissements());
            BigDecimal realiseDepenses = rCharges.add(rInvest);
            resultat.add(new BudgetResumeResponse(b.getId(), b.getReference(), b.getIntitule(), b.getExercice(),
                b.getStatut(), b.getNumeroRevision(), detail.elaboreParNom(), detail.approuveParNom(), b.getDateCreation(),
                detail.totalProduits(), detail.totalCharges(), detail.totalInvestissements(),
                rProduits, rCharges, rInvest, prevuDepenses, realiseDepenses,
                MoteurBudgetaire.taux(realiseDepenses, prevuDepenses), b.getLignes().size()));
        }
        return resultat;
    }

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public BudgetResponse consulter(Long id) {
        return BudgetResponse.from(charger(id));
    }

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public SuiviBudgetResponse suivi(Long id) {
        return suivi.suivre(charger(id));
    }

    /** Comptes pouvant porter une ligne budgetaire, filtres par numero (debut) ou libelle (sans accents). */
    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public List<CompteBudgetableResponse> comptesBudgetables(String recherche) {
        String q = normaliser(recherche);
        return compteRepository.findAllByOrderByNumeroAsc().stream()
            .filter(c -> c.isActif() && MoteurBudgetaire.estBudgetable(c.getNumero(), c.getClasse()))
            .filter(c -> q.isEmpty() || c.getNumero().startsWith(q) || normaliser(c.getLibelle()).contains(q))
            .limit(60)
            .map(c -> new CompteBudgetableResponse(c.getNumero(), c.getLibelle(), c.getClasse(),
                c.getType() == null ? null : c.getType().name(),
                MoteurBudgetaire.section(c.getNumero(), c.getType()).name(), c.isImputable()))
            .toList();
    }

    /**
     * Ventilation d'un montant annuel : parts egales, ou saisonnalite reelle (realise mensuel de l'annee
     * precedente sur le compte et ses sous-comptes) ; parts egales a defaut d'historique.
     */
    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public RepartitionResponse repartir(RepartitionRequest req) {
        if ("SAISONNALITE".equalsIgnoreCase(req.mode()) && StringUtils.hasText(req.compteNumero()) && req.exercice() != null) {
            BigDecimal[] profil = realiseMensuelDuCompte(req.compteNumero().trim(), req.exercice() - 1);
            if (MoteurBudgetaire.total(profil).signum() > 0) {
                return new RepartitionResponse(List.of(MoteurBudgetaire.repartirSelonProfil(req.montantAnnuel(), profil)),
                    "SAISONNALITE", "Réparti selon le réalisé mensuel de " + (req.exercice() - 1) + " sur le compte " + req.compteNumero() + ".");
            }
            return new RepartitionResponse(List.of(MoteurBudgetaire.repartirUniformement(req.montantAnnuel())),
                "UNIFORME", "Aucun réalisé en " + (req.exercice() - 1) + " sur ce compte : réparti à parts égales.");
        }
        return new RepartitionResponse(List.of(MoteurBudgetaire.repartirUniformement(req.montantAnnuel())),
            "UNIFORME", "Réparti à parts égales sur les douze mois.");
    }

    /** Realise mensuel d'un compte et de ses sous-comptes sur un exercice (sens selon la nature du compte). */
    BigDecimal[] realiseMensuelDuCompte(String numero, int exercice) {
        CompteOHADA compte = compteRepository.findByNumero(numero).orElse(null);
        BigDecimal[] total = MoteurBudgetaire.zeros();
        if (compte == null) {
            return total;
        }
        for (SuiviBudgetaireService.MouvementsCompte m : suivi.mouvementsDeLExercice(exercice).values()) {
            if (m.numero().startsWith(numero)) {
                total = MoteurBudgetaire.additionner(total, m.realiseSelon(compte.getType()));
            }
        }
        return total;
    }

    // ---------------------------------------------------------------------
    // Creation / modification / suppression (BROUILLON)
    // ---------------------------------------------------------------------

    @PreAuthorize(ECRITURE)
    @Transactional
    public BudgetResponse creer(BudgetRequest req) {
        User auteur = currentUser.requireUser();
        Budget budget = Budget.builder()
            .reference(referenceGenerator.pourBudget())
            .intitule(req.intitule().trim())
            .exercice(req.exercice())
            .statut(StatutBudget.BROUILLON)
            .elaborePar(auteur)
            .observation(vide(req.observation()))
            .build();
        appliquerLignes(budget, req.lignes());
        budget = budgetRepository.save(budget);
        log.info("Budget cree [{} {}, exercice={}, lignes={}, par={}]", budget.getReference(), budget.getIntitule(),
            budget.getExercice(), budget.getLignes().size(), auteur.getEmail());
        return BudgetResponse.from(budget);
    }

    @PreAuthorize(ECRITURE)
    @Transactional
    public BudgetResponse modifier(Long id, BudgetRequest req) {
        Budget budget = charger(id);
        exigerEtat(budget, StatutBudget.BROUILLON, "modifier");
        if (budget.getRevisionDe() != null && !budget.getRevisionDe().getExercice().equals(req.exercice())) {
            throw new IllegalArgumentException("Une révision porte sur le même exercice que le budget révisé ("
                + budget.getRevisionDe().getExercice() + ").");
        }
        budget.setIntitule(req.intitule().trim());
        budget.setExercice(req.exercice());
        budget.setObservation(vide(req.observation()));
        budget.getLignes().clear();
        budgetRepository.flush();   // les anciennes lignes partent avant l'insertion des nouvelles
        appliquerLignes(budget, req.lignes());
        log.info("Budget modifie [{}]", budget.getReference());
        return BudgetResponse.from(budget);
    }

    /** Supprime un budget encore en brouillon ou rejete (jamais un budget approuve, en execution ou cloture). */
    @PreAuthorize(ECRITURE)
    @Transactional
    public void supprimer(Long id) {
        Budget budget = charger(id);
        if (budget.getStatut() != StatutBudget.BROUILLON && budget.getStatut() != StatutBudget.REJETE) {
            throw new TransitionInvalideException("Seul un budget en brouillon ou rejeté peut être supprimé (état actuel : "
                + budget.getStatut() + ").");
        }
        budgetRepository.delete(budget);
        log.info("Budget supprime [{}]", budget.getReference());
    }

    // ---------------------------------------------------------------------
    // Circuit
    // ---------------------------------------------------------------------

    @PreAuthorize(ECRITURE)
    @Transactional
    public BudgetResponse soumettre(Long id) {
        Budget budget = charger(id);
        exigerEtat(budget, StatutBudget.BROUILLON, "soumettre");
        BigDecimal total = budget.getLignes().stream().map(LigneBudget::getMontantPrevu).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (budget.getLignes().isEmpty() || total.signum() <= 0) {
            throw new IllegalArgumentException("Un budget sans aucun montant prévu ne peut pas être soumis.");
        }
        budget.setStatut(StatutBudget.SOUMIS);
        budget.setDateSoumission(Instant.now());
        log.info("Budget {} soumis pour approbation", budget.getReference());
        return BudgetResponse.from(budget);
    }

    @PreAuthorize(APPROBATION)
    @Transactional
    public BudgetResponse approuver(Long id, ActionWorkflowRequest action) {
        Budget budget = charger(id);
        exigerEtat(budget, StatutBudget.SOUMIS, "approuver");
        budget.setStatut(StatutBudget.APPROUVE);
        budget.setApprouvePar(currentUser.requireUser());
        budget.setDateApprobation(Instant.now());
        if (action != null && StringUtils.hasText(action.commentaire())) {
            budget.setObservation(action.commentaire().trim());
        }
        log.info("Budget {} approuve", budget.getReference());
        return BudgetResponse.from(budget);
    }

    @PreAuthorize(APPROBATION)
    @Transactional
    public BudgetResponse rejeter(Long id, ActionWorkflowRequest action) {
        Budget budget = charger(id);
        exigerEtat(budget, StatutBudget.SOUMIS, "rejeter");
        if (action == null || !StringUtils.hasText(action.commentaire())) {
            throw new IllegalArgumentException("Un motif de rejet est obligatoire");
        }
        budget.setStatut(StatutBudget.REJETE);
        budget.setApprouvePar(currentUser.requireUser());
        budget.setMotifRejet(action.commentaire().trim());
        log.info("Budget {} rejete", budget.getReference());
        return BudgetResponse.from(budget);
    }

    /** Un budget rejete revient en brouillon pour etre corrige puis soumis a nouveau (le motif reste visible). */
    @PreAuthorize(ECRITURE)
    @Transactional
    public BudgetResponse reprendre(Long id) {
        Budget budget = charger(id);
        exigerEtat(budget, StatutBudget.REJETE, "reprendre");
        budget.setStatut(StatutBudget.BROUILLON);
        budget.setApprouvePar(null);
        log.info("Budget {} repris en brouillon", budget.getReference());
        return BudgetResponse.from(budget);
    }

    /**
     * Met un budget approuve en execution : il controle alors les depenses de son exercice. Un seul budget en
     * execution par exercice ; une revision approuvee remplace le budget qu'elle revise (qui passe REMPLACE).
     */
    @PreAuthorize(ECRITURE)
    @Transactional
    public BudgetResponse demarrerExecution(Long id) {
        Budget budget = charger(id);
        exigerEtat(budget, StatutBudget.APPROUVE, "mettre en exécution");
        budgetRepository.findFirstByExerciceAndStatut(budget.getExercice(), StatutBudget.EN_EXECUTION).ifPresent(enCours -> {
            if (budget.getRevisionDe() == null || !budget.getRevisionDe().getId().equals(enCours.getId())) {
                throw new TransitionInvalideException("Le budget " + enCours.getReference() + " est déjà en exécution pour "
                    + budget.getExercice() + " : pour le modifier, faites-en une révision.");
            }
            enCours.setStatut(StatutBudget.REMPLACE);
            enCours.setDateCloture(Instant.now());
            budgetRepository.saveAndFlush(enCours);   // libere l'index « un seul en execution » avant la bascule
            log.info("Budget {} remplace par sa revision {}", enCours.getReference(), budget.getReference());
        });
        budget.setStatut(StatutBudget.EN_EXECUTION);
        budget.setDateExecution(Instant.now());
        log.info("Budget {} passe en execution", budget.getReference());
        return BudgetResponse.from(budget);
    }

    /**
     * Revision (budget rectificatif) d'un budget en execution : copie en brouillon, a modifier puis faire
     * approuver. Le budget en execution continue de controler les depenses jusqu'a la mise en execution de
     * la revision.
     */
    @PreAuthorize(ECRITURE)
    @Transactional
    public BudgetResponse reviser(Long id) {
        Budget budget = charger(id);
        exigerEtat(budget, StatutBudget.EN_EXECUTION, "réviser");
        boolean revisionEnCours = budgetRepository.findAllByOrderByExerciceDescDateCreationDesc().stream()
            .anyMatch(b -> b.getRevisionDe() != null && b.getRevisionDe().getId().equals(id) && REVISION_EN_COURS.contains(b.getStatut()));
        if (revisionEnCours) {
            throw new TransitionInvalideException("Une révision de ce budget est déjà en cours : terminez-la ou supprimez-la d'abord.");
        }
        int numero = budget.getNumeroRevision() + 1;
        Budget revision = Budget.builder()
            .reference(referenceGenerator.pourBudget())
            .intitule(budget.getIntitule())
            .exercice(budget.getExercice())
            .statut(StatutBudget.BROUILLON)
            .elaborePar(currentUser.requireUser())
            .observation("Révision n° " + numero + " du budget " + budget.getReference())
            .revisionDe(budget)
            .numeroRevision(numero)
            .build();
        for (LigneBudget l : budget.getLignes()) {
            revision.addLigne(LigneBudget.builder()
                .compte(l.getCompte())
                .montantPrevu(l.getMontantPrevu())
                .mois(new java.util.TreeMap<>(l.getMois()))
                .commentaire(l.getCommentaire())
                .build());
        }
        revision = budgetRepository.save(revision);
        log.info("Revision {} creee pour le budget {}", revision.getReference(), budget.getReference());
        return BudgetResponse.from(revision);
    }

    /** Cloture d'un budget en execution, une fois son exercice termine : il reste consultable. */
    @PreAuthorize(ECRITURE)
    @Transactional
    public BudgetResponse cloturer(Long id) {
        Budget budget = charger(id);
        exigerEtat(budget, StatutBudget.EN_EXECUTION, "clôturer");
        if (LocalDate.now().getYear() <= budget.getExercice()) {
            throw new TransitionInvalideException("L'exercice " + budget.getExercice()
                + " n'est pas terminé : le budget le contrôle jusqu'au 31 décembre.");
        }
        budget.setStatut(StatutBudget.CLOTURE);
        budget.setDateCloture(Instant.now());
        log.info("Budget {} cloture", budget.getReference());
        return BudgetResponse.from(budget);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    Budget charger(Long id) {
        return budgetRepository.findWithLignesById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Budget", id));
    }

    /** Controle et pose les lignes : comptes budgetables, ventilation de douze mois, aucun recouvrement. */
    private void appliquerLignes(Budget budget, List<LigneBudgetRequest> lignes) {
        Map<String, CompteOHADA> comptes = new LinkedHashMap<>();
        for (LigneBudgetRequest l : lignes) {
            String numero = l.compteNumero().trim();
            CompteOHADA compte = compteRepository.findByNumero(numero)
                .orElseThrow(() -> RessourceIntrouvableException.of("CompteOHADA", numero));
            if (!MoteurBudgetaire.estBudgetable(compte.getNumero(), compte.getClasse())) {
                throw new IllegalArgumentException("Le compte " + numero + " (" + compte.getLibelle() + ") ne peut pas porter de ligne "
                    + "budgétaire : seuls les comptes de charges (6), de produits (7), d'investissement (2, hors amortissements "
                    + "et dépréciations 28/29) et H.A.O. (8) se budgètent, à partir de deux chiffres.");
            }
            if (comptes.put(numero, compte) != null) {
                throw new IllegalArgumentException("Le compte " + numero + " figure deux fois dans le budget.");
            }
        }
        List<String[]> conflits = MoteurBudgetaire.chevauchements(new ArrayList<>(comptes.keySet()));
        if (!conflits.isEmpty()) {
            String detail = conflits.stream().map(c -> c[0] + " couvre déjà " + c[1]).collect(Collectors.joining(", "));
            throw new IllegalArgumentException("Des lignes se recouvrent (" + detail + ") : une dépense serait comptée deux fois. "
                + "Gardez soit le compte général, soit ses sous-comptes.");
        }
        for (LigneBudgetRequest l : lignes) {
            CompteOHADA compte = comptes.get(l.compteNumero().trim());
            BigDecimal[] mensuel = mensuel(l);
            Map<Integer, BigDecimal> mois = new java.util.TreeMap<>();
            for (int i = 0; i < MoteurBudgetaire.MOIS; i++) {
                mois.put(i + 1, mensuel[i]);
            }
            budget.addLigne(LigneBudget.builder()
                .compte(compte)
                .mois(mois)
                .montantPrevu(MoteurBudgetaire.total(mensuel))
                .commentaire(vide(l.commentaire()))
                .build());
        }
    }

    private BigDecimal[] mensuel(LigneBudgetRequest l) {
        if (l.mensuel() != null) {
            BigDecimal[] m = new BigDecimal[MoteurBudgetaire.MOIS];
            for (int i = 0; i < MoteurBudgetaire.MOIS; i++) {
                BigDecimal v = l.mensuel().get(i);
                if (v == null || v.signum() < 0) {
                    throw new IllegalArgumentException("Compte " + l.compteNumero() + " : montant du mois " + (i + 1) + " invalide.");
                }
                m[i] = v.setScale(2, java.math.RoundingMode.HALF_UP);
            }
            return m;
        }
        if (l.montantPrevu() == null) {
            throw new IllegalArgumentException("Compte " + l.compteNumero() + " : indiquez la ventilation mensuelle ou le montant annuel.");
        }
        return MoteurBudgetaire.repartirUniformement(l.montantPrevu());
    }

    private void exigerEtat(Budget budget, StatutBudget attendu, String operation) {
        if (budget.getStatut() != attendu) {
            throw new TransitionInvalideException(
                "Impossible de " + operation + " : le budget doit être " + attendu + " (état actuel : " + budget.getStatut() + ")");
        }
    }

    private static String vide(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    static String normaliser(String s) {
        if (s == null) {
            return "";
        }
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).trim();
    }
}
