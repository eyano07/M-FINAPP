package com.mbsc.finapp.service;

import com.mbsc.finapp.audit.AuditService;
import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.Entrepot;
import com.mbsc.finapp.domain.LigneNoteFrais;
import com.mbsc.finapp.domain.LigneRetourFonds;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.RetourFondsNote;
import com.mbsc.finapp.domain.StockNiveau;
import com.mbsc.finapp.domain.TransactionCaisse;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.CategorieNote;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.SensTransaction;
import com.mbsc.finapp.domain.enums.StatutNote;
import com.mbsc.finapp.domain.enums.StatutRetourFonds;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.domain.enums.TypeNotification;
import com.mbsc.finapp.dto.notes.RetourFondsRequest;
import com.mbsc.finapp.dto.notes.RetourFondsResponse;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.CamionMineraiRepository;
import com.mbsc.finapp.repository.ChargeCamionMineraiRepository;
import com.mbsc.finapp.repository.EmballageBoissonRepository;
import com.mbsc.finapp.repository.NoteFraisRepository;
import com.mbsc.finapp.repository.RetourFondsNoteRepository;
import com.mbsc.finapp.repository.StockNiveauRepository;
import com.mbsc.finapp.repository.TauxChangeRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Retour de fonds sur une note payee : la demande de l'emetteur, le retrait, le refus du caissier, et la validation qui
 * fait le miroir du paiement (encaissement en caisse, sortie de stock, vides, note mise a jour, notifications).
 */
class RetourFondsServiceTest {

