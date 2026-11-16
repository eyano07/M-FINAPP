package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Budget;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.LigneBudget;
import com.mbsc.finapp.domain.ServiceBudgetaire;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.DureeBudget;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.StatutBudget;
import com.mbsc.finapp.domain.enums.TypeNotification;
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
import com.mbsc.finapp.repository.ServiceBudgetaireRepository;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Budgets previsionnels ventiles par mois (voir {@link StatutBudget} pour le circuit), sur l'exercice entier ou
 * sur une periode plus courte (semestre, trimestre, mois : voir {@link DureeBudget}), organises ou non par service.
 *
 * <p>Regles : comptes budgetables (classes 2, 6, 7, 8 du plan SYSCOHADA, hors 28/29) ; dans un service (ou dans
 * les lignes generales), un compte une seule fois ; un meme compte peut figurer dans plusieurs services (il se
 * cumule dans le budget consolide) mais un compte et l'un de ses sous-comptes ne coexistent jamais ; seules les
 * depenses (charges, investissements) se rattachent a un service ; montants mensuels positifs ou nuls, zero hors
 * de la periode ; un seul budget en execution sur un meme mois (une revision remplace le budget qu'elle revise
 * quand elle est mise en execution) ; un budget n'est modifiable qu'en brouillon.</p>
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
    /** Budgets mis en execution (meme remplaces ou clotures depuis) : leurs depassements ont un sens. */
    private static final EnumSet<StatutBudget> EXECUTES =
        EnumSet.of(StatutBudget.EN_EXECUTION, StatutBudget.REMPLACE, StatutBudget.CLOTURE);

    private final BudgetRepository budgetRepository;
    private final CompteOHADARepository compteRepository;
    private final ReferenceGenerator referenceGenerator;
    private final CurrentUserProvider currentUser;
    private final SuiviBudgetaireService suivi;
    private final ServiceBudgetaireRepository serviceRepository;
    /** Circuit du budget : le DA est prevenu d'une soumission, le DFIN d'une approbation ou d'un rejet, etc. */
    private final NotificationService notifications;

    // ---------------------------------------------------------------------
    // Lecture
    // ---------------------------------------------------------------------

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public List<BudgetResumeResponse> lister() {
        List<Budget> budgets = budgetRepository.findAllByOrderByExerciceDescDateCreationDesc();
        // Une seule lecture du grand livre (et des engagements) par exercice, quel que soit le nombre de budgets.
        Map<Integer, SuiviBudgetaireService.MouvementsExercice> mouvements = new HashMap<>();
        Map<Integer, List<SuiviBudgetaireService.Engagement>> engagements = new HashMap<>();
        Map<Long, ServiceBudgetaire> catalogue = suivi.catalogueServices();
        List<BudgetResumeResponse> resultat = new ArrayList<>();
        for (Budget b : budgets) {
            SuiviBudgetaireService.MouvementsExercice mvts =
                mouvements.computeIfAbsent(b.getExercice(), suivi::mouvementsDetailles);
            boolean execute = EXECUTES.contains(b.getStatut());
            List<SuiviBudgetaireService.Engagement> engages = execute
                ? engagements.computeIfAbsent(b.getExercice(), e -> suivi.engagementsDeLExercice(e, null, new ArrayList<>()))
                : List.of();
            BudgetResponse detail = BudgetResponse.from(b);
            SuiviBudgetaireService.SyntheseExecution s = suivi.synthese(b, mvts, engages, catalogue, execute);
            BigDecimal prevuDepenses = detail.totalCharges().add(detail.totalInvestissements());
            BigDecimal realiseDepenses = s.realiseCharges().add(s.realiseInvestissements());
            long nombreServices = b.getLignes().stream().map(LigneBudget::getService).filter(Objects::nonNull)
                .map(ServiceBudgetaire::getId).distinct().count();
            resultat.add(new BudgetResumeResponse(b.getId(), b.getReference(), b.getIntitule(), b.getExercice(),
                b.getStatut(), b.getNumeroRevision(), detail.elaboreParNom(), detail.approuveParNom(), b.getDateCreation(),
                detail.totalProduits(), detail.totalCharges(), detail.totalInvestissements(),
                s.realiseProduits(), s.realiseCharges(), s.realiseInvestissements(), prevuDepenses, realiseDepenses,
                MoteurBudgetaire.taux(realiseDepenses, prevuDepenses), b.getLignes().size(),
                b.getDuree(), b.getPeriode(), b.libellePeriode(), b.moisDebut(), b.moisFin(), (int) nombreServices,
                s.montantDepassement(), s.nombreDepassements(), s.servicesEnDepassement()));
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
     * Ventilation d'un montant sur les mois d'une periode (l'exercice entier par defaut) : parts egales, ou
     * saisonnalite reelle (realise mensuel de l'annee precedente sur le compte et ses sous-comptes, sur les memes
     * mois) ; parts egales a defaut d'historique.
     */
    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public RepartitionResponse repartir(RepartitionRequest req) {
        int debut = req.moisDebut() == null ? 1 : req.moisDebut();
        int fin = req.moisFin() == null ? MoteurBudgetaire.MOIS : req.moisFin();
        if (debut > fin) {
            throw new IllegalArgumentException("Période invalide : le premier mois suit le dernier.");
        }
        boolean annee = debut == 1 && fin == MoteurBudgetaire.MOIS;
        String surLesMois = annee ? "sur les douze mois" : debut == fin ? "sur le mois de la période" : "sur les mois de la période";
        if ("SAISONNALITE".equalsIgnoreCase(req.mode()) && StringUtils.hasText(req.compteNumero()) && req.exercice() != null) {
            BigDecimal[] profil = realiseMensuelDuCompte(req.compteNumero().trim(), req.exercice() - 1);
            BigDecimal surLaPeriode = BigDecimal.ZERO;
            for (int m = debut; m <= fin; m++) {
                surLaPeriode = surLaPeriode.add(profil[m - 1]);
            }
            if (surLaPeriode.signum() > 0) {
                return new RepartitionResponse(List.of(MoteurBudgetaire.repartirSelonProfil(req.montantAnnuel(), profil, debut, fin)),
                    "SAISONNALITE", "Réparti selon le réalisé mensuel de " + (req.exercice() - 1) + " sur le compte "
                        + req.compteNumero() + (annee ? "." : ", mêmes mois."));
            }
            return new RepartitionResponse(List.of(MoteurBudgetaire.repartirUniformement(req.montantAnnuel(), debut, fin)),
                "UNIFORME", "Aucun réalisé en " + (req.exercice() - 1) + " sur ce compte" + (annee ? "" : " pour ces mois")
                    + " : réparti à parts égales.");
        }
        return new RepartitionResponse(List.of(MoteurBudgetaire.repartirUniformement(req.montantAnnuel(), debut, fin)),
            "UNIFORME", "Réparti à parts égales " + surLesMois + ".");
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
        DureeBudget duree = duree(req);
        int periode = periode(req, duree);
        Budget budget = Budget.builder()
            .reference(referenceGenerator.pourBudget())
            .intitule(req.intitule().trim())
            .exercice(req.exercice())
            .duree(duree)
            .periode(periode)
            .statut(StatutBudget.BROUILLON)
            .elaborePar(auteur)
            .observation(vide(req.observation()))
            .build();
        appliquerLignes(budget, req.lignes(), Set.of());
        budget = budgetRepository.save(budget);
        log.info("Budget cree [{} {}, periode={}, lignes={}, par={}]", budget.getReference(), budget.getIntitule(),
            budget.libellePeriode(), budget.getLignes().size(), auteur.getEmail());
        return BudgetResponse.from(budget);
    }

    @PreAuthorize(ECRITURE)
    @Transactional
    public BudgetResponse modifier(Long id, BudgetRequest req) {
        Budget budget = charger(id);
        exigerEtat(budget, StatutBudget.BROUILLON, "modifier");
        DureeBudget duree = duree(req);
        int periode = periode(req, duree);
        Budget revise = budget.getRevisionDe();
        if (revise != null && (!revise.getExercice().equals(req.exercice()) || revise.getDuree() != duree
                || revise.getPeriode() != periode)) {
            throw new IllegalArgumentException("Une révision porte sur la même période que le budget révisé ("
                + revise.libellePeriode() + ").");
        }
        // Services deja presents dans le budget : une ligne existante garde son service meme s'il a ete desactive depuis.
        Set<Long> servicesDejaPresents = new HashSet<>();
        budget.getLignes().stream().map(LigneBudget::getService).filter(Objects::nonNull)
            .forEach(s -> servicesDejaPresents.add(s.getId()));
        budget.setIntitule(req.intitule().trim());
        budget.setExercice(req.exercice());
        budget.setDuree(duree);
        budget.setPeriode(periode);
        budget.setObservation(vide(req.observation()));
        budget.getLignes().clear();
        budgetRepository.flush();   // les anciennes lignes partent avant l'insertion des nouvelles
        appliquerLignes(budget, req.lignes(), servicesDejaPresents);
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
        BudgetResponse reponse = BudgetResponse.from(budget);
        User auteur = currentUser.requireUser();
        notifications.notifierBudget(List.of(RoleType.DA), null, auteur, TypeNotification.BUDGET_SOUMIS, "Budget à approuver",
            designation(budget) + revision(budget) + " : " + montant(reponse.totalCharges().add(reponse.totalInvestissements()))
                + " USD de dépenses prévues" + (reponse.totalProduits().signum() > 0 ? ", " + montant(reponse.totalProduits()) + " USD de recettes" : "")
                + (budget.parService() ? ", réparti par service" : "") + ". Soumis par " + nom(auteur) + ".",
            budget);
        return reponse;
    }

    @PreAuthorize(APPROBATION)
    @Transactional
    public BudgetResponse approuver(Long id, ActionWorkflowRequest action) {
        Budget budget = charger(id);
        exigerEtat(budget, StatutBudget.SOUMIS, "approuver");
        User approbateur = currentUser.requireUser();
        budget.setStatut(StatutBudget.APPROUVE);
        budget.setApprouvePar(approbateur);
        budget.setDateApprobation(Instant.now());
        boolean observation = action != null && StringUtils.hasText(action.commentaire());
        if (observation) {
            budget.setObservation(action.commentaire().trim());
        }
        log.info("Budget {} approuve", budget.getReference());
        notifications.notifierBudget(List.of(RoleType.DFIN), budget.getElaborePar(), approbateur, TypeNotification.BUDGET_APPROUVE,
            "Budget approuvé", designation(budget) + " approuvé par " + nom(approbateur) + " : il peut être mis en exécution."
                + (observation ? " Observation : " + action.commentaire().trim() : ""),
            budget);
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
        User approbateur = currentUser.requireUser();
        budget.setStatut(StatutBudget.REJETE);
        budget.setApprouvePar(approbateur);
        budget.setMotifRejet(action.commentaire().trim());
        log.info("Budget {} rejete", budget.getReference());
        notifications.notifierBudget(List.of(RoleType.DFIN), budget.getElaborePar(), approbateur, TypeNotification.BUDGET_REJETE,
            "Budget rejeté", designation(budget) + " rejeté par " + nom(approbateur) + " : " + action.commentaire().trim(),
            budget);
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
     * Met un budget approuve en execution : il controle alors les depenses des mois de sa periode. Un seul budget en
     * execution sur un meme mois : un budget de decembre peut suivre un budget du 3e trimestre, pas chevaucher un budget
     * annuel. Une revision approuvee remplace le budget qu'elle revise (qui passe REMPLACE).
     */
    @PreAuthorize(ECRITURE)
    @Transactional
    public BudgetResponse demarrerExecution(Long id) {
        Budget budget = charger(id);
        exigerEtat(budget, StatutBudget.APPROUVE, "mettre en exécution");
        Long revise = budget.getRevisionDe() == null ? null : budget.getRevisionDe().getId();
        String remplace = null;
        for (Budget enCours : budgetRepository.findByExerciceAndStatut(budget.getExercice(), StatutBudget.EN_EXECUTION)) {
            if (enCours.getId().equals(budget.getId()) || !enCours.chevauche(budget)) {
                continue;
            }
            if (!enCours.getId().equals(revise)) {
                throw new TransitionInvalideException("Le budget " + enCours.getReference() + " (" + enCours.libellePeriode()
                    + ") est déjà en exécution sur une partie de cette période (" + budget.libellePeriode() + ") : "
                    + "pour le modifier, faites-en une révision ; sinon choisissez une période qui ne le chevauche pas.");
            }
            enCours.setStatut(StatutBudget.REMPLACE);
            enCours.setDateCloture(Instant.now());
            budgetRepository.saveAndFlush(enCours);   // libere la contrainte « un seul en execution par mois » avant la bascule
            remplace = enCours.getReference();
            log.info("Budget {} remplace par sa revision {}", enCours.getReference(), budget.getReference());
        }
        budget.setStatut(StatutBudget.EN_EXECUTION);
        budget.setDateExecution(Instant.now());
        log.info("Budget {} passe en execution ({})", budget.getReference(), budget.libellePeriode());
        notifications.notifierBudget(List.of(RoleType.DA, RoleType.DG), null, currentUser.requireUser(),
            TypeNotification.BUDGET_EN_EXECUTION, "Budget en exécution",
            designation(budget) + " est en exécution : il contrôle désormais les notes de frais de « " + budget.libellePeriode() + " »"
                + (remplace == null ? "." : " et remplace le budget " + remplace + "."),
            budget);
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
            .duree(budget.getDuree())
            .periode(budget.getPeriode())
            .statut(StatutBudget.BROUILLON)
            .elaborePar(currentUser.requireUser())
            .observation("Révision n° " + numero + " du budget " + budget.getReference())
            .revisionDe(budget)
            .numeroRevision(numero)
            .build();
        for (LigneBudget l : budget.getLignes()) {
            revision.addLigne(LigneBudget.builder()
                .compte(l.getCompte())
                .service(l.getService())
                .montantPrevu(l.getMontantPrevu())
                .mois(new java.util.TreeMap<>(l.getMois()))
                .commentaire(l.getCommentaire())
                .build());
        }
        revision = budgetRepository.save(revision);
        log.info("Revision {} creee pour le budget {}", revision.getReference(), budget.getReference());
        return BudgetResponse.from(revision);
    }

    /** Cloture d'un budget en execution, une fois sa periode terminee : il reste consultable. */
    @PreAuthorize(ECRITURE)
    @Transactional
    public BudgetResponse cloturer(Long id) {
        Budget budget = charger(id);
        exigerEtat(budget, StatutBudget.EN_EXECUTION, "clôturer");
        if (!LocalDate.now().isAfter(budget.dernierJour())) {
            throw new TransitionInvalideException("La période « " + budget.libellePeriode() + " » n'est pas terminée : "
                + "le budget la contrôle jusqu'au " + budget.dernierJour().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ".");
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

    /**
     * Controle et pose les lignes : comptes budgetables, services existants (actifs pour un nouveau rattachement),
     * recettes jamais rattachees a un service, un compte une seule fois par service, aucun recouvrement dans le budget
     * consolide, montants nuls hors de la periode.
     *
     * @param servicesAdmis services deja presents dans le budget, admis meme s'ils ont ete desactives depuis
     */
    private void appliquerLignes(Budget budget, List<LigneBudgetRequest> lignes, Set<Long> servicesAdmis) {
        Map<String, CompteOHADA> comptes = new LinkedHashMap<>();
        Map<Long, ServiceBudgetaire> services = new HashMap<>();
        Set<String> clesVues = new HashSet<>();
        for (LigneBudgetRequest l : lignes) {
            String numero = l.compteNumero().trim();
            CompteOHADA compte = comptes.get(numero);
            if (compte == null) {
                compte = compteRepository.findByNumero(numero)
                    .orElseThrow(() -> RessourceIntrouvableException.of("CompteOHADA", numero));
                if (!MoteurBudgetaire.estBudgetable(compte.getNumero(), compte.getClasse())) {
                    throw new IllegalArgumentException("Le compte " + numero + " (" + compte.getLibelle() + ") ne peut pas porter de ligne "
                        + "budgétaire : seuls les comptes de charges (6), de produits (7), d'investissement (2, hors amortissements "
                        + "et dépréciations 28/29) et H.A.O. (8) se budgètent, à partir de deux chiffres.");
                }
                comptes.put(numero, compte);
            }
            ServiceBudgetaire service = null;
            if (l.serviceId() != null) {
                service = services.computeIfAbsent(l.serviceId(), sid -> serviceRepository.findById(sid)
                    .orElseThrow(() -> RessourceIntrouvableException.of("ServiceBudgetaire", sid)));
                if (!service.isActif() && !servicesAdmis.contains(service.getId())) {
                    throw new IllegalArgumentException("Le service « " + service.getNom() + " » est désactivé : il ne reçoit plus de "
                        + "nouvelle ligne budgétaire.");
                }
                if (MoteurBudgetaire.section(numero, compte.getType()) == MoteurBudgetaire.Section.PRODUITS) {
                    throw new IllegalArgumentException("Le compte " + numero + " (" + compte.getLibelle() + ") est une recette : les "
                        + "recettes ne se rattachent pas à un service, seules les dépenses se suivent par service. "
                        + "Mettez cette ligne dans les lignes générales.");
                }
            }
            if (!clesVues.add((service == null ? "" : service.getId()) + "|" + numero)) {
                throw new IllegalArgumentException("Le compte " + numero + " figure deux fois "
                    + (service == null ? "dans les lignes générales" : "dans le service « " + service.getNom() + " »") + ".");
            }
        }
        List<String[]> conflits = ImputationBudgetaire.chevauchements(comptes.keySet());
        if (!conflits.isEmpty()) {
            String detail = conflits.stream().map(c -> c[0] + " couvre déjà " + c[1]).collect(Collectors.joining(", "));
            throw new IllegalArgumentException("Des lignes se recouvrent (" + detail + ") : une dépense serait comptée deux fois. "
                + "Gardez soit le compte général, soit ses sous-comptes — le même dans tous les services.");
        }
        int debut = budget.moisDebut();
        int fin = budget.moisFin();
        for (LigneBudgetRequest l : lignes) {
            CompteOHADA compte = comptes.get(l.compteNumero().trim());
            BigDecimal[] mensuel = mensuel(l, debut, fin);
            List<Integer> hors = MoteurBudgetaire.moisHorsPeriode(mensuel, debut, fin);
            if (!hors.isEmpty()) {
                throw new IllegalArgumentException("Compte " + compte.getNumero() + " : le budget couvre « " + budget.libellePeriode()
                    + " », il n'a pas de montant pour " + nomsDesMois(hors) + ".");
            }
            Map<Integer, BigDecimal> mois = new java.util.TreeMap<>();
            for (int i = 0; i < MoteurBudgetaire.MOIS; i++) {
                mois.put(i + 1, mensuel[i]);
            }
            budget.addLigne(LigneBudget.builder()
                .compte(compte)
                .service(l.serviceId() == null ? null : services.get(l.serviceId()))
                .mois(mois)
                .montantPrevu(MoteurBudgetaire.total(mensuel))
                .commentaire(vide(l.commentaire()))
                .build());
        }
    }

    private BigDecimal[] mensuel(LigneBudgetRequest l, int debut, int fin) {
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
            throw new IllegalArgumentException("Compte " + l.compteNumero() + " : indiquez la ventilation mensuelle ou le montant de la période.");
        }
        return MoteurBudgetaire.repartirUniformement(l.montantPrevu(), debut, fin);
    }

    private static DureeBudget duree(BudgetRequest req) {
        return req.duree() == null ? DureeBudget.ANNUEL : req.duree();
    }

    private static int periode(BudgetRequest req, DureeBudget duree) {
        int periode = req.periode() == null ? 1 : req.periode();
        if (!duree.periodeValide(periode)) {
            throw new IllegalArgumentException("Période " + periode + " invalide pour un budget " + duree.nom().toLowerCase(Locale.FRANCE)
                + " : choisissez une période de 1 à " + duree.nombrePeriodes() + ".");
        }
        return periode;
    }

    private static String nomsDesMois(List<Integer> mois) {
        return mois.stream()
            .map(m -> java.time.Month.of(m).getDisplayName(java.time.format.TextStyle.FULL, Locale.FRANCE))
            .collect(Collectors.joining(", "));
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

    /** « BUD-2026-000002 — Budget de décembre (Décembre 2026) », pour les notifications. */
    private static String designation(Budget b) {
        return b.getReference() + " — " + b.getIntitule() + " (" + b.libellePeriode() + ")";
    }

    /** « , révision n° 1 de BUD-2026-000001 », vide pour une version initiale. */
    private static String revision(Budget b) {
        return b.getRevisionDe() == null ? "" : ", révision n° " + b.getNumeroRevision() + " de " + b.getRevisionDe().getReference();
    }

    private static String montant(BigDecimal v) {
        return BudgetPdfService.montant(v);
    }

    private static String nom(User u) {
        if (u == null) {
            return "—";
        }
        String n = ((u.getPrenom() == null ? "" : u.getPrenom()) + " " + (u.getNom() == null ? "" : u.getNom())).trim();
        return n.isEmpty() ? u.getEmail() : n;
    }

    static String normaliser(String s) {
        if (s == null) {
            return "";
        }
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).trim();
    }
}
