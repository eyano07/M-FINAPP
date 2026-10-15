package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Budget;
import com.mbsc.finapp.domain.LigneBudget;
import com.mbsc.finapp.domain.ServiceBudgetaire;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.DureeBudget;
import com.mbsc.finapp.domain.enums.StatutBudget;
import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.dto.budget.BudgetRequest;
import com.mbsc.finapp.dto.budget.BudgetResponse;
import com.mbsc.finapp.dto.budget.LigneBudgetRequest;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.BudgetRepository;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.ServiceBudgetaireRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regles d'un budget par periode et par service : periode valide, montants dans la periode, recettes jamais rattachees a
 * un service, un compte une seule fois par service, pas de recouvrement dans le budget consolide, un seul budget en
 * execution sur un meme mois, cloture a la fin de la periode, revision sur la meme periode.
 */
class BudgetServiceReglesTest {

    private BudgetRepository budgets;
    private ServiceBudgetaireRepository services;
    private BudgetService service;

    private static final ServiceBudgetaire RESTAURANT = BudgetParServiceTest.RESTAURANT;
    private static final ServiceBudgetaire LOGISTIQUE = BudgetParServiceTest.LOGISTIQUE;
    private static final ServiceBudgetaire ANCIEN = ServiceBudgetaire.builder().id(9L).nom("Ancien atelier").responsable("X").actif(false).build();

    @BeforeEach
    void preparer() {
        budgets = mock(BudgetRepository.class);
        services = mock(ServiceBudgetaireRepository.class);
        CompteOHADARepository comptes = mock(CompteOHADARepository.class);
        when(comptes.findByNumero(anyString())).thenAnswer(i -> {
            String n = i.getArgument(0);
            return Optional.of(BudgetParServiceTest.compte(n, n.startsWith("7") ? TypeCompte.PRODUIT : TypeCompte.CHARGE));
        });
        when(services.findById(1L)).thenReturn(Optional.of(RESTAURANT));
        when(services.findById(2L)).thenReturn(Optional.of(LOGISTIQUE));
        when(services.findById(9L)).thenReturn(Optional.of(ANCIEN));
        when(budgets.save(any(Budget.class))).thenAnswer(i -> i.getArgument(0));
        ReferenceGenerator references = mock(ReferenceGenerator.class);
        when(references.pourBudget()).thenReturn("BUD-2026-000099");
        CurrentUserProvider courant = mock(CurrentUserProvider.class);
        when(courant.requireUser()).thenReturn(User.builder().id(7L).prenom("Bruno").nom("Kalunga").email("dfin_bruno").build());
        service = new BudgetService(budgets, comptes, references, courant, mock(SuiviBudgetaireService.class), services);
    }

    private static List<BigDecimal> decembre(String montant) {
        List<BigDecimal> m = new ArrayList<>(Collections.nCopies(12, BigDecimal.ZERO));
        m.set(11, new BigDecimal(montant));
        return m;
    }

    private static LigneBudgetRequest ligne(String compte, Long service, List<BigDecimal> mensuel) {
        return new LigneBudgetRequest(compte, mensuel, null, null, service);
    }

    private static BudgetRequest requete(DureeBudget duree, Integer periode, LigneBudgetRequest... lignes) {
        return new BudgetRequest("Budget de fin d'année", 2026, null, List.of(lignes), duree, periode);
    }

    // ---------------------------------------------------------------------
    // Creation
    // ---------------------------------------------------------------------

    @Test
    void un_budget_de_decembre_par_service_est_cree_et_consolide() {
        BudgetResponse b = service.creer(requete(DureeBudget.MENSUEL, 12,
            ligne("6011", 1L, decembre("1000")),
            ligne("6011", 2L, decembre("400")),          // meme compte dans deux services : il se cumule
            ligne("6042", 2L, decembre("500")),
            ligne("7011", null, decembre("3000"))));
        assertThat(b.duree()).isEqualTo(DureeBudget.MENSUEL);
        assertThat(b.libellePeriode()).isEqualTo("Décembre 2026");
        assertThat(b.moisDebut()).isEqualTo(12);
        assertThat(b.parService()).isTrue();
        assertThat(b.totalCharges()).isEqualByComparingTo("1900");
        assertThat(b.totalProduits()).isEqualByComparingTo("3000");
        assertThat(b.lignes()).extracting(l -> l.compteNumero() + "/" + l.serviceNom())
            .containsExactly("6011/Logistique", "6011/Restaurant", "6042/Logistique", "7011/null");
    }

    @Test
    void sans_duree_le_budget_reste_annuel() {
        BudgetResponse b = service.creer(new BudgetRequest("Budget 2026", 2026, null,
            List.of(new LigneBudgetRequest("6011", null, new BigDecimal("1200"), null, null)), null, null));
        assertThat(b.duree()).isEqualTo(DureeBudget.ANNUEL);
        assertThat(b.libellePeriode()).isEqualTo("Exercice 2026");
        assertThat(b.lignes().get(0).mensuel()).allSatisfy(v -> assertThat(v).isEqualByComparingTo("100"));
    }

