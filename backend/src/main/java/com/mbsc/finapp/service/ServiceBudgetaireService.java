package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Budget;
import com.mbsc.finapp.domain.LigneBudget;
import com.mbsc.finapp.domain.ServiceBudgetaire;
import com.mbsc.finapp.domain.enums.StatutBudget;
import com.mbsc.finapp.dto.budget.ExecutionServiceResponse;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse.SuiviService;
import com.mbsc.finapp.repository.BudgetRepository;
import com.mbsc.finapp.dto.budget.ServiceBudgetaireRequest;
import com.mbsc.finapp.dto.budget.ServiceBudgetaireResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.ServiceBudgetaireRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Services (departements) de l'entreprise, chacun avec le nom de son responsable : les lignes de depenses d'un budget
 * et les notes de frais s'y rattachent (voir {@link ImputationBudgetaire}). Le DFIN (et l'administrateur) les cree et
 * les gere ; tout utilisateur connecte les lit, pour rattacher une note de frais a son service.
 */
@Service
@RequiredArgsConstructor
public class ServiceBudgetaireService {

    private static final Logger log = LoggerFactory.getLogger(ServiceBudgetaireService.class);

    static final String GESTION = "hasAnyRole('DFIN', 'ADMIN')";

    private final ServiceBudgetaireRepository repository;
    private final CurrentUserProvider currentUser;
    private final BudgetRepository budgetRepository;
    private final SuiviBudgetaireService suivi;

    /** Tous les services, actifs d'abord, avec leur usage (lignes de budget, notes de frais). */
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public List<ServiceBudgetaireResponse> lister() {
        Map<Long, Long> lignes = compter(repository.compterLignesParService());
        Map<Long, Long> notes = compter(repository.compterNotesParService());
        return repository.findAllByOrderByActifDescNomAsc().stream()
            .map(s -> ServiceBudgetaireResponse.from(s, lignes.getOrDefault(s.getId(), 0L), notes.getOrDefault(s.getId(), 0L)))
            .toList();
    }

    /**
     * Execution de chaque service dans son budget de reference : le budget en execution couvrant aujourd'hui ou il a des
     * lignes ; a defaut, un autre budget en execution, puis approuve, soumis, en brouillon et enfin cloture (le plus
     * recent d'abord). Un service sans aucune ligne de budget n'a pas d'entree. Lecteurs du module budget seulement.
     */
    @PreAuthorize(BudgetService.LECTURE)
    @Transactional(readOnly = true)
    public List<ExecutionServiceResponse> executions() {
        LocalDate aujourdhui = LocalDate.now();
        Map<Long, Budget> reference = new HashMap<>();
        Comparator<Budget> ordre = Comparator.comparingInt((Budget b) -> rang(b, aujourdhui))
            .thenComparing(Budget::dernierJour, Comparator.reverseOrder())
            .thenComparing(Budget::getId, Comparator.reverseOrder());
        budgetRepository.findAll().stream()
            .filter(b -> rang(b, aujourdhui) < Integer.MAX_VALUE)
            .sorted(ordre)
            .forEach(b -> b.getLignes().stream().map(LigneBudget::getService).filter(Objects::nonNull)
                .forEach(s -> reference.putIfAbsent(s.getId(), b)));
        if (reference.isEmpty()) {
            return List.of();
        }
        Map<Long, ServiceBudgetaire> catalogue = suivi.catalogueServices();
        Map<Integer, SuiviBudgetaireService.MouvementsExercice> mouvements = new HashMap<>();
        Map<Integer, List<SuiviBudgetaireService.Engagement>> engagements = new HashMap<>();
        Map<Long, Map<Long, SuiviService>> parBudget = new LinkedHashMap<>();
        List<ExecutionServiceResponse> r = new ArrayList<>();
        reference.forEach((serviceId, b) -> {
            Map<Long, SuiviService> execs = parBudget.computeIfAbsent(b.getId(), id -> suivi.executionDesServices(b,
                mouvements.computeIfAbsent(b.getExercice(), suivi::mouvementsDetailles),
                engagements.computeIfAbsent(b.getExercice(), ex -> suivi.engagementsDeLExercice(ex, null, new ArrayList<>())),
                catalogue));
            SuiviService e = execs.get(serviceId);
            if (e == null) {
                return;
            }
            LocalDate debut = b.premierJour();
            LocalDate fin = b.dernierJour();
            long total = ChronoUnit.DAYS.between(debut, fin) + 1;
            long ecoules = aujourdhui.isBefore(debut) ? 0 : Math.min(total, ChronoUnit.DAYS.between(debut, aujourdhui) + 1);
            r.add(new ExecutionServiceResponse(serviceId, b.getId(), b.getReference(), b.getIntitule(), b.getStatut().name(),
                b.getDuree().nom(), b.libellePeriode(), debut, fin, total, ecoules, e.prevu(), e.realise(), e.engage(),
                e.disponible(), e.tauxExecution(), e.depassement(), ConversionDeviseService.DEVISE_BASE.name()));
        });
        return r;
    }

