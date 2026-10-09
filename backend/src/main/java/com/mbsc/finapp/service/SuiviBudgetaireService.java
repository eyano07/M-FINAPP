package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Budget;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.LigneBudget;
import com.mbsc.finapp.domain.LigneNoteFrais;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.StatutControleBudget;
import com.mbsc.finapp.domain.enums.StatutNote;
import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse.CompteHorsBudget;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse.LigneSuivi;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse.NoteHorsBudget;
import com.mbsc.finapp.dto.budget.SuiviBudgetResponse.TotalSuivi;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.NoteFraisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Realise et engage d'un budget, calcules a la demande (jamais stockes, donc jamais desynchronises).
 *
 * <ul>
 *   <li><b>Realise</b> : mouvements du grand livre de l'exercice sur les comptes de la ligne et leurs
 *       sous-comptes (pieces non brouillon et operations de tresorerie, hors a-nouveaux, reevaluations de
 *       change et piece de cloture). Chaque compte se range sur la ligne la plus precise qui le couvre.</li>
 *   <li><b>Engage</b> : notes de frais approuvees par le DA mais pas encore payees (VALIDEE_DA,
 *       TRANSMISE_CAISSE), au montant HT converti en devise de base au taux d'engagement de la note. Une fois
 *       payee, la note passe dans le realise par ses ecritures.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SuiviBudgetaireService {

    /** Notes approuvees non encore payees : engagements budgetaires. */
    static final Set<StatutNote> STATUTS_ENGAGES = EnumSet.of(StatutNote.VALIDEE_DA, StatutNote.TRANSMISE_CAISSE);
    /** Compte de repli applique au paiement d'une ligne de note sans compte (voir EcritureComptableService). */
    static final String COMPTE_REPLI = "6588";
    private static final String DEVISE = ConversionDeviseService.DEVISE_BASE.name();

    private final EcritureGrandLivreRepository ecritureRepository;
    private final NoteFraisRepository noteRepository;
    private final CompteOHADARepository compteRepository;
    private final ConversionDeviseService conversion;

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

    /** Mouvements de l'exercice, par compte, sur les classes budgetaires (2, 6, 7, 8). */
    @Transactional(readOnly = true)
    public Map<String, MouvementsCompte> mouvementsDeLExercice(int exercice) {
        Map<String, MouvementsCompte> parCompte = new TreeMap<>();
        for (Object[] row : ecritureRepository.mouvementsMensuelsBudgetaires(
                LocalDate.of(exercice, 1, 1), LocalDate.of(exercice, 12, 31))) {
            String numero = (String) row[0];
            TypeCompte type = (TypeCompte) row[1];
            int mois = ((Number) row[2]).intValue();
            MouvementsCompte m = parCompte.computeIfAbsent(numero,
                n -> new MouvementsCompte(n, type, MoteurBudgetaire.zeros(), MoteurBudgetaire.zeros()));
            m.debit()[mois - 1] = m.debit()[mois - 1].add((BigDecimal) row[3]);
            m.credit()[mois - 1] = m.credit()[mois - 1].add((BigDecimal) row[4]);
        }
        return parCompte;
    }

    /** Un engagement : une ligne de note approuvee non payee, en devise de base. */
    public record Engagement(Long noteId, String compteNumero, int mois, BigDecimal montantBase) {
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
            for (LigneNoteFrais l : n.getLignes()) {
                BigDecimal base = versBase(l.montantHtTotal(), n.getDevise(), taux);
                if (base == null) {
                    avertissements.add("Note " + n.getReference() + " : taux de change manquant, engagement en "
                        + n.getDevise() + " non compte.");
                    break;
                }
                String compte = l.getCompteImputation() == null ? COMPTE_REPLI : l.getCompteImputation().getNumero();
                engagements.add(new Engagement(n.getId(), compte, mois, base));
            }
        }
        return engagements;
    }

    /** Realise et engage mois par mois de chaque ligne du budget (cle : compte de la ligne). */
    public record ExecutionLignes(Map<String, BigDecimal[]> realise, Map<String, BigDecimal[]> engage,
                                  Map<String, BigDecimal[]> horsBudget, Map<String, MouvementsCompte> mouvements) {
    }

    /**
     * Range le realise et l'engage de l'exercice sur les lignes du budget (ligne la plus precise couvrant le
     * compte). Ce qui ne se range nulle part est du hors budget (realise seulement : un engagement hors
     * budget apparait avec sa note dans la liste des notes hors budget).
     */
    public ExecutionLignes repartirSurLignes(Budget budget, Map<String, MouvementsCompte> mouvements, List<Engagement> engagements) {
        Map<String, TypeCompte> typeDesLignes = new LinkedHashMap<>();
        for (LigneBudget l : budget.getLignes()) {
            typeDesLignes.put(l.getCompte().getNumero(), l.getCompte().getType());
        }
        Map<String, BigDecimal[]> realise = new HashMap<>();
        Map<String, BigDecimal[]> engage = new HashMap<>();
        typeDesLignes.keySet().forEach(c -> {
            realise.put(c, MoteurBudgetaire.zeros());
            engage.put(c, MoteurBudgetaire.zeros());
        });
        Map<String, BigDecimal[]> horsBudget = new TreeMap<>();
        for (MouvementsCompte m : mouvements.values()) {
            String ligne = MoteurBudgetaire.ligneCouvrant(m.numero(), typeDesLignes.keySet());
            if (ligne != null) {
                realise.put(ligne, MoteurBudgetaire.additionner(realise.get(ligne), m.realiseSelon(typeDesLignes.get(ligne))));
            } else if (MoteurBudgetaire.estBudgetable(m.numero(), classe(m.numero()))) {
                horsBudget.put(m.numero(), m.realiseSelon(m.type()));
            }
        }
        for (Engagement e : engagements) {
            String ligne = MoteurBudgetaire.ligneCouvrant(e.compteNumero(), typeDesLignes.keySet());
            if (ligne != null) {
                BigDecimal[] serie = engage.get(ligne);
                serie[e.mois() - 1] = serie[e.mois() - 1].add(e.montantBase());
            }
        }
        return new ExecutionLignes(realise, engage, horsBudget, mouvements);
    }

    // ---------------------------------------------------------------------
    // Suivi complet
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SuiviBudgetResponse suivre(Budget budget) {
        int exercice = budget.getExercice();
        List<String> avertissements = new ArrayList<>();
        Map<String, MouvementsCompte> mouvements = mouvementsDeLExercice(exercice);
        List<Engagement> engagements = engagementsDeLExercice(exercice, null, avertissements);
        ExecutionLignes exec = repartirSurLignes(budget, mouvements, engagements);
        int moisEcoules = moisEcoules(exercice);

        Map<String, CompteOHADA> plan = compteRepository.findAllByOrderByNumeroAsc().stream()
            .collect(Collectors.toMap(CompteOHADA::getNumero, Function.identity(), (a, b) -> a));

        List<LigneSuivi> lignes = budget.getLignes().stream()
            .sorted(Comparator.comparing(l -> l.getCompte().getNumero()))
            .map(l -> ligneSuivi(l, exec, moisEcoules))
            .toList();

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

        List<CompteHorsBudget> horsBudget = exec.horsBudget().entrySet().stream()
            .map(e -> {
                MouvementsCompte m = mouvements.get(e.getKey());
                CompteOHADA c = plan.get(e.getKey());
                BigDecimal annuel = MoteurBudgetaire.total(e.getValue());
                return new CompteHorsBudget(e.getKey(), c == null ? null : c.getLibelle(),
                    MoteurBudgetaire.section(e.getKey(), m.type()).name(), Arrays.asList(e.getValue()), annuel);
            })
            .filter(h -> h.realiseAnnuel().signum() != 0)
            .sorted(Comparator.comparing((CompteHorsBudget h) -> h.realiseAnnuel().abs()).reversed())
            .toList();

        List<NoteHorsBudget> notesHorsBudget = noteRepository.findHorsBudgetEntre(
                EnumSet.of(StatutControleBudget.DEPASSEMENT, StatutControleBudget.HORS_BUDGET, StatutControleBudget.SANS_BUDGET),
                debut(exercice), debut(exercice + 1)).stream()
            .map(n -> new NoteHorsBudget(n.getId(), n.getReference(), n.getObjet(), n.getMontant(),
                n.getDevise() == null ? null : n.getDevise().name(), n.getStatut().name(),
                n.getStatutBudget().name(), n.getJustificationBudget(), nom(n), n.getDateCreation()))
            .toList();

        // Un exercice futur n'a pas encore d'écritures : l'avertissement n'aurait aucun sens.
        if (mouvements.isEmpty() && exercice <= LocalDate.now().getYear()) {
            avertissements.add("Aucune écriture comptable sur les classes 2, 6, 7 et 8 pour l'exercice " + exercice
                + " : le réalisé est nul.");
        }
        if (!horsBudget.isEmpty()) {
            avertissements.add(horsBudget.size() == 1
                ? "1 compte mouvementé en " + exercice
                    + " n'est couvert par aucune ligne du budget (voir « Dépenses et recettes hors budget »)."
                : horsBudget.size() + " comptes mouvementés en " + exercice
                    + " ne sont couverts par aucune ligne du budget (voir « Dépenses et recettes hors budget »).");
        }

        return new SuiviBudgetResponse(budget.getId(), budget.getReference(), budget.getIntitule(), exercice,
            budget.getStatut(), budget.getNumeroRevision(), LocalDate.now(), moisEcoules, DEVISE,
            lignes, sections, natures, resultat, horsBudget, notesHorsBudget, List.copyOf(avertissements));
    }

    /** Totaux realises par section pour la liste des budgets (une seule lecture du grand livre par exercice). */
    public Map<MoteurBudgetaire.Section, BigDecimal[]> realiseParSection(Budget budget, Map<String, MouvementsCompte> mouvements) {
        ExecutionLignes exec = repartirSurLignes(budget, mouvements, List.of());
        Map<MoteurBudgetaire.Section, BigDecimal[]> r = new LinkedHashMap<>();
        for (LigneBudget l : budget.getLignes()) {
            MoteurBudgetaire.Section s = MoteurBudgetaire.section(l.getCompte().getNumero(), l.getCompte().getType());
            r.merge(s, exec.realise().get(l.getCompte().getNumero()), MoteurBudgetaire::additionner);
        }
        return r;
    }

    // ---------------------------------------------------------------------
    // Construction
    // ---------------------------------------------------------------------

    private LigneSuivi ligneSuivi(LigneBudget l, ExecutionLignes exec, int moisEcoules) {
        String numero = l.getCompte().getNumero();
        BigDecimal[] prevu = MoteurBudgetaire.mensuelDepuis(l.getMois());
        BigDecimal[] realise = exec.realise().get(numero);
        BigDecimal[] engage = exec.engage().get(numero);
        BigDecimal prevuAnnuel = MoteurBudgetaire.total(prevu);
        BigDecimal realiseAnnuel = MoteurBudgetaire.total(realise);
        BigDecimal engageAnnuel = MoteurBudgetaire.total(engage);
        BigDecimal prevuADate = MoteurBudgetaire.cumul(prevu, moisEcoules);
        BigDecimal realiseADate = MoteurBudgetaire.cumul(realise, moisEcoules);
        return new LigneSuivi(l.getId(), numero, l.getCompte().getLibelle(),
            MoteurBudgetaire.section(numero, l.getCompte().getType()).name(), MoteurBudgetaire.nature(numero),
            l.getCommentaire(), Arrays.asList(prevu), Arrays.asList(realise), Arrays.asList(engage),
            prevuAnnuel, realiseAnnuel, engageAnnuel,
            prevuAnnuel.subtract(realiseAnnuel).subtract(engageAnnuel),
            realiseAnnuel.subtract(prevuAnnuel), MoteurBudgetaire.taux(realiseAnnuel, prevuAnnuel),
            prevuADate, realiseADate, realiseADate.subtract(prevuADate));
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

    private static String nom(NoteFrais n) {
        var u = n.getCreateur();
        if (u == null) {
            return null;
        }
        return ((u.getPrenom() == null ? "" : u.getPrenom()) + " " + (u.getNom() == null ? "" : u.getNom())).trim();
    }
}
