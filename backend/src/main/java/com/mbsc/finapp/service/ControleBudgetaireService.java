package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Budget;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.LigneBudget;
import com.mbsc.finapp.domain.LigneNoteFrais;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.SensTransaction;
import com.mbsc.finapp.domain.enums.StatutBudget;
import com.mbsc.finapp.domain.enums.StatutControleBudget;
import com.mbsc.finapp.dto.budget.ControleBudgetaireRequest;
import com.mbsc.finapp.dto.budget.ControleBudgetaireResponse;
import com.mbsc.finapp.dto.budget.ControleBudgetaireResponse.LigneControle;
import com.mbsc.finapp.repository.BudgetRepository;
import com.mbsc.finapp.repository.CompteOHADARepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Controle budgetaire d'une depense : toute depense doit etre couverte par le budget en execution de son
 * exercice ; sinon (hors budget, depassement du disponible, aucun budget) la note de frais doit le justifier.
 *
 * <p>Chaque ligne de la note est rapprochee de la ligne budgetaire la plus precise couvrant son compte, et son
 * montant HT (en devise de base) compare au <b>disponible cumule a fin de mois</b> : prevu de janvier au mois
 * de la depense - realise sur la meme periode - engagements en cours (notes approuvees non payees, la note
 * controlee exceptee). Plusieurs lignes d'une meme note sur une meme ligne budgetaire se cumulent.</p>
 *
 * <p>Ne sont pas des depenses budgetaires (statut NON_CONCERNE) : notes d'encaissement, lignes imputees a un
 * compte de tiers ou de tresorerie (reglement d'une dette fournisseur deja constatee, avance au personnel...).</p>
 */
@Service
@RequiredArgsConstructor
public class ControleBudgetaireService {

    private static final Locale FR = Locale.FRANCE;

    private final BudgetRepository budgetRepository;
    private final CompteOHADARepository compteRepository;
    private final SuiviBudgetaireService suivi;
    private final ConversionDeviseService conversion;

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
        BigDecimal taux = devise == ConversionDeviseService.DEVISE_BASE ? null : conversion.tauxCourant();
        return controler(depenses, devise, taux, LocalDate.now(), req.noteId());
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
        BigDecimal taux = suivi.tauxDeLaNote(note);
        LocalDate date = note.getDateCreation() == null ? LocalDate.now()
            : LocalDate.ofInstant(note.getDateCreation(), java.time.ZoneId.systemDefault());
        return controler(depenses, devise, taux, date, note.getId());
    }

    // ---------------------------------------------------------------------

    private ControleBudgetaireResponse controler(List<Depense> depenses, Devise devise, BigDecimal taux,
                                                 LocalDate date, Long noteId) {
        int exercice = date.getYear();
        int mois = date.getMonthValue();
        List<String> avertissements = new ArrayList<>();
        Optional<Budget> budgetOpt = budgetRepository.findFirstByExerciceAndStatut(exercice, StatutBudget.EN_EXECUTION);

        Map<String, CompteOHADA> comptes = new HashMap<>();
        Map<String, LigneBudget> lignesBudget = new HashMap<>();
        SuiviBudgetaireService.ExecutionLignes exec = null;
        if (budgetOpt.isPresent()) {
            Budget budget = budgetOpt.get();
            budget.getLignes().forEach(l -> lignesBudget.put(l.getCompte().getNumero(), l));
            exec = suivi.repartirSurLignes(budget, suivi.mouvementsDeLExercice(exercice),
                suivi.engagementsDeLExercice(exercice, noteId, avertissements));
        }

        // Consommation deja imputee par les lignes precedentes de la meme note, par ligne budgetaire.
        Map<String, BigDecimal> dejaConsomme = new HashMap<>();
        List<LigneControle> resultats = new ArrayList<>();
        StatutControleBudget global = StatutControleBudget.NON_CONCERNE;
        String nomMois = Month.of(mois).getDisplayName(TextStyle.FULL, FR);

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
            } else if (budgetOpt.isEmpty()) {
                ligne = ligne(i, numero, compte.getLibelle(), montant, montantBase, StatutControleBudget.SANS_BUDGET, null, null,
                    "Aucun budget en exécution pour l'exercice " + exercice + " : la dépense est hors budget.");
            } else {
                String compteLigne = MoteurBudgetaire.ligneCouvrant(numero, lignesBudget.keySet());
                if (compteLigne == null) {
                    ligne = ligne(i, numero, compte.getLibelle(), montant, montantBase, StatutControleBudget.HORS_BUDGET, null, null,
                        "Le compte " + numero + " n'est couvert par aucune ligne du budget " + budgetOpt.get().getReference() + ".");
                } else {
                    LigneBudget lb = lignesBudget.get(compteLigne);
                    BigDecimal engage = MoteurBudgetaire.total(exec.engage().get(compteLigne))
                        .add(dejaConsomme.getOrDefault(compteLigne, BigDecimal.ZERO));
                    MoteurBudgetaire.Disponibilite dispo = MoteurBudgetaire.disponibilite(
                        MoteurBudgetaire.mensuelDepuis(lb.getMois()), exec.realise().get(compteLigne), engage, mois);
                    StatutControleBudget statut;
                    String message;
                    if (montantBase == null) {
                        statut = StatutControleBudget.DEPASSEMENT;
                        message = "Taux de change manquant : le disponible ne peut pas être vérifié (justification requise).";
                        avertissements.add("Aucun taux de change : les montants en " + devise + " ne peuvent pas être convertis en "
                            + ConversionDeviseService.DEVISE_BASE + ".");
                    } else {
                        statut = MoteurBudgetaire.statut(montantBase, dispo);
                        message = statut == StatutControleBudget.CONFORME
                            ? "Budgété sur " + compteLigne + " : disponible à fin " + nomMois + " " + fmt(dispo.disponibleCumule()) + " " + ConversionDeviseService.DEVISE_BASE + "."
                            : "Dépasse le disponible à fin " + nomMois + " sur " + compteLigne + " : disponible "
                                + fmt(dispo.disponibleCumule()) + ", demandé " + fmt(montantBase) + " " + ConversionDeviseService.DEVISE_BASE + ".";
                        dejaConsomme.merge(compteLigne, montantBase, BigDecimal::add);
                    }
                    ligne = new LigneControle(i, numero, compte.getLibelle(), montant, montantBase, statut,
                        compteLigne, lb.getCompte().getLibelle(), dispo.prevuCumule(), dispo.realiseCumule(), dispo.engage(),
                        dispo.disponibleCumule(), dispo.prevuAnnuel(), dispo.disponibleAnnuel(), message);
                }
            }
            resultats.add(ligne);
            if (ligne.statut().gravite() > global.gravite()) {
                global = ligne.statut();
            }
        }

        Budget b = budgetOpt.orElse(null);
        return new ControleBudgetaireResponse(exercice, mois,
            b == null ? null : b.getId(), b == null ? null : b.getReference(), b == null ? null : b.getIntitule(),
            global, global.exigeJustification(), devise.name(), taux, resultats, avertissements.stream().distinct().toList());
    }

    private LigneControle ligne(int index, String numero, String libelle, BigDecimal montant, BigDecimal montantBase,
                                StatutControleBudget statut, String compteLigne, String libelleLigne, String message) {
        return new LigneControle(index, numero, libelle, montant, montantBase, statut, compteLigne, libelleLigne,
            null, null, null, null, null, null, message);
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
            StatutControleBudget.NON_CONCERNE, false, note.getDevise() == null ? null : note.getDevise().name(), null, lignes, List.of());
    }

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
