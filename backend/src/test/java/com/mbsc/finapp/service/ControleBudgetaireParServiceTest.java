package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Budget;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.LigneNoteFrais;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.ServiceBudgetaire;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.domain.enums.SensTransaction;
import com.mbsc.finapp.domain.enums.StatutBudget;
import com.mbsc.finapp.domain.enums.StatutControleBudget;
import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.dto.budget.ControleBudgetaireResponse;
import com.mbsc.finapp.dto.budget.ControleBudgetaireResponse.LigneControle;
import com.mbsc.finapp.repository.BudgetRepository;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.NoteFraisRepository;
import com.mbsc.finapp.repository.ServiceBudgetaireRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.mbsc.finapp.service.BudgetParServiceTest.LOGISTIQUE;
import static com.mbsc.finapp.service.BudgetParServiceTest.RESTAURANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Controle d'une note de frais sur un budget de decembre organise par service : le budget qui couvre le mois de la
 * note, l'enveloppe de son service, les lignes generales a defaut, le budget consolide pour une note sans service.
 */
class ControleBudgetaireParServiceTest {

    private static final Instant DECEMBRE = Instant.parse("2026-12-15T12:00:00Z");
    private static final Instant NOVEMBRE = Instant.parse("2026-11-15T12:00:00Z");

    private ControleBudgetaireService controle;

    private BudgetRepository budgets;

    @BeforeEach
    void preparer() {
        budgets = mock(BudgetRepository.class);
        Budget decembre = BudgetParServiceTest.budgetDecembre();
        when(budgets.findByExerciceAndStatut(2026, StatutBudget.EN_EXECUTION)).thenReturn(List.of(decembre));

        CompteOHADARepository comptes = mock(CompteOHADARepository.class);
        when(comptes.findByNumero(anyString())).thenAnswer(i -> {
            String n = i.getArgument(0);
            return Optional.of(BudgetParServiceTest.compte(n, n.startsWith("7") ? TypeCompte.PRODUIT : TypeCompte.CHARGE));
        });

        // Grand livre de decembre : le Restaurant a deja depense 800 sur 6011 ; 150 sans service sur 6052 (ligne generale).
        EcritureGrandLivreRepository ecritures = mock(EcritureGrandLivreRepository.class);
        List<Object[]> lignes = new ArrayList<>();
        lignes.add(new Object[] {"6011", TypeCompte.CHARGE, 12, 1L, new BigDecimal("800"), BigDecimal.ZERO});
        lignes.add(new Object[] {"6052", TypeCompte.CHARGE, 12, null, new BigDecimal("150"), BigDecimal.ZERO});
        lignes.add(new Object[] {"6042", TypeCompte.CHARGE, 12, 2L, new BigDecimal("100"), BigDecimal.ZERO});
        when(ecritures.mouvementsMensuelsBudgetaires(any(), any())).thenReturn(lignes);
        NoteFraisRepository notes = mock(NoteFraisRepository.class);
        when(notes.findDecaissementsAvecLignes(any(), any(), any())).thenReturn(List.of());

        SuiviBudgetaireService suivi = new SuiviBudgetaireService(ecritures, notes, comptes, mock(ConversionDeviseService.class),
            mock(ServiceBudgetaireRepository.class));
        ModuleConfigService modules = mock(ModuleConfigService.class);
        when(modules.estActif(ModuleMetier.BUDGET)).thenReturn(true);
        controle = new ControleBudgetaireService(budgets, comptes, suivi, mock(ConversionDeviseService.class), modules,
            mock(ServiceBudgetaireRepository.class));
    }

    private static NoteFrais note(Instant date, ServiceBudgetaire service, String... compteEtMontant) {
        NoteFrais n = NoteFrais.builder().id(50L).reference("NF-2026-000050").objet("Achat").beneficiaire("Fournisseur")
            .montant(BigDecimal.ZERO).devise(Devise.USD).sens(SensTransaction.DECAISSEMENT).dateCreation(date).service(service).build();
        for (int i = 0; i < compteEtMontant.length; i += 2) {
            n.addLigne(LigneNoteFrais.builder().compteImputation(BudgetParServiceTest.compte(compteEtMontant[i], TypeCompte.CHARGE))
                .montant(new BigDecimal(compteEtMontant[i + 1])).build());
        }
        return n;
    }

    @Test
    void une_note_du_service_est_controlee_sur_son_enveloppe() {
        ControleBudgetaireResponse r = controle.controlerNote(note(DECEMBRE, RESTAURANT, "6011", "150"));
        assertThat(r.statut()).isEqualTo(StatutControleBudget.CONFORME);
        assertThat(r.budgetPeriode()).isEqualTo("Décembre 2026");
        assertThat(r.serviceNom()).isEqualTo("Restaurant");
        assertThat(r.serviceResponsable()).isEqualTo("Jean Mbuyi");
        LigneControle l = r.lignes().get(0);
        assertThat(l.enveloppe()).isEqualTo("service Restaurant");
        assertThat(l.disponibleCumule()).isEqualByComparingTo("200");
        assertThat(l.message()).contains("6011 (service Restaurant)").contains("disponible à fin décembre 200,00 USD");
    }

