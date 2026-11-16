package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Budget;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.LigneBudget;
import com.mbsc.finapp.domain.LigneNoteFrais;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.ServiceBudgetaire;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.domain.enums.SensTransaction;
import com.mbsc.finapp.domain.enums.StatutBudget;
import com.mbsc.finapp.domain.enums.StatutControleBudget;
import com.mbsc.finapp.dto.budget.ControleBudgetaireRequest;
import com.mbsc.finapp.dto.budget.ControleBudgetaireResponse;
import com.mbsc.finapp.dto.budget.ControleBudgetaireResponse.LigneControle;
import com.mbsc.finapp.repository.BudgetRepository;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.ServiceBudgetaireRepository;
import com.mbsc.finapp.service.ImputationBudgetaire.Enveloppe;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Controle budgetaire d'une depense : toute depense doit etre couverte par le budget en execution sur le mois de
 * la depense (budget annuel, ou budget d'un semestre, d'un trimestre, d'un mois) ; sinon (hors budget,
 * depassement du disponible, aucun budget) la note de frais le signale.
 *
 * <p>Chaque ligne de la note est rapprochee de la ligne budgetaire la plus precise couvrant son compte, et son
 * montant HT (en devise de base) compare au <b>disponible cumule a fin de mois</b> : prevu du debut de la periode
 * au mois de la depense - realise sur la meme periode - engagements en cours (notes approuvees non payees, la note
 * controlee exceptee). Plusieurs lignes d'une meme note sur une meme ligne budgetaire se cumulent.</p>
 *
 * <p>Budget organise par service : une note rattachee a un service est controlee sur l'enveloppe de ce service (ses
 * lignes, ou a defaut les lignes generales) ; un compte que seuls d'autres services ont budgete est hors budget pour
 * lui. Une note sans service est controlee sur les lignes generales, ou a defaut sur le budget consolide (voir
 * {@link ImputationBudgetaire}).</p>
 *
 * <p>Module {@code BUDGET} desactive par l'administrateur : aucune depense n'est controlee ni rattachee au
 * budget (statut NON_CONCERNE, voir {@link #budgetDesactive}).</p>
 *
 * <p>Ne sont pas des depenses budgetaires (statut NON_CONCERNE) : notes d'encaissement, lignes imputees a un
 * compte de tiers ou de tresorerie (reglement d'une dette fournisseur deja constatee, avance au personnel...).</p>
 */
@Service
@RequiredArgsConstructor
public class ControleBudgetaireService {

    private static final Locale FR = Locale.FRANCE;
    private static final String DEVISE_BASE = ConversionDeviseService.DEVISE_BASE.name();

    private final BudgetRepository budgetRepository;
    private final CompteOHADARepository compteRepository;
    private final SuiviBudgetaireService suivi;
    private final ConversionDeviseService conversion;
    private final ModuleConfigService modules;
    private final ServiceBudgetaireRepository serviceRepository;

    /** Une depense a controler : compte (null = compte de repli 6588) et montant HT dans la devise de la note. */
    record Depense(String compteNumero, BigDecimal montant) {
    }

    /** Controle d'une note en cours de saisie (ecran de creation / modification), a la date du jour. */
    @Transactional(readOnly = true)
    public ControleBudgetaireResponse controler(ControleBudgetaireRequest req) {
        Devise devise = req.devise() == null ? Devise.CDF : req.devise();
        List<Depense> depenses = req.lignes().stream()
            .map(l -> new Depense(l.compteNumero(), montantHt(l.montant(), l.quantite(), l.achatMarchandise())))
            .toList();
        if (!modules.estActif(ModuleMetier.BUDGET)) {
            return budgetDesactive(depenses, devise, LocalDate.now());
        }
        BigDecimal taux = devise == ConversionDeviseService.DEVISE_BASE ? null : conversion.tauxCourant();
        ServiceBudgetaire service = req.serviceId() == null ? null : serviceRepository.findById(req.serviceId()).orElse(null);
        return controler(depenses, devise, taux, LocalDate.now(), req.noteId(), service);
    }

    /** Controle d'une note enregistree, a la date de sa creation et au taux d'engagement s'il est fige. */
    @Transactional(readOnly = true)
    public ControleBudgetaireResponse controlerNote(NoteFrais note) {
        if (note.getSens() == SensTransaction.ENCAISSEMENT) {
            return nonConcernee(note);
        }
        List<Depense> depenses = note.getLignes().stream()
            .map(l -> new Depense(l.getCompteImputation() == null ? null : l.getCompteImputation().getNumero(), l.montantHtTotal()))
            .toList();
        Devise devise = note.getDevise() == null ? Devise.CDF : note.getDevise();
        LocalDate date = note.getDateCreation() == null ? LocalDate.now()
            : LocalDate.ofInstant(note.getDateCreation(), java.time.ZoneId.systemDefault());
        if (!modules.estActif(ModuleMetier.BUDGET)) {
            return budgetDesactive(depenses, devise, date);
        }
        BigDecimal taux = suivi.tauxDeLaNote(note);
        return controler(depenses, devise, taux, date, note.getId(), note.getService());
    }

    /**
     * Aucun budget en execution sur ce mois : les budgets approuves (ou soumis) qui le couvrent, pour dire ce qui manque
     * (« approuve mais pas encore mis en execution »), au lieu d'un simple « aucun budget » qui laisse croire qu'il n'existe pas.
     */
    Optional<String> budgetsEnAttente(int exercice, int mois) {
        List<Budget> enAttente = budgetRepository.findByExercice(exercice).stream()
            .filter(b -> b.couvreMois(mois) && (b.getStatut() == StatutBudget.APPROUVE || b.getStatut() == StatutBudget.SOUMIS))
            .sorted(Comparator.comparing((Budget b) -> b.getStatut() == StatutBudget.APPROUVE ? 0 : 1).thenComparing(Budget::getReference))
            .toList();
        if (enAttente.isEmpty()) {
            return Optional.empty();
        }
        List<String> approuves = enAttente.stream().filter(b -> b.getStatut() == StatutBudget.APPROUVE).map(ControleBudgetaireService::designation).toList();
        List<String> soumis = enAttente.stream().filter(b -> b.getStatut() == StatutBudget.SOUMIS).map(ControleBudgetaireService::designation).toList();
        StringBuilder texte = new StringBuilder();
        if (!approuves.isEmpty()) {
            texte.append(approuves.size() == 1
                ? "Le budget " + approuves.get(0) + " est approuvé mais pas encore mis en exécution : ouvrez-le et cliquez "
                    + "« Mettre en exécution » pour qu'il contrôle les dépenses."
                : "Les budgets " + String.join(", ", approuves) + " sont approuvés mais pas encore mis en exécution : mettez-en "
                    + "un en exécution (un seul à la fois sur un même mois) pour qu'il contrôle les dépenses.");
        }
        if (!soumis.isEmpty()) {
            if (texte.length() > 0) {
                texte.append(' ');
            }
            texte.append(soumis.size() == 1 ? "Le budget " + soumis.get(0) + " attend l'approbation du DA."
                : "Les budgets " + String.join(", ", soumis) + " attendent l'approbation du DA.");
        }
        return Optional.of(texte.toString());
    }

    private static String designation(Budget b) {
        return b.getReference() + " (" + b.libellePeriode() + ")";
    }

    /** Budget en execution couvrant ce mois de cet exercice (au plus un : regle du service et contrainte V122). */
    Optional<Budget> budgetEnExecution(int exercice, int mois) {
        return budgetRepository.findByExerciceAndStatut(exercice, StatutBudget.EN_EXECUTION).stream()
            .filter(b -> b.couvreMois(mois))
            .findFirst();
    }

    // ---------------------------------------------------------------------

    private ControleBudgetaireResponse controler(List<Depense> depenses, Devise devise, BigDecimal taux,
                                                 LocalDate date, Long noteId, ServiceBudgetaire service) {
        int exercice = date.getYear();
        int mois = date.getMonthValue();
        Long serviceId = service == null ? null : service.getId();
        List<String> avertissements = new ArrayList<>();
        Optional<Budget> budgetOpt = budgetEnExecution(exercice, mois);
        String nomMois = Month.of(mois).getDisplayName(TextStyle.FULL, FR);

        Map<String, CompteOHADA> comptes = new HashMap<>();
        BudgetControle env = null;
        if (budgetOpt.isPresent()) {
            Budget budget = budgetOpt.get();
            env = new BudgetControle(budget, suivi.repartirSurLignes(budget, suivi.mouvementsDetailles(exercice),
                suivi.engagementsDeLExercice(exercice, noteId, avertissements)));
        } else {
            budgetsEnAttente(exercice, mois).ifPresent(avertissements::add);
        }

        // Consommation deja imputee par les lignes precedentes de la meme note, par enveloppe controlee.
        Map<String, BigDecimal> dejaConsomme = new HashMap<>();
        List<LigneControle> resultats = new ArrayList<>();
        StatutControleBudget global = StatutControleBudget.NON_CONCERNE;

        for (int i = 0; i < depenses.size(); i++) {
            Depense d = depenses.get(i);
            String numero = StringUtils.hasText(d.compteNumero()) ? d.compteNumero().trim() : SuiviBudgetaireService.COMPTE_REPLI;
            CompteOHADA compte = comptes.computeIfAbsent(numero, n -> compteRepository.findByNumero(n).orElse(null));
            BigDecimal montant = d.montant() == null ? BigDecimal.ZERO : d.montant();
            BigDecimal montantBase = suivi.versBase(montant, devise, taux);

            LigneControle ligne;
            if (compte == null) {
                ligne = ligne(i, numero, null, montant, montantBase, StatutControleBudget.HORS_BUDGET, null, null,
                    "Compte " + numero + " absent du plan comptable : dépense non rattachable au budget.");
            } else if (!MoteurBudgetaire.estDepenseBudgetaire(numero, compte.getClasse(), compte.getType())) {
                ligne = ligne(i, numero, compte.getLibelle(), montant, montantBase, StatutControleBudget.NON_CONCERNE, null, null,
                    "Pas une dépense budgétaire (règlement de dette, avance, trésorerie...) : non soumise au contrôle.");
            } else if (env == null) {
                ligne = ligne(i, numero, compte.getLibelle(), montant, montantBase, StatutControleBudget.SANS_BUDGET, null, null,
                    "Aucun budget en exécution pour " + nomMois + " " + exercice + " : la dépense est hors budget.");
            } else {
                ligne = controlerLigne(i, numero, compte, montant, montantBase, devise, service, mois, nomMois, env,
                    dejaConsomme, avertissements);
            }
            resultats.add(ligne);
            if (ligne.statut().gravite() > global.gravite()) {
                global = ligne.statut();
            }
        }

        Budget b = budgetOpt.orElse(null);
        return new ControleBudgetaireResponse(exercice, mois,
            b == null ? null : b.getId(), b == null ? null : b.getReference(), b == null ? null : b.getIntitule(),
            global, global.exigeJustification(), devise.name(), taux, resultats, avertissements.stream().distinct().toList(), true,
            b == null ? null : b.libellePeriode(), serviceId,
            service == null ? null : service.getNom(), service == null ? null : service.getResponsable());
    }

    /** Lignes du budget en execution et leur execution, consolidees et par enveloppe. */
    private static final class BudgetControle {
        final Budget budget;
        final SuiviBudgetaireService.ExecutionLignes exec;
        final ImputationBudgetaire imputation;
        final Map<Enveloppe, LigneBudget> lignes = new HashMap<>();
        final Map<String, List<LigneBudget>> parCompte = new HashMap<>();

        BudgetControle(Budget budget, SuiviBudgetaireService.ExecutionLignes exec) {
            this.budget = budget;
            this.exec = exec;
            this.imputation = exec.imputation();
            for (LigneBudget l : budget.getLignes()) {
                lignes.put(ImputationBudgetaire.enveloppeDe(l), l);
                parCompte.computeIfAbsent(l.getCompte().getNumero(), k -> new ArrayList<>()).add(l);
            }
        }

        /** Prevu mensuel de la ligne consolidee (toutes les lignes de ce compte, tous services confondus). */
        BigDecimal[] prevuConsolide(String compte) {
            BigDecimal[] prevu = MoteurBudgetaire.zeros();
            for (LigneBudget l : parCompte.getOrDefault(compte, List.of())) {
                prevu = MoteurBudgetaire.additionner(prevu, MoteurBudgetaire.mensuelDepuis(l.getMois()));
            }
            return prevu;
        }
    }

    private LigneControle controlerLigne(int index, String numero, CompteOHADA compte, BigDecimal montant, BigDecimal montantBase,
                                         Devise devise, ServiceBudgetaire service, int mois, String nomMois, BudgetControle env,
                                         Map<String, BigDecimal> dejaConsomme, List<String> avertissements) {
        Long serviceId = service == null ? null : service.getId();
        String consolidee = env.imputation.ligneConsolidee(numero);
        if (consolidee == null) {
            return ligne(index, numero, compte.getLibelle(), montant, montantBase, StatutControleBudget.HORS_BUDGET, null, null,
                "Le compte " + numero + " n'est couvert par aucune ligne du budget " + env.budget.getReference() + ".");
        }
        Enveloppe enveloppe = env.imputation.enveloppe(numero, serviceId);
        if (enveloppe == null && serviceId != null) {
            String services = env.parCompte.getOrDefault(consolidee, List.of()).stream()
                .filter(l -> l.getService() != null).map(l -> l.getService().getNom()).distinct().sorted()
                .collect(Collectors.joining(", "));
            return ligne(index, numero, compte.getLibelle(), montant, montantBase, StatutControleBudget.HORS_BUDGET, null, null,
                "Le service " + service.getNom() + " n'a pas de ligne budgétaire couvrant le compte " + numero
                    + (services.isEmpty() ? "" : " (il est budgété pour : " + services + ")") + ".");
        }

        // Enveloppe controlee : celle du service ou des lignes generales, a defaut le budget consolide (note sans
        // service sur un compte que seuls des services ont budgete).
        String cle;
        String libelleEnveloppe;
        String compteLigne;
        String libelleLigne;
        BigDecimal[] prevu;
        BigDecimal[] realise;
        BigDecimal engageLigne;
        if (enveloppe != null) {
            LigneBudget lb = env.lignes.get(enveloppe);
            cle = (enveloppe.serviceId() == null ? "G" : "S" + enveloppe.serviceId()) + "|" + enveloppe.compte();
            libelleEnveloppe = enveloppe.serviceId() != null ? "service " + lb.getService().getNom()
                : env.imputation.parService() ? "lignes générales" : null;
            compteLigne = enveloppe.compte();
            libelleLigne = lb.getCompte().getLibelle();
            prevu = MoteurBudgetaire.mensuelDepuis(lb.getMois());
            realise = env.exec.realiseEnveloppe().get(enveloppe);
            engageLigne = MoteurBudgetaire.total(env.exec.engageEnveloppe().get(enveloppe));
        } else {
            cle = "C|" + consolidee;
            libelleEnveloppe = "budget consolidé";
            compteLigne = consolidee;
            libelleLigne = env.parCompte.get(consolidee).get(0).getCompte().getLibelle();
            prevu = env.prevuConsolide(consolidee);
            realise = env.exec.realise().get(consolidee);
            engageLigne = MoteurBudgetaire.total(env.exec.engage().get(consolidee));
            avertissements.add("Aucun service choisi : le compte " + consolidee + " n'est budgété que par service, la dépense est "
                + "contrôlée sur le budget consolidé sans être suivie sur le budget d'un service.");
        }
        BigDecimal engage = engageLigne.add(dejaConsomme.getOrDefault(cle, BigDecimal.ZERO));
        MoteurBudgetaire.Disponibilite dispo = MoteurBudgetaire.disponibilite(prevu, realise, engage, mois);
        String surLigne = compteLigne + (libelleEnveloppe == null ? "" : " (" + libelleEnveloppe + ")");
        StatutControleBudget statut;
        String message;
        if (montantBase == null) {
            statut = StatutControleBudget.DEPASSEMENT;
            message = "Taux de change manquant : le disponible ne peut pas être vérifié (justification requise).";
            avertissements.add("Aucun taux de change : les montants en " + devise + " ne peuvent pas être convertis en "
                + DEVISE_BASE + ".");
        } else {
            statut = MoteurBudgetaire.statut(montantBase, dispo);
            message = statut == StatutControleBudget.CONFORME
                ? "Budgété sur " + surLigne + " : disponible à fin " + nomMois + " " + fmt(dispo.disponibleCumule()) + " " + DEVISE_BASE + "."
                : "Dépasse le disponible à fin " + nomMois + " sur " + surLigne + " : disponible "
                    + fmt(dispo.disponibleCumule()) + ", demandé " + fmt(montantBase) + " " + DEVISE_BASE + ".";
            dejaConsomme.merge(cle, montantBase, BigDecimal::add);
        }
        return new LigneControle(index, numero, compte.getLibelle(), montant, montantBase, statut,
            compteLigne, libelleLigne, dispo.prevuCumule(), dispo.realiseCumule(), dispo.engage(),
            dispo.disponibleCumule(), dispo.prevuAnnuel(), dispo.disponibleAnnuel(), message, libelleEnveloppe);
    }

    private LigneControle ligne(int index, String numero, String libelle, BigDecimal montant, BigDecimal montantBase,
                                StatutControleBudget statut, String compteLigne, String libelleLigne, String message) {
        return new LigneControle(index, numero, libelle, montant, montantBase, statut, compteLigne, libelleLigne,
            null, null, null, null, null, null, message, null);
    }

    private ControleBudgetaireResponse nonConcernee(NoteFrais note) {
        LocalDate date = note.getDateCreation() == null ? LocalDate.now()
            : LocalDate.ofInstant(note.getDateCreation(), java.time.ZoneId.systemDefault());
        List<LigneControle> lignes = new ArrayList<>();
        int i = 0;
        for (LigneNoteFrais l : note.getLignes()) {
            CompteOHADA c = l.getCompteImputation();
            lignes.add(ligne(i++, c == null ? null : c.getNumero(), c == null ? null : c.getLibelle(), l.montantHtTotal(), null,
                StatutControleBudget.NON_CONCERNE, null, null, "Note d'encaissement : une recette n'est pas soumise au contrôle des dépenses."));
        }
        return new ControleBudgetaireResponse(date.getYear(), date.getMonthValue(), null, null, null,
            StatutControleBudget.NON_CONCERNE, false, note.getDevise() == null ? null : note.getDevise().name(), null, lignes,
            List.of(), true);
    }

    /**
     * Module Budget désactivé par l'administrateur : aucune dépense n'est contrôlée ni rattachée au budget
     * (statut NON_CONCERNE, pas de budget de référence, aucune justification exigée).
     */
    private ControleBudgetaireResponse budgetDesactive(List<Depense> depenses, Devise devise, LocalDate date) {
        List<LigneControle> lignes = new ArrayList<>();
        for (int i = 0; i < depenses.size(); i++) {
            Depense d = depenses.get(i);
            lignes.add(ligne(i, d.compteNumero(), null, d.montant() == null ? BigDecimal.ZERO : d.montant(), null,
                StatutControleBudget.NON_CONCERNE, null, null, MESSAGE_DESACTIVE));
        }
        return new ControleBudgetaireResponse(date.getYear(), date.getMonthValue(), null, null, null,
            StatutControleBudget.NON_CONCERNE, false, devise.name(), null, lignes, List.of(MESSAGE_DESACTIVE), false);
    }

    static final String MESSAGE_DESACTIVE =
        "Module Budget désactivé par l'administrateur : les dépenses ne sont pas rattachées au budget.";

    /** Montant HT d'une ligne saisie : prix unitaire x quantite pour un achat de marchandise, montant saisi sinon. */
    static BigDecimal montantHt(BigDecimal montant, BigDecimal quantite, Boolean achatMarchandise) {
        BigDecimal m = montant == null ? BigDecimal.ZERO : montant;
        if (Boolean.TRUE.equals(achatMarchandise) && quantite != null) {
            return m.multiply(quantite).setScale(2, RoundingMode.HALF_UP);
        }
        return m;
    }

    private static String fmt(BigDecimal v) {
        NumberFormat f = NumberFormat.getNumberInstance(FR);
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return f.format(v == null ? BigDecimal.ZERO : v);
    }
}