    private final NoteFraisRepository noteRepository = mock(NoteFraisRepository.class);
    private final RetourFondsNoteRepository retourRepository = mock(RetourFondsNoteRepository.class);
    private final CamionMineraiRepository camionRepository = mock(CamionMineraiRepository.class);
    private final ChargeCamionMineraiRepository chargeCamionRepository = mock(ChargeCamionMineraiRepository.class);
    private final StockNiveauRepository stockNiveauRepository = mock(StockNiveauRepository.class);
    private final EmballageBoissonRepository emballageRepository = mock(EmballageBoissonRepository.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final ConversionDeviseService conversion = new ConversionDeviseService(mock(TauxChangeRepository.class));
    private final EcritureComptableService comptabilite = mock(EcritureComptableService.class);
    private final ReferenceGenerator references = mock(ReferenceGenerator.class);
    private final StockService stockService = mock(StockService.class);
    private final RestaurantService restaurantService = mock(RestaurantService.class);
    private final NotificationService notifications = mock(NotificationService.class);

    private final RetourFondsService service = new RetourFondsService(noteRepository, retourRepository, camionRepository,
        chargeCamionRepository, stockNiveauRepository, emballageRepository, currentUser, conversion, comptabilite, references, stockService,
        restaurantService, notifications);

    private final User emetteur = User.builder().id(10L).prenom("Rita").nom("Kabila").email("rest@test").actif(true).build();
    private final User caissier = User.builder().id(20L).prenom("Cyril").nom("Mwamba").email("caisse@test").actif(true).build();
    private final User autre = User.builder().id(30L).prenom("Autre").nom("Compte").email("autre@test").actif(true).build();

    private final CompteOHADA fournitures = CompteOHADA.builder().id(1L).numero("6047").libelle("Fournitures").build();
    private final CompteOHADA tvaRecuperable = CompteOHADA.builder().id(2L).numero("4452").libelle("TVA récupérable").build();
    private final CompteOHADA achatBiere = CompteOHADA.builder().id(3L).numero("60111").libelle("6011 : Bière").build();
    private final CompteOHADA achatRiz = CompteOHADA.builder().id(4L).numero("60211").libelle("6021 : Riz").build();
    private final Article biere = Article.builder().id(1L).code("BOI-001").libelle("Bière Simba").uniteMesure("bouteille")
        .type(TypeArticle.BOISSON).build();
    private final Article riz = Article.builder().id(2L).code("PROV-001").libelle("Riz").uniteMesure("kg")
        .type(TypeArticle.PROVISION).build();
    private final Entrepot bar = Entrepot.builder().id(5L).code("BAR").nom("Bar").build();

    private NoteFrais note;
    private LigneNoteFrais depense;   // 100,00 HT + TVA 16 % = 116,00
    private LigneNoteFrais lBiere;    // 24 bouteilles a 2,50 = 60,00, echange de consigne
    private LigneNoteFrais lRiz;      // 12,5 kg a 3,20 = 40,00

    @BeforeEach
    void preparer() {
        depense = LigneNoteFrais.builder().id(101L).montant(new BigDecimal("100.00")).compteImputation(fournitures)
            .soumisTva(true).tauxTvaApplique(new BigDecimal("16")).compteTva(tvaRecuperable).description("Papier").build();
        lBiere = LigneNoteFrais.builder().id(102L).montant(new BigDecimal("2.50")).quantiteMarchandise(new BigDecimal("24"))
            .achatMarchandise(true).article(biere).entrepot(bar).compteImputation(achatBiere).echangeConsigne(true).build();
        lRiz = LigneNoteFrais.builder().id(103L).montant(new BigDecimal("3.20")).quantiteMarchandise(new BigDecimal("12.5"))
            .achatMarchandise(true).article(riz).entrepot(bar).compteImputation(achatRiz).build();
        note = NoteFrais.builder().id(1L).reference("NF-2026-000042").objet("Achats du restaurant").beneficiaire("Fournisseur")
            .devise(Devise.USD).statut(StatutNote.PAYEE).sens(SensTransaction.DECAISSEMENT).categorie(CategorieNote.STANDARD)
            .createur(emetteur).build();
        note.addLigne(depense);
        note.addLigne(lBiere);
        note.addLigne(lRiz);
        note.recalculerMontant();   // 116,00 + 60,00 + 40,00 = 216,00
        note.setTransaction(TransactionCaisse.builder().id(900L).reference("TRX-PAIEMENT").sens(SensTransaction.DECAISSEMENT)
            .montant(new BigDecimal("216.00")).tauxJournalier(new BigDecimal("2800")).build());

        when(noteRepository.findWithDetailsById(1L)).thenReturn(Optional.of(note));
        when(camionRepository.findByNoteFraisReglementId(1L)).thenReturn(List.of());
        when(retourRepository.findFirstByNoteIdAndStatut(eq(1L), eq(StatutRetourFonds.EN_ATTENTE))).thenReturn(Optional.empty());
        when(retourRepository.save(any(RetourFondsNote.class))).thenAnswer(i -> i.getArgument(0));
        stock(biere, "30");
        stock(riz, "10");
        when(references.pourTransaction()).thenReturn("TRX-RETOUR");
        when(references.pourRecu()).thenReturn("REC-RETOUR");
        when(comptabilite.enregistrerEncaissementMultiLigne(any(TransactionCaisse.class), anyList(), anyString(), any()))
            .thenAnswer(i -> i.getArgument(0));
    }

    private void stock(Article article, String quantite) {
        when(stockNiveauRepository.findByArticleAndEntrepot(article, bar))
            .thenReturn(Optional.of(StockNiveau.builder().article(article).entrepot(bar).quantite(new BigDecimal(quantite)).build()));
    }

    private static RetourFondsRequest demandeComplete() {
        return new RetourFondsRequest("Livraison incomplète", List.of(
            new RetourFondsRequest.Ligne(101L, null, new BigDecimal("50.00")),
            new RetourFondsRequest.Ligne(102L, new BigDecimal("12"), null),
            new RetourFondsRequest.Ligne(103L, new BigDecimal("10.25"), null)));
    }

    /** Demande en attente telle que demander() l'aurait enregistree. */
    private RetourFondsNote enAttente() {
        when(currentUser.requireUser()).thenReturn(emetteur);
        service.demander(1L, demandeComplete());
        ArgumentCaptor<RetourFondsNote> c = ArgumentCaptor.forClass(RetourFondsNote.class);
        verify(retourRepository).save(c.capture());
        RetourFondsNote r = c.getValue();
        r.setId(7L);
        when(retourRepository.findPourMiseAJour(7L)).thenReturn(Optional.of(r));
        return r;
    }

    // ---------------------------------------------------------------------
    // Demande de l'emetteur
    // ---------------------------------------------------------------------

    @Test
    void l_emetteur_demande_le_retour_rien_ne_bouge_avant_la_caisse() {
        when(currentUser.requireUser()).thenReturn(emetteur);
        RetourFondsResponse r = service.demander(1L, demandeComplete());

        // 58,00 (depense avec TVA) + 30,00 (12 bouteilles) + 7,20 (2,25 kg)
        assertThat(r.montant()).isEqualByComparingTo("95.20");
        assertThat(r.statut()).isEqualTo(StatutRetourFonds.EN_ATTENTE);
        assertThat(r.lignes()).hasSize(3);
        assertThat(note.getMontant()).isEqualByComparingTo("216.00");          // la note ne change qu'a la validation
        assertThat(lBiere.getQuantiteMarchandise()).isEqualByComparingTo("24");
        assertThat(note.getObservations()).singleElement()
            .satisfies(o -> assertThat(o.getCommentaire()).contains("Retour de fonds demandé").contains("Livraison incomplète"));
        verifyNoInteractions(comptabilite, stockService, restaurantService);
        // La caisse, et elle seule, est prévenue : l'argent va lui être remis (lien vers la page Caisse, jamais l'émetteur).
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notifications).notifierNote(eq(List.of()), isNull(), eq(emetteur), eq(TypeNotification.RETOUR_FONDS_A_RECEVOIR),
            eq("Retour de fonds à recevoir"), message.capture(), eq(note), eq(java.util.Map.of(RoleType.CAISSIER, "/caisse")));
        assertThat(message.getValue()).contains("NF-2026-000042").contains("95,20 USD à recevoir en espèces de Rita Kabila")
            .contains("Papier 100,00 → 50,00").contains("Bière Simba 24 → 12").contains("Riz 12.5 → 10.25")
            .contains("Motif : Livraison incomplète");
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void seul_l_emetteur_reduit_sa_note() {
        when(currentUser.requireUser()).thenReturn(autre);
        assertThatThrownBy(() -> service.demander(1L, demandeComplete()))
            .isInstanceOf(TransitionInvalideException.class).hasMessageContaining("Seul l'émetteur");
    }

    @Test
    void seule_une_note_de_depense_payee_standard_se_reduit() {
        when(currentUser.requireUser()).thenReturn(emetteur);
        note.setStatut(StatutNote.TRANSMISE_CAISSE);
        assertThatThrownBy(() -> service.demander(1L, demandeComplete())).hasMessageContaining("Seule une note payée");
        note.setStatut(StatutNote.PAYEE);
        note.setCategorie(CategorieNote.PAIE);
        assertThatThrownBy(() -> service.demander(1L, demandeComplete())).hasMessageContaining("note de paie");
        note.setCategorie(CategorieNote.STANDARD);
        note.setSens(SensTransaction.ENCAISSEMENT);
        assertThatThrownBy(() -> service.demander(1L, demandeComplete())).hasMessageContaining("note de dépense");
        note.setSens(SensTransaction.DECAISSEMENT);
        when(camionRepository.findByNoteFraisReglementId(1L)).thenReturn(List.of(new com.mbsc.finapp.domain.CamionMinerai()));
        assertThatThrownBy(() -> service.demander(1L, demandeComplete())).hasMessageContaining("camions de minerais");
        when(camionRepository.findByNoteFraisReglementId(1L)).thenReturn(List.of());
        when(chargeCamionRepository.findByNoteFraisReglementId(1L))
            .thenReturn(List.of(new com.mbsc.finapp.domain.ChargeCamionMinerai()));
        assertThatThrownBy(() -> service.demander(1L, demandeComplete())).hasMessageContaining("ou de leurs frais");
    }

    @Test
    void une_seule_demande_en_attente_par_note() {
        when(currentUser.requireUser()).thenReturn(emetteur);
        when(retourRepository.findFirstByNoteIdAndStatut(1L, StatutRetourFonds.EN_ATTENTE))
            .thenReturn(Optional.of(RetourFondsNote.builder().note(note).build()));
        assertThatThrownBy(() -> service.demander(1L, demandeComplete()))
            .isInstanceOf(TransitionInvalideException.class).hasMessageContaining("attend déjà la caisse");
    }

    @Test
    void ce_qui_est_deja_vendu_ou_consomme_ne_se_rend_pas() {
        when(currentUser.requireUser()).thenReturn(emetteur);
        stock(riz, "1");   // la reduction en retire 2,25 kg
        assertThatThrownBy(() -> service.demander(1L, demandeComplete()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Stock insuffisant pour Riz").hasMessageContaining("il en reste 1 kg");
    }

    // ---------------------------------------------------------------------
    // Validation par le caissier
    // ---------------------------------------------------------------------

    @Test
    void la_validation_fait_le_miroir_du_paiement() {
        RetourFondsNote retour = enAttente();
        when(currentUser.requireUser()).thenReturn(caissier);

        RetourFondsResponse r = service.valider(7L);

        // 1. Encaissement en caisse : D 571 (par EcritureComptableService) / C comptes de la note, TVA a son compte
        ArgumentCaptor<TransactionCaisse> t = ArgumentCaptor.forClass(TransactionCaisse.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EcritureComptableService.LigneCredit>> credits = ArgumentCaptor.forClass(List.class);
        verify(comptabilite).enregistrerEncaissementMultiLigne(t.capture(), credits.capture(), anyString(), any());
        assertThat(t.getValue().getSens()).isEqualTo(SensTransaction.ENCAISSEMENT);
        assertThat(t.getValue().getNoteRetour()).isSameAs(note);
        assertThat(t.getValue().getNoteFrais()).isNull();   // le lien un-pour-un reste celui du paiement
        assertThat(t.getValue().getMontant()).isEqualByComparingTo("95.20");
        assertThat(t.getValue().getTauxJournalier()).isEqualByComparingTo("2800");
        assertThat(t.getValue().getCaissier()).isSameAs(caissier);
        assertThat(credits.getValue()).extracting(c -> c.compte().getNumero(), c -> c.montant().toPlainString())
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("6047", "50.00"),
                org.assertj.core.groups.Tuple.tuple("4452", "8.00"),
                org.assertj.core.groups.Tuple.tuple("60111", "30.00"),
                org.assertj.core.groups.Tuple.tuple("60211", "7.20"));

        // 2. Stock : une sortie par entrepot, articles tries
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StockService.SortieVente>> sorties = ArgumentCaptor.forClass(List.class);
        verify(stockService).enregistrerSortieVenteInterne(any(LocalDate.class), eq("Retour d'achat — NF-2026-000042"), eq(bar),
            sorties.capture(), eq(caissier));
        assertThat(sorties.getValue()).extracting(s -> s.article().getCode(), s -> s.quantite().stripTrailingZeros().toPlainString())
            .containsExactly(org.assertj.core.groups.Tuple.tuple("BOI-001", "12"), org.assertj.core.groups.Tuple.tuple("PROV-001", "2.25"));
        // Chaque sortie désigne la ligne de note rendue : c'est le lot de CETTE livraison qui diminue dans la page de stock
        // (sans quoi le FIFO vide le plus ancien lot de l'article et la livraison retournée paraît inchangée).
        assertThat(sorties.getValue()).extracting(StockService.SortieVente::ligneNoteOrigineId).containsExactly(102L, 103L);

        // 3. Vides : 12 bouteilles d'echange de consigne rendues par le fournisseur
        verify(restaurantService).enregistrerRetourVidesAchatInterne(eq(biere), eq(12), any(LocalDate.class), anyString(), eq(caissier));

        // 4. La note prend ses nouvelles valeurs
        assertThat(depense.getMontant()).isEqualByComparingTo("50.00");
        assertThat(lBiere.getQuantiteMarchandise()).isEqualByComparingTo("12");
        assertThat(lRiz.getQuantiteMarchandise()).isEqualByComparingTo("10.25");
        assertThat(note.getMontant()).isEqualByComparingTo("120.80");   // 216,00 - 95,20
        assertThat(note.getStatut()).isEqualTo(StatutNote.PAYEE);
        assertThat(retour.getStatut()).isEqualTo(StatutRetourFonds.VALIDE);
        assertThat(retour.getCaissier()).isSameAs(caissier);
        assertThat(r.numeroRecu()).isEqualTo("REC-RETOUR");
        assertThat(retour.getLignes()).extracting(LigneRetourFonds::getAncienMontant).containsExactly(
            new BigDecimal("100.00"), new BigDecimal("2.50"), new BigDecimal("3.20"));

        // 5. Notifications : DFIN, comptable, DA, admin et emetteur (lien vers la note), responsables bar et cuisine (lien
        // vers leur stock : ils ne voient que leurs propres notes) — jamais le caissier
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<RoleType>> roles = ArgumentCaptor.forClass(Collection.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<RoleType, String>> responsables = ArgumentCaptor.forClass(java.util.Map.class);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notifications).notifierNote(roles.capture(), eq(emetteur), eq(caissier), eq(TypeNotification.RETOUR_FONDS_VALIDE),
            eq("Retour de fonds reçu"), message.capture(), eq(note), responsables.capture());
        assertThat(roles.getValue()).containsExactlyInAnyOrder(RoleType.DFIN, RoleType.COMPTABLE, RoleType.DA, RoleType.ADMIN);
        assertThat(responsables.getValue()).containsExactly(
            java.util.Map.entry(RoleType.RESP_BAR, "/restaurant/stock"),
            java.util.Map.entry(RoleType.RESP_CUISINE, "/restaurant/provisions"));
        assertThat(message.getValue()).contains("95,20 USD rendus en caisse (reçu REC-RETOUR)")
            .contains("la note passe de 216,00 à 120,80 USD");
    }

    @Test
    void une_note_en_francs_rend_au_taux_de_son_paiement_pas_au_taux_du_jour() {
        LigneNoteFrais l = LigneNoteFrais.builder().id(201L).montant(new BigDecimal("30000")).compteImputation(fournitures).build();
        NoteFrais cdf = NoteFrais.builder().id(2L).reference("NF-2026-000043").objet("Carburant").devise(Devise.CDF)
            .statut(StatutNote.PAYEE).sens(SensTransaction.DECAISSEMENT).categorie(CategorieNote.STANDARD).createur(emetteur).build();
        cdf.addLigne(l);
        cdf.recalculerMontant();
        cdf.setTransactionBancaire(com.mbsc.finapp.domain.TransactionBancaire.builder().id(901L)
            .tauxJournalier(new BigDecimal("2800")).build());   // payee par la banque : le retour vient quand meme en caisse
        when(noteRepository.findWithDetailsById(2L)).thenReturn(Optional.of(cdf));
        when(camionRepository.findByNoteFraisReglementId(2L)).thenReturn(List.of());
        when(retourRepository.findFirstByNoteIdAndStatut(2L, StatutRetourFonds.EN_ATTENTE)).thenReturn(Optional.empty());
        when(currentUser.requireUser()).thenReturn(emetteur);
        service.demander(2L, new RetourFondsRequest("Plein moins cher", List.of(new RetourFondsRequest.Ligne(201L, null, new BigDecimal("20000")))));
        ArgumentCaptor<RetourFondsNote> c = ArgumentCaptor.forClass(RetourFondsNote.class);
        verify(retourRepository).save(c.capture());
        RetourFondsNote retour = c.getValue();
        retour.setId(8L);
        when(retourRepository.findPourMiseAJour(8L)).thenReturn(Optional.of(retour));

        when(currentUser.requireUser()).thenReturn(caissier);
        service.valider(8L);

        ArgumentCaptor<TransactionCaisse> t = ArgumentCaptor.forClass(TransactionCaisse.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EcritureComptableService.LigneCredit>> credits = ArgumentCaptor.forClass(List.class);
        verify(comptabilite).enregistrerEncaissementMultiLigne(t.capture(), credits.capture(), anyString(), any());
        assertThat(t.getValue().getMontant()).isEqualByComparingTo("3.57");   // 10 000 FC / 2 800
        assertThat(t.getValue().getTauxJournalier()).isEqualByComparingTo("2800");
        assertThat(credits.getValue()).singleElement().satisfies(x -> assertThat(x.montant()).isEqualByComparingTo("3.57"));
        verify(stockService, never()).enregistrerSortieVenteInterne(any(), anyString(), any(), anyList(), any());
        assertThat(cdf.getMontant()).isEqualByComparingTo("20000");
    }

    @Test
    void la_caisse_refuse_de_valider_sur_une_note_qui_a_change() {
        RetourFondsNote retour = enAttente();
        lBiere.setQuantiteMarchandise(new BigDecimal("20"));   // ne devrait pas arriver, mais on ne rend pas sur du perime
        when(currentUser.requireUser()).thenReturn(caissier);
        assertThatThrownBy(() -> service.valider(7L)).isInstanceOf(TransitionInvalideException.class)
            .hasMessageContaining("La note a changé depuis la demande");
        assertThat(retour.getStatut()).isEqualTo(StatutRetourFonds.EN_ATTENTE);
        verifyNoInteractions(comptabilite);
    }

    @Test
    void le_stock_est_reverifie_a_la_validation() {
        enAttente();
        stock(biere, "5");   // vendu entre la demande et la validation
        when(currentUser.requireUser()).thenReturn(caissier);
        assertThatThrownBy(() -> service.valider(7L)).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Stock insuffisant pour Bière Simba");
        verifyNoInteractions(comptabilite);
    }

    @Test
    void une_demande_deja_traitee_ne_se_valide_plus() {
        RetourFondsNote retour = enAttente();
        retour.setStatut(StatutRetourFonds.ANNULE);
        when(currentUser.requireUser()).thenReturn(caissier);
        assertThatThrownBy(() -> service.valider(7L)).isInstanceOf(TransitionInvalideException.class)
            .hasMessageContaining("n'est plus en attente");
    }

    // ---------------------------------------------------------------------
    // Refus, retrait
    // ---------------------------------------------------------------------

    @Test
    void le_caissier_refuse_avec_un_motif_seul_l_emetteur_est_prevenu() {
        RetourFondsNote retour = enAttente();
        when(currentUser.requireUser()).thenReturn(caissier);
        assertThatThrownBy(() -> service.refuser(7L, "  ")).isInstanceOf(IllegalArgumentException.class);

        service.refuser(7L, "Argent non remis");
        assertThat(retour.getStatut()).isEqualTo(StatutRetourFonds.REFUSE);
        assertThat(retour.getMotifRefus()).isEqualTo("Argent non remis");
        assertThat(note.getMontant()).isEqualByComparingTo("216.00");
        verify(notifications).notifierNote(eq(List.of()), eq(emetteur), eq(caissier), eq(TypeNotification.RETOUR_FONDS_REFUSE),
            eq("Retour de fonds refusé"), anyString(), eq(note));
        verifyNoInteractions(comptabilite, stockService, restaurantService);
    }

    @Test
    void l_emetteur_retire_sa_demande_personne_d_autre() {
        RetourFondsNote retour = enAttente();
        when(currentUser.requireUser()).thenReturn(autre);
        assertThatThrownBy(() -> service.annuler(7L)).hasMessageContaining("Seul l'émetteur de la demande");
        when(currentUser.requireUser()).thenReturn(emetteur);
        service.annuler(7L);
        assertThat(retour.getStatut()).isEqualTo(StatutRetourFonds.ANNULE);
        verifyNoInteractions(comptabilite);
        // Les caissiers, prévenus de la demande, savent qu'il n'y a plus rien à recevoir.
        verify(notifications).notifierNote(eq(List.of()), isNull(), eq(emetteur), eq(TypeNotification.RETOUR_FONDS_RETIRE),
            eq("Retour de fonds retiré"), org.mockito.ArgumentMatchers.contains("la demande de 95,20 USD a été retirée"), eq(note),
            eq(java.util.Map.of(RoleType.CAISSIER, "/caisse")));
    }

    // ---------------------------------------------------------------------
    // Journal d'audit : chaque action depose son detail metier
    // ---------------------------------------------------------------------

    private static String detailAudit(MockHttpServletRequest requete) {
        return (String) requete.getAttribute(AuditService.ATTRIBUT_DETAIL);
    }

    @Test
    void la_demande_et_la_reception_deposent_leur_detail_dans_le_journal_d_audit() {
        MockHttpServletRequest requete = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(requete));
        try {
            enAttente();
            assertThat(detailAudit(requete)).startsWith("NF-2026-000042 — retour de 95,20 USD demandé (")
                .contains("Bière Simba 24 → 12").endsWith("Motif : Livraison incomplète");

            MockHttpServletRequest reception = new MockHttpServletRequest();
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(reception));
            when(currentUser.requireUser()).thenReturn(caissier);
            service.valider(7L);
            assertThat(detailAudit(reception)).startsWith("NF-2026-000042 — 95,20 USD reçus en espèces, reçu REC-RETOUR (TRX-RETOUR)")
                .contains("note 216,00 → 120,80 USD").contains("Riz 12.5 → 10.25");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void le_refus_depose_son_detail_dans_le_journal_d_audit() {
        try {
            enAttente();
            MockHttpServletRequest refus = new MockHttpServletRequest();
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(refus));
            when(currentUser.requireUser()).thenReturn(caissier);
            service.refuser(7L, "Argent non remis");
            assertThat(detailAudit(refus)).isEqualTo("NF-2026-000042 — 95,20 USD non reçus, retour refusé. Motif : Argent non remis");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void le_retrait_depose_son_detail_dans_le_journal_d_audit() {
        try {
            enAttente();
            MockHttpServletRequest retrait = new MockHttpServletRequest();
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(retrait));
            when(currentUser.requireUser()).thenReturn(emetteur);
            service.annuler(7L);
            assertThat(detailAudit(retrait)).isEqualTo("NF-2026-000042 — demande de retour de 95,20 USD retirée par l'émetteur");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    // ---------------------------------------------------------------------
    // Regles statiques et gardes
    // ---------------------------------------------------------------------

    @Test
    void responsable_bar_pour_une_boisson_responsable_cuisine_pour_une_provision_ou_un_plat() {
        java.util.Map<Long, LigneNoteFrais> parId = new java.util.LinkedHashMap<>();
        parId.put(101L, depense);
        parId.put(102L, lBiere);
        parId.put(103L, lRiz);
        RetourFondsCalcul.Reduction d = new RetourFondsCalcul.Reduction(101L, null, null, null, null, BigDecimal.ONE);
        RetourFondsCalcul.Reduction b = new RetourFondsCalcul.Reduction(102L, null, null, null, null, BigDecimal.ONE);
        RetourFondsCalcul.Reduction p = new RetourFondsCalcul.Reduction(103L, null, null, null, null, BigDecimal.ONE);
        assertThat(RetourFondsService.responsablesConcernes(List.of(d), parId)).isEmpty();
        assertThat(RetourFondsService.responsablesConcernes(List.of(b), parId)).containsExactly(RoleType.RESP_BAR);
        assertThat(RetourFondsService.responsablesConcernes(List.of(p), parId)).containsExactly(RoleType.RESP_CUISINE);
    }

    private static String garde(String methode) {
        List<String> gardes = new ArrayList<>();
        for (Method m : RetourFondsService.class.getDeclaredMethods()) {
            if (m.getName().equals(methode) && m.isAnnotationPresent(PreAuthorize.class)) {
                gardes.add(m.getAnnotation(PreAuthorize.class).value());
            }
        }
        assertThat(gardes).as(methode).hasSize(1);
        return gardes.get(0);
    }

    @Test
    void gardes_l_emetteur_demande_le_caissier_seul_recoit_l_argent() {
        for (String m : List.of("preparer", "demander", "annuler")) {
            assertThat(garde(m)).as(m).isEqualTo(RetourFondsService.EMETTEURS);
        }
        assertThat(garde("valider")).isEqualTo("hasRole('CAISSIER')");
        assertThat(garde("refuser")).isEqualTo("hasRole('CAISSIER')");
        assertThat(garde("lister")).contains("CAISSIER", "DFIN", "DA", "COMPTABLE").doesNotContain("RESP_");
        // memes emetteurs que la creation d'une note de frais
        String creer = Arrays.stream(NoteFraisService.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("creer") && m.isAnnotationPresent(PreAuthorize.class))
            .map(m -> m.getAnnotation(PreAuthorize.class).value()).findFirst().orElseThrow();
        assertThat(Set.of(RetourFondsService.EMETTEURS.replaceAll("[^A-Z_,]", "").split(",")))
            .isEqualTo(Set.of(creer.replaceAll("[^A-Z_,]", "").split(",")));
    }
}
