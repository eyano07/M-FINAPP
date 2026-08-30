package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Budget;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.LigneBudget;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.StatutBudget;
import com.mbsc.finapp.dto.budget.BudgetRequest;
import com.mbsc.finapp.dto.budget.BudgetResponse;
import com.mbsc.finapp.dto.budget.LigneBudgetRequest;
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

import java.util.List;

/**
 * Cycle de vie des budgets previsionnels (voir {@link StatutBudget}).
 *
 * <pre>
 *   BROUILLON --soumettre(DFIN)--> SOUMIS
 *   SOUMIS    --approuver(DA)----> APPROUVE
 *   SOUMIS    --rejeter(DA)------> REJETE
 *   APPROUVE  --demarrer(DFIN)---> EN_EXECUTION
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class BudgetService {

    private static final Logger log = LoggerFactory.getLogger(BudgetService.class);

    private final BudgetRepository budgetRepository;
    private final CompteOHADARepository compteRepository;
    private final ReferenceGenerator referenceGenerator;
    private final CurrentUserProvider currentUser;

    // ---------------------------------------------------------------------
    // Lecture
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<BudgetResponse> lister() {
        return budgetRepository.findAllByOrderByExerciceDescDateCreationDesc().stream()
            .map(BudgetResponse::from)
            .toList();
    }

    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public BudgetResponse consulter(Long id) {
        return BudgetResponse.from(charger(id));
    }

    // ---------------------------------------------------------------------
    // Creation / modification (BROUILLON)
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('DFIN', 'ADMIN')")
    @Transactional
    public BudgetResponse creer(BudgetRequest req) {
        User auteur = currentUser.requireUser();

        Budget budget = Budget.builder()
            .intitule(req.intitule())
            .exercice(req.exercice())
            .statut(StatutBudget.BROUILLON)
            .elaborePar(auteur)
            .observation(req.observation())
            .build();

        appliquerLignes(budget, req.lignes());
        budget = budgetRepository.save(budget);

        log.info("Budget cree [intitule={}, exercice={}, par={}]",
            budget.getIntitule(), budget.getExercice(), auteur.getEmail());
        return BudgetResponse.from(budget);
    }

    @PreAuthorize("hasAnyRole('DFIN', 'ADMIN')")
    @Transactional
    public BudgetResponse modifier(Long id, BudgetRequest req) {
        Budget budget = charger(id);
        exigerEtat(budget, StatutBudget.BROUILLON, "modifier");

        budget.setIntitule(req.intitule());
        budget.setExercice(req.exercice());
        budget.setObservation(req.observation());
        budget.getLignes().clear();
        appliquerLignes(budget, req.lignes());

        log.info("Budget modifie [id={}]", id);
        return BudgetResponse.from(budget);
    }

    // ---------------------------------------------------------------------
    // Transitions
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('DFIN', 'ADMIN')")
    @Transactional
    public BudgetResponse soumettre(Long id) {
        Budget budget = charger(id);
        exigerEtat(budget, StatutBudget.BROUILLON, "soumettre");
        budget.setStatut(StatutBudget.SOUMIS);
        log.info("Budget {} soumis pour approbation", id);
        return BudgetResponse.from(budget);
    }

    @PreAuthorize("hasAnyRole('DA', 'ADMIN')")
    @Transactional
    public BudgetResponse approuver(Long id, ActionWorkflowRequest action) {
        Budget budget = charger(id);
        exigerEtat(budget, StatutBudget.SOUMIS, "approuver");
        budget.setStatut(StatutBudget.APPROUVE);
        budget.setApprouvePar(currentUser.requireUser());
        appliquerObservation(budget, action);
        log.info("Budget {} approuve par le DA", id);
        return BudgetResponse.from(budget);
    }

    @PreAuthorize("hasAnyRole('DA', 'ADMIN')")
    @Transactional
    public BudgetResponse rejeter(Long id, ActionWorkflowRequest action) {
        Budget budget = charger(id);
        exigerEtat(budget, StatutBudget.SOUMIS, "rejeter");
        if (action == null || !StringUtils.hasText(action.commentaire())) {
            throw new IllegalArgumentException("Un motif de rejet est obligatoire");
        }
        budget.setStatut(StatutBudget.REJETE);
        budget.setApprouvePar(currentUser.requireUser());
        appliquerObservation(budget, action);
        log.info("Budget {} rejete par le DA", id);
        return BudgetResponse.from(budget);
    }

    @PreAuthorize("hasAnyRole('DFIN', 'ADMIN')")
    @Transactional
    public BudgetResponse demarrerExecution(Long id) {
        Budget budget = charger(id);
        exigerEtat(budget, StatutBudget.APPROUVE, "mettre en execution");
        budget.setStatut(StatutBudget.EN_EXECUTION);
        log.info("Budget {} passe en execution", id);
        return BudgetResponse.from(budget);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private Budget charger(Long id) {
        return budgetRepository.findWithLignesById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Budget", id));
    }

    private void appliquerLignes(Budget budget, List<LigneBudgetRequest> lignes) {
        for (LigneBudgetRequest l : lignes) {
            CompteOHADA compte = compteRepository.findByNumero(l.compteNumero())
                .orElseThrow(() -> RessourceIntrouvableException.of("CompteOHADA", l.compteNumero()));
            budget.addLigne(LigneBudget.builder()
                .compte(compte)
                .montantPrevu(l.montantPrevu())
                .build());
        }
    }

    private void appliquerObservation(Budget budget, ActionWorkflowRequest action) {
        if (action != null && StringUtils.hasText(action.commentaire())) {
            budget.setObservation(action.commentaire());
        }
    }

    private void exigerEtat(Budget budget, StatutBudget attendu, String operation) {
        if (budget.getStatut() != attendu) {
            throw new TransitionInvalideException(
                "Impossible de " + operation + " : le budget doit etre " + attendu
                    + " (etat actuel : " + budget.getStatut() + ")");
        }
    }
}