    /** Priorite d'un budget comme reference d'un service (plus petit = prefere) ; MAX_VALUE : jamais retenu. */
    private static int rang(Budget b, LocalDate jour) {
        StatutBudget st = b.getStatut();
        if (st == StatutBudget.EN_EXECUTION) {
            boolean enCours = !jour.isBefore(b.premierJour()) && !jour.isAfter(b.dernierJour());
            return enCours ? 0 : 1;
        }
        return switch (st) {
            case APPROUVE -> 2;
            case SOUMIS -> 3;
            case BROUILLON -> 4;
            case CLOTURE -> 5;
            default -> Integer.MAX_VALUE;
        };
    }

    @PreAuthorize(GESTION)
    @Transactional
    public ServiceBudgetaireResponse creer(ServiceBudgetaireRequest req) {
        String nom = nettoyer(req.nom());
        if (repository.existsByNomIgnoreCase(nom)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Un service s'appelle déjà « " + nom + " ».");
        }
        ServiceBudgetaire s = repository.save(ServiceBudgetaire.builder()
            .nom(nom)
            .responsable(nettoyer(req.responsable()))
            .description(vide(req.description()))
            .actif(true)
            .creePar(currentUser.requireUser())
            .build());
        log.info("Service budgetaire cree [{} - responsable {}]", s.getNom(), s.getResponsable());
        return ServiceBudgetaireResponse.from(s, 0, 0);
    }

    @PreAuthorize(GESTION)
    @Transactional
    public ServiceBudgetaireResponse modifier(Long id, ServiceBudgetaireRequest req) {
        ServiceBudgetaire s = charger(id);
        String nom = nettoyer(req.nom());
        if (repository.existsByNomIgnoreCaseAndIdNot(nom, id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Un service s'appelle déjà « " + nom + " ».");
        }
        s.setNom(nom);
        s.setResponsable(nettoyer(req.responsable()));
        s.setDescription(vide(req.description()));
        log.info("Service budgetaire modifie [{} - responsable {}]", s.getNom(), s.getResponsable());
        return reponse(s);
    }

    /**
     * Active ou desactive un service. Desactive, il ne se choisit plus pour une nouvelle note de frais ni pour une
     * nouvelle ligne de budget ; ses lignes et ses notes existantes restent rattachees et suivies.
     */
    @PreAuthorize(GESTION)
    @Transactional
    public ServiceBudgetaireResponse basculerActif(Long id) {
        ServiceBudgetaire s = charger(id);
        s.setActif(!s.isActif());
        log.info("Service budgetaire {} [{}]", s.isActif() ? "reactive" : "desactive", s.getNom());
        return reponse(s);
    }

    /** Supprime un service encore jamais utilise ; un service deja rattache a un budget ou a une note se desactive. */
    @PreAuthorize(GESTION)
    @Transactional
    public void supprimer(Long id) {
        ServiceBudgetaire s = charger(id);
        ServiceBudgetaireResponse usage = reponse(s);
        if (!usage.supprimable()) {
            throw new TransitionInvalideException("Le service « " + s.getNom() + " » est déjà utilisé ("
                + usage.nombreLignesBudget() + " ligne(s) de budget, " + usage.nombreNotes() + " note(s) de frais) : "
                + "désactivez-le plutôt, son historique reste ainsi consultable.");
        }
        repository.delete(s);
        log.info("Service budgetaire supprime [{}]", s.getNom());
    }

    // ---------------------------------------------------------------------

    private ServiceBudgetaire charger(Long id) {
        return repository.findById(id).orElseThrow(() -> RessourceIntrouvableException.of("ServiceBudgetaire", id));
    }

    private ServiceBudgetaireResponse reponse(ServiceBudgetaire s) {
        return ServiceBudgetaireResponse.from(s, compter(repository.compterLignesParService()).getOrDefault(s.getId(), 0L),
            compter(repository.compterNotesParService()).getOrDefault(s.getId(), 0L));
    }

    private static Map<Long, Long> compter(List<Object[]> lignes) {
        Map<Long, Long> r = new HashMap<>();
        for (Object[] l : lignes) {
            r.put(((Number) l[0]).longValue(), ((Number) l[1]).longValue());
        }
        return r;
    }

    /** Espaces de bord retires, espaces multiples reduits a un seul. */
    static String nettoyer(String s) {
        return s == null ? null : s.strip().replaceAll("\\s+", " ");
    }

    private static String vide(String s) {
        return StringUtils.hasText(s) ? s.strip() : null;
    }
}