    @Test
    void le_service_qui_depasse_son_enveloppe_est_signale() {
        ControleBudgetaireResponse r = controle.controlerNote(note(DECEMBRE, RESTAURANT, "6011", "300"));
        assertThat(r.statut()).isEqualTo(StatutControleBudget.DEPASSEMENT);
        assertThat(r.justificationRequise()).isTrue();
        assertThat(r.lignes().get(0).message()).contains("Dépasse le disponible à fin décembre sur 6011 (service Restaurant)")
            .contains("disponible 200,00, demandé 300,00 USD");
    }

    @Test
    void deux_lignes_d_une_note_sur_la_meme_enveloppe_se_cumulent() {
        ControleBudgetaireResponse r = controle.controlerNote(note(DECEMBRE, RESTAURANT, "6011", "150", "6011.2", "150"));
        assertThat(r.lignes()).extracting(LigneControle::statut)
            .containsExactly(StatutControleBudget.CONFORME, StatutControleBudget.DEPASSEMENT);
        assertThat(r.statut()).isEqualTo(StatutControleBudget.DEPASSEMENT);
    }

    @Test
    void sans_ligne_du_service_la_ligne_generale_prend_le_relais() {
        ControleBudgetaireResponse r = controle.controlerNote(note(DECEMBRE, RESTAURANT, "6052", "40"));
        assertThat(r.statut()).isEqualTo(StatutControleBudget.CONFORME);
        assertThat(r.lignes().get(0).enveloppe()).isEqualTo("lignes générales");
        assertThat(r.lignes().get(0).disponibleCumule()).isEqualByComparingTo("50");
    }

    @Test
    void un_compte_budgete_seulement_par_un_autre_service_est_hors_budget_pour_ce_service() {
        ControleBudgetaireResponse r = controle.controlerNote(note(DECEMBRE, RESTAURANT, "6042", "10"));
        assertThat(r.statut()).isEqualTo(StatutControleBudget.HORS_BUDGET);
        assertThat(r.lignes().get(0).message())
            .isEqualTo("Le service Restaurant n'a pas de ligne budgétaire couvrant le compte 6042 (il est budgété pour : Logistique).");
    }

    @Test
    void la_logistique_consomme_sa_propre_enveloppe() {
        ControleBudgetaireResponse r = controle.controlerNote(note(DECEMBRE, LOGISTIQUE, "6042", "450"));
        assertThat(r.statut()).isEqualTo(StatutControleBudget.DEPASSEMENT);
        assertThat(r.lignes().get(0).disponibleCumule()).isEqualByComparingTo("400");
    }

    @Test
    void une_note_sans_service_sur_un_compte_budgete_par_service_est_controlee_sur_le_budget_consolide() {
        ControleBudgetaireResponse r = controle.controlerNote(note(DECEMBRE, null, "6011", "150"));
        assertThat(r.statut()).isEqualTo(StatutControleBudget.CONFORME);
        assertThat(r.lignes().get(0).enveloppe()).isEqualTo("budget consolidé");
        assertThat(r.avertissements()).anyMatch(a -> a.startsWith("Aucun service choisi"));
        assertThat(r.serviceId()).isNull();
    }

    @Test
    void un_compte_que_le_budget_ne_couvre_pas_est_hors_budget() {
        ControleBudgetaireResponse r = controle.controlerNote(note(DECEMBRE, RESTAURANT, "6288", "10"));
        assertThat(r.statut()).isEqualTo(StatutControleBudget.HORS_BUDGET);
        assertThat(r.lignes().get(0).message()).contains("n'est couvert par aucune ligne du budget BUD-2026-000010");
    }

    @Test
    void un_budget_approuve_mais_pas_mis_en_execution_est_signale() {
        Budget novembre = Budget.builder().id(20L).reference("BUD-2026-000020").intitule("Novembre").exercice(2026)
            .duree(com.mbsc.finapp.domain.enums.DureeBudget.MENSUEL).periode(11).statut(StatutBudget.APPROUVE).build();
        Budget annuel = Budget.builder().id(21L).reference("BUD-2026-000021").intitule("Année").exercice(2026)
            .statut(StatutBudget.SOUMIS).build();
        Budget autreMois = Budget.builder().id(22L).reference("BUD-2026-000022").intitule("Mars").exercice(2026)
            .duree(com.mbsc.finapp.domain.enums.DureeBudget.MENSUEL).periode(3).statut(StatutBudget.APPROUVE).build();
        when(budgets.findByExercice(2026)).thenReturn(List.of(annuel, autreMois, novembre));

        ControleBudgetaireResponse r = controle.controlerNote(note(NOVEMBRE, RESTAURANT, "6011", "10"));

        assertThat(r.statut()).isEqualTo(StatutControleBudget.SANS_BUDGET);
        assertThat(r.avertissements()).containsExactly("Le budget BUD-2026-000020 (Novembre 2026) est approuvé mais pas encore mis en "
            + "exécution : ouvrez-le et cliquez « Mettre en exécution » pour qu'il contrôle les dépenses. Le budget BUD-2026-000021 "
            + "(Exercice 2026) attend l'approbation du DA.");
    }

    @Test
    void une_note_d_un_mois_que_le_budget_ne_couvre_pas_est_sans_budget() {
        ControleBudgetaireResponse r = controle.controlerNote(note(NOVEMBRE, RESTAURANT, "6011", "10"));
        assertThat(r.statut()).isEqualTo(StatutControleBudget.SANS_BUDGET);
        assertThat(r.budgetId()).isNull();
        assertThat(r.lignes().get(0).message()).isEqualTo("Aucun budget en exécution pour novembre 2026 : la dépense est hors budget.");
    }
}
