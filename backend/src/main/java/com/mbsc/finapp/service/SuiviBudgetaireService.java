package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Budget;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.LigneBudget;
import com.mbsc.finapp.domain.LigneNoteFrais;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.ServiceBudgetaire;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.StatutControleBudget;
import com.mbsc.finapp.domain.enums.StatutNote;
import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse.CompteHorsBudget;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse.Depassement;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse.LigneSuivi;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse.NoteHorsBudget;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse.NoteService;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse.SuiviService;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse.TotalSuivi;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.NoteFraisRepository;
import com.mbsc.finapp.repository.ServiceBudgetaireRepository;
import com.mbsc.finapp.service.ImputationBudgetaire.Enveloppe;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Realise et engage d'un budget sur sa periode, calcules a la demande (jamais stockes, donc jamais desynchronises).
 *
 * <ul>
 *   <li><b>Realise</b> : mouvements du grand livre des mois de la periode du budget sur les comptes de la ligne et
 *       leurs sous-comptes (pieces non brouillon et operations de tresorerie, hors a-nouveaux, reevaluations de
 *       change et piece de cloture). Chaque compte se range sur la ligne consolidee la plus precise qui le
 *       couvre.</li>
 *   <li><b>Engage</b> : notes de frais approuvees par le DA mais pas encore payees (VALIDEE_DA,
 *       TRANSMISE_CAISSE), creees pendant la periode, au montant HT converti en devise de base au taux
 *       d'engagement de la note. Une fois payee, la note passe dans le realise par ses ecritures.</li>
 *   <li><b>Services</b> : une ecriture de paiement d'une note rattachee a un service, et l'engagement d'une telle
 *       note, s'imputent en plus sur l'enveloppe de ce service (voir {@link ImputationBudgetaire}).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SuiviBudgetaireService {

    /** Notes approuvees non encore payees : engagements budgetaires. */
    static final Set<StatutNote> STATUTS_ENGAGES = EnumSet.of(StatutNote.VALIDEE_DA, StatutNote.TRANSMISE_CAISSE);
    /** Compte de repli applique au paiement d'une ligne de note sans compte (voir EcritureComptableService). */
    static final String COMPTE_REPLI = "6588";
    /** Nom du bloc des lignes generales (sans service) d'un budget organise par service. */
    static final String LIGNES_GENERALES = "Lignes générales (sans service)";
    private static final String DEVISE = ConversionDeviseService.DEVISE_BASE.name();

    private final EcritureGrandLivreRepository ecritureRepository;
    private final NoteFraisRepository noteRepository;
    private final CompteOHADARepository compteRepository;
    private final ConversionDeviseService conversion;
    private final ServiceBudgetaireRepository serviceRepository;

    // ---------------------------------------------------------------------
    // Donnees brutes d'un exercice
    // ---------------------------------------------------------------------

    /** Mouvements d'un compte budgetable sur l'exercice : debit et credit, mois par mois. */
    public record MouvementsCompte(String numero, TypeCompte type, BigDecimal[] debit, BigDecimal[] credit) {
        BigDecimal[] realiseSelon(TypeCompte typeLigne) {
            BigDecimal[] r = new BigDecimal[MoteurBudgetaire.MOIS];
            for (int i = 0; i < MoteurBudgetaire.MOIS; i++) {
                r[i] = MoteurBudgetaire.realise(typeLigne, debit[i], credit[i]);
            }
            return r;
        }
    }

    /** Mouvements d'un compte pendant un mois, pour un service (null : ecriture sans note ou note sans service). */
    public record Mouvement(String numero, TypeCompte type, int mois, Long serviceId, BigDecimal debit, BigDecimal credit) {
        BigDecimal realiseSelon(TypeCompte typeLigne) {
            return MoteurBudgetaire.realise(typeLigne, debit, credit);
        }
    }

    /** Mouvements de l'exercice : detail par service, et totaux par compte tous services confondus. */
    public record MouvementsExercice(List<Mouvement> lignes, Map<String, MouvementsCompte> parCompte) {
        public static MouvementsExercice vide() {
            return new MouvementsExercice(List.of(), Map.of());
        }
    }

    /** Mouvements de l'exercice sur les classes budgetaires (2, 6, 7, 8), par compte, mois et service. */
    @Transactional(readOnly = true)
    public MouvementsExercice mouvementsDetailles(int exercice) {
        List<Mouvement> lignes = new ArrayList<>();
        Map<String, MouvementsCompte> parCompte = new TreeMap<>();
        for (Object[] row : ecritureRepository.mouvementsMensuelsBudgetaires(
                LocalDate.of(exercice, 1, 1), LocalDate.of(exercice, 12, 31))) {
            String numero = (String) row[0];
            TypeCompte type = (TypeCompte) row[1];
            int mois = ((Number) row[2]).intValue();
            Long service = row[3] == null ? null : ((Number) row[3]).longValue();
            BigDecimal debit = montant(row[4]);
            BigDecimal credit = montant(row[5]);
            lignes.add(new Mouvement(numero, type, mois, service, debit, credit));
            MouvementsCompte m = parCompte.computeIfAbsent(numero,
                n -> new MouvementsCompte(n, type, MoteurBudgetaire.zeros(), MoteurBudgetaire.zeros()));
            m.debit()[mois - 1] = m.debit()[mois - 1].add(debit);
            m.credit()[mois - 1] = m.credit()[mois - 1].add(credit);
        }
        return new MouvementsExercice(List.copyOf(lignes), parCompte);
    }

    /** Mouvements de l'exercice, par compte, sur les classes budgetaires (2, 6, 7, 8), tous services confondus. */
    @Transactional(readOnly = true)
    public Map<String, MouvementsCompte> mouvementsDeLExercice(int exercice) {
        return mouvementsDetailles(exercice).parCompte();
    }

    /** Un engagement : une ligne de note approuvee non payee, en devise de base, avec le service de la note. */
    public record Engagement(Long noteId, String compteNumero, int mois, BigDecimal montantBase, Long serviceId) {
    }

    /**
     * Engagements de l'exercice (lignes des notes approuvees non payees), convertis en devise de base.
     *
     * @param noteExclue note a ne pas compter (celle que l'on controle : elle ne doit pas se gener elle-meme)
     * @param avertissements recoit un message par note qui n'a pas pu etre convertie (taux de change manquant)
     */
    @Transactional(readOnly = true)
    public List<Engagement> engagementsDeLExercice(int exercice, Long noteExclue, List<String> avertissements) {
        List<Engagement> engagements = new ArrayList<>();
        for (NoteFrais n : noteRepository.findDecaissementsAvecLignes(STATUTS_ENGAGES, debut(exercice), debut(exercice + 1))) {
            if (noteExclue != null && noteExclue.equals(n.getId())) {
                continue;
            }
            BigDecimal taux = tauxDeLaNote(n);
            int mois = moisDe(n.getDateCreation());
            Long service = n.getService() == null ? null : n.getService().getId();
            for (LigneNoteFrais l : n.getLignes()) {
                BigDecimal base = versBase(l.montantHtTotal(), n.getDevise(), taux);
                if (base == null) {
                    avertissements.add("Note " + n.getReference() + " : taux de change manquant, engagement en "
                        + n.getDevise() + " non compte.");
                    break;
                }
                String compte = l.getCompteImputation() == null ? COMPTE_REPLI : l.getCompteImputation().getNumero();
                engagements.add(new Engagement(n.getId(), compte, mois, base, service));
            }
        }
        return engagements;
    }

    /**
     * Realise et engage mois par mois de la periode, ranges sur les lignes du budget.
     *
     * @param realise            budget consolide : cle = compte de la ligne consolidee
     * @param engage             idem
     * @param horsBudget         comptes budgetables mouvementes qu'aucune ligne du budget ne couvre (realise)
     * @param engageHorsBudget   idem pour les engagements
     * @param realiseEnveloppe   enveloppes (ligne d'un service, ou ligne generale) : realise impute
     * @param engageEnveloppe    idem pour les engagements
     * @param horsBudgetService  depenses d'un service qu'aucune ligne du service ni generale ne couvre : service, compte
     * @param engageHorsBudgetService idem pour les engagements
     * @param imputeGeneral      depenses d'un service imputees sur une ligne generale, faute de ligne du service
     * @param nonRattache        depenses sans service sur des comptes que seuls des services ont budgetes
     * @param types              type de chaque compte mouvemente (sens du realise)
     */
    public record ExecutionLignes(Map<String, BigDecimal[]> realise, Map<String, BigDecimal[]> engage,
                                  Map<String, BigDecimal[]> horsBudget, Map<String, BigDecimal[]> engageHorsBudget,
                                  Map<Enveloppe, BigDecimal[]> realiseEnveloppe, Map<Enveloppe, BigDecimal[]> engageEnveloppe,
                                  Map<Long, Map<String, BigDecimal[]>> horsBudgetService,
                                  Map<Long, Map<String, BigDecimal[]>> engageHorsBudgetService,
                                  Map<Long, BigDecimal[]> imputeGeneral,
                                  Map<String, BigDecimal[]> nonRattache,
                                  Map<String, TypeCompte> types,
                                  ImputationBudgetaire imputation,
                                  boolean mouvementsSurLaPeriode) {
    }

    /**
     * Range le realise et l'engage des mois de la periode du budget sur ses lignes : lignes consolidees (la plus
     * precise couvrant le compte) et enveloppes des services. Les mois hors de la periode sont ignores.
     */
    public ExecutionLignes repartirSurLignes(Budget budget, MouvementsExercice mouvements, List<Engagement> engagements) {
        int debut = budget.moisDebut();
        int fin = budget.moisFin();
        ImputationBudgetaire imputation = ImputationBudgetaire.de(budget);

        Map<String, TypeCompte> typeDesLignes = new HashMap<>();
        Map<Enveloppe, BigDecimal[]> realiseEnveloppe = new HashMap<>();
        Map<Enveloppe, BigDecimal[]> engageEnveloppe = new HashMap<>();
        for (LigneBudget l : budget.getLignes()) {
            typeDesLignes.put(l.getCompte().getNumero(), l.getCompte().getType());
            Enveloppe e = ImputationBudgetaire.enveloppeDe(l);
            realiseEnveloppe.put(e, MoteurBudgetaire.zeros());
            engageEnveloppe.put(e, MoteurBudgetaire.zeros());
        }
        Map<String, BigDecimal[]> realise = new HashMap<>();
        Map<String, BigDecimal[]> engage = new HashMap<>();
        imputation.comptesConsolides().forEach(c -> {
            realise.put(c, MoteurBudgetaire.zeros());
            engage.put(c, MoteurBudgetaire.zeros());
        });
        Map<String, BigDecimal[]> horsBudget = new TreeMap<>();
        Map<String, BigDecimal[]> engageHorsBudget = new TreeMap<>();
        Map<Long, Map<String, BigDecimal[]>> horsBudgetService = new HashMap<>();
        Map<Long, Map<String, BigDecimal[]>> engageHorsBudgetService = new HashMap<>();
        Map<Long, BigDecimal[]> imputeGeneral = new HashMap<>();
        Map<String, BigDecimal[]> nonRattache = new TreeMap<>();
        Map<String, TypeCompte> types = new HashMap<>();
        boolean surLaPeriode = false;

        for (Mouvement m : mouvements.lignes()) {
            if (m.mois() < debut || m.mois() > fin) {
                continue;
            }
            surLaPeriode = true;
            types.putIfAbsent(m.numero(), m.type());
            Integer classe = classe(m.numero());
            boolean depense = MoteurBudgetaire.estDepenseBudgetaire(m.numero(), classe, m.type());
            String consolidee = imputation.ligneConsolidee(m.numero());
            if (consolidee != null) {
                ajouter(realise.get(consolidee), m.mois(), m.realiseSelon(typeDesLignes.get(consolidee)));
            } else if (MoteurBudgetaire.estBudgetable(m.numero(), classe)) {
                ajouter(horsBudget.computeIfAbsent(m.numero(), k -> MoteurBudgetaire.zeros()), m.mois(), m.realiseSelon(m.type()));
            }
            Enveloppe enveloppe = imputation.enveloppe(m.numero(), m.serviceId());
            if (enveloppe != null) {
                BigDecimal valeur = m.realiseSelon(typeDesLignes.get(enveloppe.compte()));
                ajouter(realiseEnveloppe.get(enveloppe), m.mois(), valeur);
                if (m.serviceId() != null && enveloppe.serviceId() == null && depense) {
                    ajouter(imputeGeneral.computeIfAbsent(m.serviceId(), k -> MoteurBudgetaire.zeros()), m.mois(), valeur);
                }
            } else if (m.serviceId() != null) {
                if (depense) {
                    ajouter(serie(horsBudgetService, m.serviceId(), m.numero()), m.mois(), m.realiseSelon(m.type()));
                }
            } else if (consolidee != null && depense) {
                ajouter(nonRattache.computeIfAbsent(m.numero(), k -> MoteurBudgetaire.zeros()), m.mois(), m.realiseSelon(m.type()));
            }
        }
        for (Engagement e : engagements) {
            if (e.mois() < debut || e.mois() > fin) {
                continue;
            }
            String consolidee = imputation.ligneConsolidee(e.compteNumero());
            if (consolidee != null) {
                ajouter(engage.get(consolidee), e.mois(), e.montantBase());
            } else if (MoteurBudgetaire.estBudgetable(e.compteNumero(), classe(e.compteNumero()))) {
                ajouter(engageHorsBudget.computeIfAbsent(e.compteNumero(), k -> MoteurBudgetaire.zeros()), e.mois(), e.montantBase());
            }
            Enveloppe enveloppe = imputation.enveloppe(e.compteNumero(), e.serviceId());
            if (enveloppe != null) {
                ajouter(engageEnveloppe.get(enveloppe), e.mois(), e.montantBase());
            } else if (e.serviceId() != null && MoteurBudgetaire.estBudgetable(e.compteNumero(), classe(e.compteNumero()))) {
                ajouter(serie(engageHorsBudgetService, e.serviceId(), e.compteNumero()), e.mois(), e.montantBase());
            }
        }
        return new ExecutionLignes(realise, engage, horsBudget, engageHorsBudget, realiseEnveloppe, engageEnveloppe,
            horsBudgetService, engageHorsBudgetService, imputeGeneral, nonRattache, types, imputation, surLaPeriode);
    }

    // ---------------------------------------------------------------------
    // Suivi complet
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SuiviBudgetResponse suivre(Budget budget) {
        int exercice = budget.getExercice();
        List<String> avertissements = new ArrayList<>();
        ExecutionLignes exec = repartirSurLignes(budget, mouvementsDetailles(exercice),
            engagementsDeLExercice(exercice, null, avertissements));
        int moisEcoules = moisEcoules(exercice);

        Map<String, CompteOHADA> plan = compteRepository.findAllByOrderByNumeroAsc().stream()
            .collect(Collectors.toMap(CompteOHADA::getNumero, Function.identity(), (a, b) -> a));

        // Budget consolide : une ligne par compte, tous services confondus.
        List<LigneSuivi> lignes = lignesConsolidees(budget, exec, moisEcoules);

        // Totaux par section, puis par nature SYSCOHADA (compte a deux chiffres) dans chaque section.
        List<TotalSuivi> sections = new ArrayList<>();
        for (MoteurBudgetaire.Section s : MoteurBudgetaire.Section.values()) {
            List<LigneSuivi> dela = lignes.stream().filter(l -> l.section().equals(s.name())).toList();
            if (!dela.isEmpty()) {
                sections.add(total(s.name(), libelleSection(s), s.name(), dela));
            }
        }
        Map<String, List<LigneSuivi>> parNature = lignes.stream()
            .collect(Collectors.groupingBy(l -> l.section() + "|" + l.nature(), TreeMap::new, Collectors.toList()));
        List<TotalSuivi> natures = parNature.entrySet().stream()
            .map(e -> {
                LigneSuivi premiere = e.getValue().get(0);
                CompteOHADA compteNature = plan.get(premiere.nature());
                String libelle = compteNature == null ? "Nature " + premiere.nature() : compteNature.getLibelle();
                return total(premiere.nature(), libelle, premiere.section(), e.getValue());
            })
            .sorted(Comparator.comparing((TotalSuivi t) -> ordreSection(t.section())).thenComparing(TotalSuivi::code))
            .toList();

        TotalSuivi resultat = resultat(sections);

        List<CompteHorsBudget> horsBudget = comptesHorsBudget(exec.horsBudget(), exec.engageHorsBudget(), exec, plan);

        Instant du = debutPeriode(budget);
        Instant au = finPeriode(budget);
        List<NoteHorsBudget> notesHorsBudget = noteRepository.findHorsBudgetEntre(
                EnumSet.of(StatutControleBudget.DEPASSEMENT, StatutControleBudget.HORS_BUDGET, StatutControleBudget.SANS_BUDGET),
                du, au).stream()
            .map(n -> new NoteHorsBudget(n.getId(), n.getReference(), n.getObjet(), n.getMontant(),
                n.getDevise() == null ? null : n.getDevise().name(), n.getStatut().name(),
                n.getStatutBudget().name(), n.getJustificationBudget(), nom(n), n.getDateCreation(),
                n.getService() == null ? null : n.getService().getNom()))
            .toList();

        List<SuiviService> services = suiviDesServices(budget, exec, catalogueServices(),
            noteRepository.findDecaissementsDesServicesEntre(du, au), moisEcoules, plan);
        List<CompteHorsBudget> nonRattache = comptesHorsBudget(exec.nonRattache(), Map.of(), exec, plan);
        List<Depassement> depassements = depassements(exec.imputation().parService(), lignes, services);

        // Une periode pas encore commencee n'a pas d'ecritures : l'avertissement n'aurait aucun sens.
        if (!exec.mouvementsSurLaPeriode() && !LocalDate.now().isBefore(budget.premierJour())) {
            avertissements.add("Aucune écriture comptable sur les classes 2, 6, 7 et 8 pour la période « "
                + budget.libellePeriode() + " » : le réalisé est nul.");
        }
        if (!horsBudget.isEmpty()) {
            avertissements.add(horsBudget.size() == 1
                ? "1 compte mouvementé ou engagé pendant la période n'est couvert par aucune ligne du budget (voir « Dépenses et recettes hors budget »)."
                : horsBudget.size() + " comptes mouvementés ou engagés pendant la période ne sont couverts par aucune ligne du budget "
                    + "(voir « Dépenses et recettes hors budget »).");
        }
        if (!nonRattache.isEmpty()) {
            BigDecimal montant = nonRattache.stream().map(CompteHorsBudget::realiseAnnuel).reduce(BigDecimal.ZERO, BigDecimal::add);
            avertissements.add(fmt(montant) + " " + DEVISE + " de dépenses passées sur des comptes budgétés par service ne sont "
                + "rattachées à aucun service : rattachez les notes de frais à leur service pour les suivre sur son budget.");
        }

        return new SuiviBudgetResponse(budget.getId(), budget.getReference(), budget.getIntitule(), exercice,
            budget.getStatut(), budget.getNumeroRevision(), LocalDate.now(), moisEcoules, DEVISE,
            lignes, sections, natures, resultat, horsBudget, notesHorsBudget, List.copyOf(avertissements),
            budget.getDuree(), budget.getPeriode(), budget.libellePeriode(), budget.moisDebut(), budget.moisFin(),
            exec.imputation().parService(), services, nonRattache, depassements);
    }

    /** Synthese d'execution d'un budget pour la liste des budgets (sans les notes ni le detail des lignes). */
    public record SyntheseExecution(BigDecimal realiseProduits, BigDecimal realiseCharges, BigDecimal realiseInvestissements,
                                    BigDecimal montantDepassement, int nombreDepassements, List<String> servicesEnDepassement) {
    }

    /**
     * Synthese pour la liste : realise par section du budget consolide et, si {@code avecDepassements}, ses
     * depassements et les services dont l'enveloppe est depassee. Les mouvements et engagements sont lus une
     * seule fois par exercice par l'appelant.
     */
    public SyntheseExecution synthese(Budget budget, MouvementsExercice mouvements, List<Engagement> engagements,
                                      Map<Long, ServiceBudgetaire> catalogue, boolean avecDepassements) {
        ExecutionLignes exec = repartirSurLignes(budget, mouvements, engagements);
        int moisEcoules = moisEcoules(budget.getExercice());
        List<LigneSuivi> lignes = lignesConsolidees(budget, exec, moisEcoules);
        Map<String, BigDecimal> parSection = new HashMap<>();
        for (LigneSuivi l : lignes) {
            parSection.merge(l.section(), l.realiseAnnuel(), BigDecimal::add);
        }
        BigDecimal montant = BigDecimal.ZERO;
        int nombre = 0;
        List<String> servicesEnDepassement = List.of();
        if (avecDepassements) {
            for (LigneSuivi l : lignes) {
                if (l.depassement().signum() > 0) {
                    montant = montant.add(l.depassement());
                    nombre++;
                }
            }
            servicesEnDepassement = suiviDesServices(budget, exec, catalogue, List.of(), moisEcoules, Map.of()).stream()
                .filter(s -> s.serviceId() != null && s.depassement().signum() > 0)
                .map(SuiviService::nom)
                .toList();
        }
        return new SyntheseExecution(parSection.getOrDefault("PRODUITS", BigDecimal.ZERO),
            parSection.getOrDefault("CHARGES", BigDecimal.ZERO), parSection.getOrDefault("INVESTISSEMENTS", BigDecimal.ZERO),
            montant, nombre, servicesEnDepassement);
    }

    /** Tous les services (actifs ou non), par identifiant. */
    @Transactional(readOnly = true)
    public Map<Long, ServiceBudgetaire> catalogueServices() {
        return serviceRepository.findAll().stream().collect(Collectors.toMap(ServiceBudgetaire::getId, Function.identity()));
    }

    // ---------------------------------------------------------------------
    // Construction
    // ---------------------------------------------------------------------

    /** Lignes du budget consolide : un compte par ligne, ses services cumules. */
    private List<LigneSuivi> lignesConsolidees(Budget budget, ExecutionLignes exec, int moisEcoules) {
        Map<String, List<LigneBudget>> parCompte = new TreeMap<>();
        for (LigneBudget l : budget.getLignes()) {
            parCompte.computeIfAbsent(l.getCompte().getNumero(), k -> new ArrayList<>()).add(l);
        }
        List<LigneSuivi> lignes = new ArrayList<>();
        for (List<LigneBudget> memeCompte : parCompte.values()) {
            LigneBudget premiere = memeCompte.get(0);
            String numero = premiere.getCompte().getNumero();
            BigDecimal[] prevu = MoteurBudgetaire.zeros();
            for (LigneBudget l : memeCompte) {
                prevu = MoteurBudgetaire.additionner(prevu, MoteurBudgetaire.mensuelDepuis(l.getMois()));
            }
            Long id = memeCompte.stream().map(LigneBudget::getId).filter(Objects::nonNull).min(Long::compareTo).orElse(null);
            String commentaire = memeCompte.stream().map(LigneBudget::getCommentaire).filter(StringUtils::hasText)
                .map(String::trim).distinct().collect(Collectors.joining(" · "));
            List<String> services = memeCompte.stream().map(LigneBudget::getService).filter(Objects::nonNull)
                .map(ServiceBudgetaire::getNom).distinct().sorted().toList();
            lignes.add(ligneSuivi(id, premiere.getCompte(), commentaire.isEmpty() ? null : commentaire, services, prevu,
                exec.realise().get(numero), exec.engage().get(numero), moisEcoules));
        }
        return lignes;
    }

    /**
     * Execution de chaque service : ceux qui ont des lignes dans le budget, et ceux qui ont depense ou engage pendant la
     * periode (meme sans ligne) ; puis, dans un budget organise par service, les lignes generales (enveloppe commune).
     */
    private List<SuiviService> suiviDesServices(Budget budget, ExecutionLignes exec, Map<Long, ServiceBudgetaire> catalogue,
                                                List<NoteFrais> notes, int moisEcoules, Map<String, CompteOHADA> plan) {
        Set<Long> ids = new LinkedHashSet<>();
        for (LigneBudget l : budget.getLignes()) {
            if (l.getService() != null) {
                ids.add(l.getService().getId());
            }
        }
        ids.addAll(exec.horsBudgetService().keySet());
        ids.addAll(exec.engageHorsBudgetService().keySet());
        ids.addAll(exec.imputeGeneral().keySet());
        Map<Long, List<NoteFrais>> notesParService = new HashMap<>();
        for (NoteFrais n : notes) {
            ids.add(n.getService().getId());
            notesParService.computeIfAbsent(n.getService().getId(), k -> new ArrayList<>()).add(n);
        }
        Map<Long, ServiceBudgetaire> connus = new HashMap<>(catalogue);
        budget.getLignes().stream().map(LigneBudget::getService).filter(Objects::nonNull).forEach(s -> connus.putIfAbsent(s.getId(), s));
        notes.forEach(n -> connus.putIfAbsent(n.getService().getId(), n.getService()));

        List<SuiviService> blocs = new ArrayList<>();
        ids.stream()
            .filter(connus::containsKey)
            .sorted(Comparator.comparing(id -> connus.get(id).getNom(), String.CASE_INSENSITIVE_ORDER))
            .forEach(id -> {
                ServiceBudgetaire s = connus.get(id);
                List<LigneBudget> sesLignes = budget.getLignes().stream()
                    .filter(l -> l.getService() != null && l.getService().getId().equals(id))
                    .sorted(Comparator.comparing(l -> l.getCompte().getNumero()))
                    .toList();
                blocs.add(bloc(id, s.getNom(), s.getResponsable(), s.isActif(), sesLignes, exec,
                    notesParService.getOrDefault(id, List.of()), moisEcoules, plan));
            });
        // Lignes generales de depenses d'un budget organise par service : l'enveloppe commune (ses recettes restent dans
        // le budget consolide, ce suivi ne porte que sur les depenses).
        List<LigneBudget> generales = budget.getLignes().stream()
            .filter(l -> l.getService() == null)
            .filter(l -> MoteurBudgetaire.section(l.getCompte().getNumero(), l.getCompte().getType()) != MoteurBudgetaire.Section.PRODUITS)
            .sorted(Comparator.comparing(l -> l.getCompte().getNumero()))
            .toList();
        if (exec.imputation().parService() && !generales.isEmpty()) {
            blocs.add(bloc(null, LIGNES_GENERALES, null, true, generales, exec, List.of(), moisEcoules, plan));
        }
        return blocs;
    }

    private SuiviService bloc(Long serviceId, String nom, String responsable, boolean actif, List<LigneBudget> sesLignes,
                              ExecutionLignes exec, List<NoteFrais> notes, int moisEcoules, Map<String, CompteOHADA> plan) {
        List<String> nomService = serviceId == null ? List.of() : List.of(nom);
        List<LigneSuivi> lignes = sesLignes.stream()
            .map(l -> {
                Enveloppe e = ImputationBudgetaire.enveloppeDe(l);
                return ligneSuivi(l.getId(), l.getCompte(), l.getCommentaire(), nomService, MoteurBudgetaire.mensuelDepuis(l.getMois()),
                    exec.realiseEnveloppe().get(e), exec.engageEnveloppe().get(e), moisEcoules);
            })
            .toList();
        BigDecimal prevu = BigDecimal.ZERO;
        BigDecimal realise = BigDecimal.ZERO;
        BigDecimal engage = BigDecimal.ZERO;
        BigDecimal depassement = BigDecimal.ZERO;
        int nombre = 0;
        for (LigneSuivi l : lignes) {
            if ("PRODUITS".equals(l.section())) {
                continue;
            }
            prevu = prevu.add(l.prevuAnnuel());
            realise = realise.add(l.realiseAnnuel());
            engage = engage.add(l.engageAnnuel());
            if (l.depassement().signum() > 0) {
                depassement = depassement.add(l.depassement());
                nombre++;
            }
        }
        List<CompteHorsBudget> horsBudget = serviceId == null ? List.of()
            : comptesHorsBudget(exec.horsBudgetService().getOrDefault(serviceId, Map.of()),
                exec.engageHorsBudgetService().getOrDefault(serviceId, Map.of()), exec, plan);
        BigDecimal nonBudgete = BigDecimal.ZERO;
        for (CompteHorsBudget h : horsBudget) {
            BigDecimal montant = h.realiseAnnuel().add(h.engageAnnuel());
            if (montant.signum() > 0) {
                nonBudgete = nonBudgete.add(montant);
                nombre++;
            }
        }
        depassement = depassement.add(nonBudgete);
        BigDecimal imputeGeneral = serviceId == null ? BigDecimal.ZERO
            : MoteurBudgetaire.total(exec.imputeGeneral().get(serviceId));
        List<NoteService> sesNotes = notes.stream()
            .map(n -> new NoteService(n.getId(), n.getReference(), n.getObjet(), n.getMontant(),
                n.getDevise() == null ? null : n.getDevise().name(), n.getStatut().name(),
                n.getStatutBudget() == null ? null : n.getStatutBudget().name(), n.getDateCreation()))
            .toList();
        return new SuiviService(serviceId, nom, responsable, actif, lignes, prevu, realise, engage,
            prevu.subtract(realise).subtract(engage), MoteurBudgetaire.taux(realise, prevu), nonBudgete, depassement, nombre,
            imputeGeneral, horsBudget, sesNotes);
    }

    /**
     * Depassements de la periode : enveloppes des services (lignes depassees et depenses non budgetees) dans un budget
     * organise par service, puis lignes du budget consolide ; les plus importants d'abord dans chaque niveau.
     */
    static List<Depassement> depassements(boolean parService, List<LigneSuivi> consolidees, List<SuiviService> services) {
        List<Depassement> liste = new ArrayList<>();
        if (parService) {
            for (SuiviService s : services) {
                for (LigneSuivi l : s.lignes()) {
                    if (l.depassement().signum() > 0) {
                        liste.add(new Depassement("SERVICE", "DEPASSEMENT", s.serviceId(), s.nom(), s.responsable(), l.compteNumero(),
                            l.compteLibelle(), l.section(), l.prevuAnnuel(), l.realiseAnnuel(), l.engageAnnuel(), l.depassement()));
                    }
                }
                for (CompteHorsBudget h : s.horsBudget()) {
                    BigDecimal montant = h.realiseAnnuel().add(h.engageAnnuel());
                    if (montant.signum() > 0) {
                        liste.add(new Depassement("SERVICE", "NON_BUDGETE", s.serviceId(), s.nom(), s.responsable(), h.compteNumero(),
                            h.compteLibelle(), h.section(), BigDecimal.ZERO, h.realiseAnnuel(), h.engageAnnuel(), montant));
                    }
                }
            }
        }
        for (LigneSuivi l : consolidees) {
            if (l.depassement().signum() > 0) {
                liste.add(new Depassement("CONSOLIDE", "DEPASSEMENT", null, null, null, l.compteNumero(), l.compteLibelle(),
                    l.section(), l.prevuAnnuel(), l.realiseAnnuel(), l.engageAnnuel(), l.depassement()));
            }
        }
        liste.sort(Comparator.comparing((Depassement d) -> "SERVICE".equals(d.niveau()) ? 0 : 1)
            .thenComparing(Depassement::montant, Comparator.reverseOrder()));
        return liste;
    }

    private List<CompteHorsBudget> comptesHorsBudget(Map<String, BigDecimal[]> realise, Map<String, BigDecimal[]> engage,
                                                     ExecutionLignes exec, Map<String, CompteOHADA> plan) {
        Set<String> comptes = new TreeSet<>(realise.keySet());
        comptes.addAll(engage.keySet());
        return comptes.stream()
            .map(numero -> {
                BigDecimal[] serie = realise.getOrDefault(numero, MoteurBudgetaire.zeros());
                CompteOHADA c = plan.get(numero);
                TypeCompte type = exec.types().getOrDefault(numero, c == null ? null : c.getType());
                return new CompteHorsBudget(numero, c == null ? null : c.getLibelle(),
                    MoteurBudgetaire.section(numero, type).name(), Arrays.asList(serie), MoteurBudgetaire.total(serie),
                    MoteurBudgetaire.total(engage.get(numero)));
            })
            .filter(h -> h.realiseAnnuel().signum() != 0 || h.engageAnnuel().signum() != 0)
            .sorted(Comparator.comparing((CompteHorsBudget h) -> h.realiseAnnuel().add(h.engageAnnuel()).abs()).reversed())
            .toList();
    }

    private LigneSuivi ligneSuivi(Long id, CompteOHADA compte, String commentaire, List<String> services, BigDecimal[] prevu,
                                  BigDecimal[] realise, BigDecimal[] engage, int moisEcoules) {
        String numero = compte.getNumero();
        BigDecimal[] r = realise == null ? MoteurBudgetaire.zeros() : realise;
        BigDecimal[] e = engage == null ? MoteurBudgetaire.zeros() : engage;
        String section = MoteurBudgetaire.section(numero, compte.getType()).name();
        BigDecimal prevuAnnuel = MoteurBudgetaire.total(prevu);
        BigDecimal realiseAnnuel = MoteurBudgetaire.total(r);
        BigDecimal engageAnnuel = MoteurBudgetaire.total(e);
        BigDecimal prevuADate = MoteurBudgetaire.cumul(prevu, moisEcoules);
        BigDecimal realiseADate = MoteurBudgetaire.cumul(r, moisEcoules);
        BigDecimal depassement = "PRODUITS".equals(section) ? BigDecimal.ZERO
            : realiseAnnuel.add(engageAnnuel).subtract(prevuAnnuel).max(BigDecimal.ZERO);
        return new LigneSuivi(id, numero, compte.getLibelle(), section, MoteurBudgetaire.nature(numero),
            commentaire, Arrays.asList(prevu), Arrays.asList(r), Arrays.asList(e),
            prevuAnnuel, realiseAnnuel, engageAnnuel,
            prevuAnnuel.subtract(realiseAnnuel).subtract(engageAnnuel),
            realiseAnnuel.subtract(prevuAnnuel), MoteurBudgetaire.taux(realiseAnnuel, prevuAnnuel),
            prevuADate, realiseADate, realiseADate.subtract(prevuADate), services, depassement);
    }

    private TotalSuivi total(String code, String libelle, String section, List<LigneSuivi> lignes) {
        BigDecimal[] prevu = MoteurBudgetaire.zeros();
        BigDecimal[] realise = MoteurBudgetaire.zeros();
        BigDecimal[] engage = MoteurBudgetaire.zeros();
        for (LigneSuivi l : lignes) {
            prevu = MoteurBudgetaire.additionner(prevu, l.prevu().toArray(BigDecimal[]::new));
            realise = MoteurBudgetaire.additionner(realise, l.realise().toArray(BigDecimal[]::new));
            engage = MoteurBudgetaire.additionner(engage, l.engage().toArray(BigDecimal[]::new));
        }
        BigDecimal prevuAnnuel = MoteurBudgetaire.total(prevu);
        BigDecimal realiseAnnuel = MoteurBudgetaire.total(realise);
        return new TotalSuivi(code, libelle, section, Arrays.asList(prevu), Arrays.asList(realise), Arrays.asList(engage),
            prevuAnnuel, realiseAnnuel, MoteurBudgetaire.total(engage), MoteurBudgetaire.taux(realiseAnnuel, prevuAnnuel));
    }

    /** Resultat = produits - charges, mois par mois (les investissements n'entrent pas dans le resultat). */
    private TotalSuivi resultat(List<TotalSuivi> sections) {
        BigDecimal[] prevu = MoteurBudgetaire.zeros();
        BigDecimal[] realise = MoteurBudgetaire.zeros();
        for (TotalSuivi s : sections) {
            int signe = "PRODUITS".equals(s.section()) ? 1 : "CHARGES".equals(s.section()) ? -1 : 0;
            if (signe == 0) {
                continue;
            }
            for (int i = 0; i < MoteurBudgetaire.MOIS; i++) {
                prevu[i] = prevu[i].add(s.prevu().get(i).multiply(BigDecimal.valueOf(signe)));
                realise[i] = realise[i].add(s.realise().get(i).multiply(BigDecimal.valueOf(signe)));
            }
        }
        return new TotalSuivi("RESULTAT", "Résultat (produits - charges)", "RESULTAT", Arrays.asList(prevu), Arrays.asList(realise),
            Arrays.asList(MoteurBudgetaire.zeros()), MoteurBudgetaire.total(prevu), MoteurBudgetaire.total(realise),
            BigDecimal.ZERO, null);
    }

    static String libelleSection(MoteurBudgetaire.Section s) {
        return switch (s) {
            case PRODUITS -> "Produits";
            case CHARGES -> "Charges";
            case INVESTISSEMENTS -> "Investissements";
        };
    }

    private static int ordreSection(String section) {
        return MoteurBudgetaire.Section.valueOf(section).ordinal();
    }

    // ---------------------------------------------------------------------
    // Outils
    // ---------------------------------------------------------------------

    /** Taux d'une note en devise etrangere : celui fige a sa transmission, sinon le taux du jour (null si aucun). */
    BigDecimal tauxDeLaNote(NoteFrais n) {
        if (n.getDevise() == null || n.getDevise() == ConversionDeviseService.DEVISE_BASE) {
            return null;
        }
        return n.getTauxEngagement() != null ? n.getTauxEngagement() : conversion.tauxCourant();
    }

    /** Montant en devise de base ; null si la devise est etrangere et qu'aucun taux n'est disponible. */
    BigDecimal versBase(BigDecimal montant, Devise devise, BigDecimal taux) {
        if (montant == null) {
            return BigDecimal.ZERO;
        }
        if (devise == null || devise == ConversionDeviseService.DEVISE_BASE) {
            return montant;
        }
        if (taux == null || taux.signum() <= 0) {
            return null;
        }
        return conversion.enDeviseBase(montant, devise, taux).montantBase();
    }

    static Instant debut(int exercice) {
        return LocalDate.of(exercice, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    /** Debut de la periode du budget (inclus). */
    static Instant debutPeriode(Budget budget) {
        return budget.premierJour().atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    /** Fin de la periode du budget (exclue : lendemain de son dernier jour). */
    static Instant finPeriode(Budget budget) {
        return budget.dernierJour().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    static int moisDe(Instant instant) {
        return (instant == null ? LocalDate.now() : LocalDate.ofInstant(instant, ZoneId.systemDefault())).getMonthValue();
    }

    /** Mois ecoules de l'exercice a la date du jour : 0 avant son debut, 12 apres sa fin. */
    static int moisEcoules(int exercice) {
        LocalDate aujourdhui = LocalDate.now();
        if (aujourdhui.getYear() < exercice) {
            return 0;
        }
        return aujourdhui.getYear() > exercice ? 12 : aujourdhui.getMonthValue();
    }

    static Integer classe(String numero) {
        return numero == null || numero.isEmpty() || !Character.isDigit(numero.charAt(0)) ? null : numero.charAt(0) - '0';
    }

    private static void ajouter(BigDecimal[] serie, int mois, BigDecimal valeur) {
        if (serie != null && valeur != null) {
            serie[mois - 1] = serie[mois - 1].add(valeur);
        }
    }

    private static BigDecimal[] serie(Map<Long, Map<String, BigDecimal[]>> parService, Long service, String compte) {
        return parService.computeIfAbsent(service, k -> new TreeMap<>()).computeIfAbsent(compte, k -> MoteurBudgetaire.zeros());
    }

    private static BigDecimal montant(Object valeur) {
        if (valeur == null) {
            return BigDecimal.ZERO;
        }
        return valeur instanceof BigDecimal b ? b : new BigDecimal(valeur.toString());
    }

    private static String fmt(BigDecimal v) {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.FRANCE);
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return f.format(v == null ? BigDecimal.ZERO : v);
    }

    private static String nom(NoteFrais n) {
        var u = n.getCreateur();
        if (u == null) {
            return null;
        }
        return ((u.getPrenom() == null ? "" : u.getPrenom()) + " " + (u.getNom() == null ? "" : u.getNom())).trim();
    }
}