    @Test
    void un_montant_de_periode_se_repartit_sur_les_mois_de_la_periode() {
        BudgetResponse b = service.creer(requete(DureeBudget.TRIMESTRIEL, 4,
            new LigneBudgetRequest("6011", null, new BigDecimal("900"), null, 1L)));
        List<BigDecimal> m = b.lignes().get(0).mensuel();
        assertThat(m.subList(0, 9)).allSatisfy(v -> assertThat(v).isEqualByComparingTo("0"));
        assertThat(m.subList(9, 12)).allSatisfy(v -> assertThat(v).isEqualByComparingTo("300"));
    }

    @Test
    void periode_invalide_refusee() {
        assertThatThrownBy(() -> service.creer(requete(DureeBudget.TRIMESTRIEL, 5, ligne("6011", null, decembre("10")))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Période 5 invalide pour un budget trimestriel").hasMessageContaining("de 1 à 4");
    }

    @Test
    void un_montant_hors_de_la_periode_est_refuse() {
        List<BigDecimal> janvierEtDecembre = decembre("100");
        janvierEtDecembre.set(0, new BigDecimal("50"));
        assertThatThrownBy(() -> service.creer(requete(DureeBudget.MENSUEL, 12, ligne("6011", 1L, janvierEtDecembre))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Compte 6011 : le budget couvre « Décembre 2026 », il n'a pas de montant pour janvier.");
    }

    @Test
    void une_recette_ne_se_rattache_pas_a_un_service() {
        assertThatThrownBy(() -> service.creer(requete(DureeBudget.MENSUEL, 12, ligne("7011", 1L, decembre("100")))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("est une recette").hasMessageContaining("lignes générales");
    }

    @Test
    void un_compte_deux_fois_dans_le_meme_service_est_refuse() {
        assertThatThrownBy(() -> service.creer(requete(DureeBudget.MENSUEL, 12,
                ligne("6011", 1L, decembre("100")), ligne("6011", 1L, decembre("50")))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Le compte 6011 figure deux fois dans le service « Restaurant ».");
        assertThatThrownBy(() -> service.creer(requete(DureeBudget.MENSUEL, 12,
                ligne("6011", null, decembre("100")), ligne("6011", null, decembre("50")))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Le compte 6011 figure deux fois dans les lignes générales.");
    }

    @Test
    void un_compte_et_son_sous_compte_dans_deux_services_sont_refuses() {
        assertThatThrownBy(() -> service.creer(requete(DureeBudget.MENSUEL, 12,
                ligne("605", 1L, decembre("100")), ligne("6052", 2L, decembre("50")))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("605 couvre déjà 6052").hasMessageContaining("le même dans tous les services");
    }

    @Test
    void un_service_desactive_ne_recoit_plus_de_nouvelle_ligne() {
        assertThatThrownBy(() -> service.creer(requete(DureeBudget.MENSUEL, 12, ligne("6011", 9L, decembre("100")))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("« Ancien atelier » est désactivé");
    }

    @Test
    void une_ligne_existante_garde_son_service_desactive_depuis() {
        Budget existant = Budget.builder().id(30L).reference("BUD-2026-000030").intitule("Décembre").exercice(2026)
            .duree(DureeBudget.MENSUEL).periode(12).statut(StatutBudget.BROUILLON).build();
        existant.addLigne(BudgetParServiceTest.ligne(301, "6011", TypeCompte.CHARGE, ANCIEN, 12, "100"));
        when(budgets.findWithLignesById(30L)).thenReturn(Optional.of(existant));
        BudgetResponse b = service.modifier(30L, requete(DureeBudget.MENSUEL, 12, ligne("6011", 9L, decembre("120"))));
        assertThat(b.lignes()).extracting(l -> l.serviceNom()).containsExactly("Ancien atelier");
    }

    // ---------------------------------------------------------------------
    // Execution, revision, cloture
    // ---------------------------------------------------------------------

    private Budget budget(long id, DureeBudget duree, int periode, int exercice, StatutBudget statut) {
        Budget b = Budget.builder().id(id).reference("BUD-" + exercice + "-0000" + id).intitule("B" + id).exercice(exercice)
            .duree(duree).periode(periode).statut(statut).build();
        b.addLigne(BudgetParServiceTest.ligne(id * 10, "6011", TypeCompte.CHARGE, null, duree.moisFin(periode), "100"));
        when(budgets.findWithLignesById(id)).thenReturn(Optional.of(b));
        return b;
    }

    @Test
    void un_budget_de_decembre_ne_demarre_pas_sur_un_budget_annuel_en_execution() {
        Budget annuel = budget(1, DureeBudget.ANNUEL, 1, 2026, StatutBudget.EN_EXECUTION);
        budget(2, DureeBudget.MENSUEL, 12, 2026, StatutBudget.APPROUVE);
        when(budgets.findByExerciceAndStatut(2026, StatutBudget.EN_EXECUTION)).thenReturn(List.of(annuel));
        assertThatThrownBy(() -> service.demarrerExecution(2L))
            .isInstanceOf(TransitionInvalideException.class)
            .hasMessageContaining("BUD-2026-00001 (Exercice 2026) est déjà en exécution sur une partie de cette période (Décembre 2026)");
    }

    @Test
    void un_budget_de_decembre_demarre_apres_un_budget_du_troisieme_trimestre() {
        Budget t3 = budget(1, DureeBudget.TRIMESTRIEL, 3, 2026, StatutBudget.EN_EXECUTION);
        Budget decembre = budget(2, DureeBudget.MENSUEL, 12, 2026, StatutBudget.APPROUVE);
        when(budgets.findByExerciceAndStatut(2026, StatutBudget.EN_EXECUTION)).thenReturn(List.of(t3));
        BudgetResponse r = service.demarrerExecution(2L);
        assertThat(r.statut()).isEqualTo(StatutBudget.EN_EXECUTION);
        assertThat(t3.getStatut()).isEqualTo(StatutBudget.EN_EXECUTION);
        assertThat(decembre.getDateExecution()).isNotNull();
    }

    @Test
    void la_revision_remplace_le_budget_revise() {
        Budget original = budget(1, DureeBudget.MENSUEL, 12, 2026, StatutBudget.EN_EXECUTION);
        Budget revision = budget(2, DureeBudget.MENSUEL, 12, 2026, StatutBudget.APPROUVE);
        revision.setRevisionDe(original);
        when(budgets.findByExerciceAndStatut(2026, StatutBudget.EN_EXECUTION)).thenReturn(List.of(original));
        service.demarrerExecution(2L);
        assertThat(original.getStatut()).isEqualTo(StatutBudget.REMPLACE);
        assertThat(revision.getStatut()).isEqualTo(StatutBudget.EN_EXECUTION);
    }

    @Test
    void une_revision_garde_la_periode_et_les_services() {
        Budget original = budget(1, DureeBudget.TRIMESTRIEL, 4, 2026, StatutBudget.EN_EXECUTION);
        original.getLignes().get(0).setService(RESTAURANT);
        when(budgets.findAllByOrderByExerciceDescDateCreationDesc()).thenReturn(List.of(original));
        BudgetResponse r = service.reviser(1L);
        assertThat(r.duree()).isEqualTo(DureeBudget.TRIMESTRIEL);
        assertThat(r.periode()).isEqualTo(4);
        assertThat(r.lignes()).extracting(l -> l.serviceNom()).containsExactly("Restaurant");
    }

    @Test
    void une_revision_ne_change_pas_de_periode() {
        Budget original = budget(1, DureeBudget.MENSUEL, 12, 2026, StatutBudget.REMPLACE);
        Budget revision = budget(2, DureeBudget.MENSUEL, 12, 2026, StatutBudget.BROUILLON);
        revision.setRevisionDe(original);
        assertThatThrownBy(() -> service.modifier(2L, requete(DureeBudget.MENSUEL, 11, ligne("6011", null, decembre("0")))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("même période que le budget révisé (Décembre 2026)");
    }

    @Test
    void cloture_a_la_fin_de_la_periode_seulement() {
        LocalDate aujourdhui = LocalDate.now();
        Budget enCours = budget(1, DureeBudget.MENSUEL, aujourdhui.getMonthValue(), aujourdhui.getYear(), StatutBudget.EN_EXECUTION);
        assertThatThrownBy(() -> service.cloturer(1L))
            .isInstanceOf(TransitionInvalideException.class)
            .hasMessageContaining("n'est pas terminée");
        LocalDate moisPasse = aujourdhui.minusMonths(1);
        Budget termine = budget(2, DureeBudget.MENSUEL, moisPasse.getMonthValue(), moisPasse.getYear(), StatutBudget.EN_EXECUTION);
        assertThat(service.cloturer(2L).statut()).isEqualTo(StatutBudget.CLOTURE);
        assertThat(enCours.getStatut()).isEqualTo(StatutBudget.EN_EXECUTION);
        assertThat(termine.getDateCloture()).isNotNull();
    }

    @Test
    void la_ligne_budgetaire_consolidee_garde_son_service() {
        Budget b = budget(5, DureeBudget.MENSUEL, 12, 2026, StatutBudget.BROUILLON);
        LigneBudget l = b.getLignes().get(0);
        l.setService(LOGISTIQUE);
        assertThat(service.consulter(5L).lignes().get(0).serviceResponsable()).isEqualTo("Marie Kasongo");
    }
}
