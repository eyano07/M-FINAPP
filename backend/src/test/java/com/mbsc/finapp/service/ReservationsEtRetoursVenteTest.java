package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.Entrepot;
import com.mbsc.finapp.domain.LigneRetourVente;
import com.mbsc.finapp.domain.LigneVente;
import com.mbsc.finapp.domain.RetourVente;
import com.mbsc.finapp.domain.StockNiveau;
import com.mbsc.finapp.domain.Vente;
import com.mbsc.finapp.domain.enums.ModeReglement;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.StockNiveauRepository;
import com.mbsc.finapp.repository.VenteRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Commandes en attente qui mettent leur stock de côté, et retour d'une vente payée avec remboursement (V134).
 */
class ReservationsEtRetoursVenteTest {

    private static final BigDecimal SEIZE = new BigDecimal("16");

    private static Article article(long id, String libelle, TypeArticle type) {
        return Article.builder().id(id).code("A" + id).libelle(libelle).type(type)
            .compteProduit(CompteOHADA.builder().id(700L + id).numero("7011." + id).build()).build();
    }

    private static Entrepot bar() {
        return Entrepot.builder().id(1L).code("BAR").nom("Bar").build();
    }

    // ---------------------------------------------------------------------
    // Reservation
    // ---------------------------------------------------------------------

    @Test
    void seuls_les_boissons_et_les_plats_se_mettent_de_cote() {
        assertThat(ReservationStockService.concerne(article(1, "Primus", TypeArticle.BOISSON))).isTrue();
        assertThat(ReservationStockService.concerne(article(2, "Poulet", TypeArticle.PLAT))).isTrue();
        assertThat(ReservationStockService.concerne(article(3, "Ciment", TypeArticle.MARCHANDISE))).isFalse();
        assertThat(ReservationStockService.concerne(null)).isFalse();
    }

    @Test
    void ce_que_d_autres_commandes_ont_mis_de_cote_ne_peut_plus_etre_vendu() {
        VenteRepository ventes = mock(VenteRepository.class);
        StockNiveauRepository niveaux = mock(StockNiveauRepository.class);
        ReservationStockService service = new ReservationStockService(ventes, niveaux);
        Article primus = article(5, "Primus 72 cl", TypeArticle.BOISSON);
        when(niveaux.verrouiller(5L, 1L)).thenReturn(Optional.of(StockNiveau.builder().quantite(new BigDecimal("10")).build()));
        when(ventes.quantiteReservee(5L, 1L, 42L)).thenReturn(new BigDecimal("7"));
        when(ventes.referencesReservant(5L, 1L, 42L)).thenReturn(List.of("VTE-2026-000010", "VTE-2026-000011"));

        service.exigerDisponible(primus, bar(), new BigDecimal("3"), 42L);

        assertThatThrownBy(() -> service.exigerDisponible(primus, bar(), new BigDecimal("4"), 42L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Primus 72 cl")
            .hasMessageContaining("10 en stock")
            .hasMessageContaining("7 mis de côté")
            .hasMessageContaining("VTE-2026-000010, VTE-2026-000011")
            .hasMessageContaining("disponible : 3, demandé : 4");
    }

    @Test
    void sans_ligne_de_stock_rien_n_est_disponible_et_un_article_hors_restaurant_n_est_pas_controle() {
        VenteRepository ventes = mock(VenteRepository.class);
        StockNiveauRepository niveaux = mock(StockNiveauRepository.class);
        ReservationStockService service = new ReservationStockService(ventes, niveaux);
        when(niveaux.verrouiller(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(ventes.quantiteReservee(anyLong(), anyLong(), any())).thenReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.exigerDisponible(article(5, "Whisky", TypeArticle.BOISSON), bar(), BigDecimal.ONE, null))
            .hasMessageContaining("0 en stock");
        service.exigerDisponible(article(9, "Ciment", TypeArticle.MARCHANDISE), bar(), BigDecimal.TEN, null);
        verify(niveaux, never()).verrouiller(9L, 1L);
    }

    @Test
    void les_lignes_de_stock_se_verrouillent_dans_l_ordre_des_articles() {
        VenteRepository ventes = mock(VenteRepository.class);
        StockNiveauRepository niveaux = mock(StockNiveauRepository.class);
        ReservationStockService service = new ReservationStockService(ventes, niveaux);
        when(niveaux.verrouiller(anyLong(), eq(1L))).thenReturn(Optional.of(StockNiveau.builder().quantite(BigDecimal.TEN).build()));
        when(ventes.quantiteReservee(anyLong(), anyLong(), any())).thenReturn(BigDecimal.ZERO);
        Map<Article, BigDecimal> quantites = new LinkedHashMap<>();
        quantites.put(article(30, "Fanta", TypeArticle.BOISSON), BigDecimal.ONE);
        quantites.put(article(4, "Poulet", TypeArticle.PLAT), BigDecimal.ONE);
        quantites.put(article(12, "Coca", TypeArticle.BOISSON), BigDecimal.ONE);

        service.exigerDisponibles(quantites, bar(), null);

        InOrder ordre = inOrder(niveaux);
        ordre.verify(niveaux).verrouiller(4L, 1L);
        ordre.verify(niveaux).verrouiller(12L, 1L);
        ordre.verify(niveaux).verrouiller(30L, 1L);
    }

    @Test
    void une_commande_qui_cite_deux_fois_le_meme_article_en_reserve_la_somme() {
        Article primus = article(5, "Primus", TypeArticle.BOISSON);
        Article autreInstance = article(5, "Primus", TypeArticle.BOISSON);
        List<LigneVente> lignes = List.of(
            LigneVente.builder().article(primus).quantite(new BigDecimal("2")).build(),
            LigneVente.builder().article(article(6, "Fanta", TypeArticle.BOISSON)).quantite(BigDecimal.ONE).build(),
            LigneVente.builder().article(autreInstance).quantite(new BigDecimal("3")).build());

        Map<Article, BigDecimal> q = VenteService.quantitesParArticle(lignes);

        assertThat(q).hasSize(2);
        assertThat(q.get(primus)).isEqualByComparingTo("5");
    }

    // ---------------------------------------------------------------------
    // Retour avec remboursement
    // ---------------------------------------------------------------------

    private static LigneVente ligneVendue(Article a, String quantite, String prix, boolean tva) {
        BigDecimal q = new BigDecimal(quantite);
        BigDecimal ht = new BigDecimal(prix).multiply(q).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal t = tva ? ht.multiply(SEIZE).divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO;
        return LigneVente.builder().article(a).designation(a.getLibelle()).quantite(q).prixUnitaire(new BigDecimal(prix))
            .soumisTva(tva).montantHt(ht).montantTva(t).build();
    }

    @Test
    void la_part_rendue_est_proportionnelle_et_le_dernier_retour_prend_le_reste_exact() {
        LigneVente ligne = ligneVendue(article(5, "Primus", TypeArticle.BOISSON), "3", "1.10", true);   // HT 3.30, TVA 0.53
        BigDecimal[] rien = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};

        BigDecimal[] un = RetourVenteService.montantsRendus(ligne, BigDecimal.ONE, rien, SEIZE);
        assertThat(un[0]).isEqualByComparingTo("1.10");
        assertThat(un[1]).isEqualByComparingTo("0.18");

        BigDecimal[] reste = RetourVenteService.montantsRendus(ligne, new BigDecimal("2"),
            new BigDecimal[] {BigDecimal.ONE, un[0], un[1]}, SEIZE);
        assertThat(reste[0]).isEqualByComparingTo("2.20");
        assertThat(reste[1]).as("0.53 - 0.18 : la somme des retours égale la ligne").isEqualByComparingTo("0.35");
    }

    @Test
    void une_boisson_rendue_revient_en_stock_un_plat_servi_non() {
        assertThat(RetourVenteService.remiseEnStock(article(5, "Primus", TypeArticle.BOISSON), null)).isTrue();
        assertThat(RetourVenteService.remiseEnStock(article(5, "Primus", TypeArticle.BOISSON), false)).isFalse();
        assertThat(RetourVenteService.remiseEnStock(article(6, "Poulet", TypeArticle.PLAT), null)).isFalse();
        assertThat(RetourVenteService.remiseEnStock(article(7, "Livraison", TypeArticle.SERVICE), true)).isFalse();
    }

    private static RetourVente retourDe(Vente vente, LigneRetourVente... lignes) {
        RetourVente r = RetourVente.builder().reference("RV-2026-000001").vente(vente).build();
        BigDecimal ht = BigDecimal.ZERO;
        BigDecimal tva = BigDecimal.ZERO;
        for (LigneRetourVente l : lignes) {
            r.addLigne(l);
            ht = ht.add(l.getMontantHt());
            tva = tva.add(l.getMontantTva());
        }
        r.setMontantHt(ht);
        r.setMontantTva(tva);
        r.setMontantTtc(ht.add(tva));
        return r;
    }

    @Test
    void la_decision_revient_au_bar_pour_des_boissons_a_la_cuisine_pour_des_plats_au_restaurant_toujours() {
        Vente vente = Vente.builder().reference("VTE-2026-000001").build();
        LigneRetourVente boisson = LigneRetourVente.builder().ligneVente(ligneVendue(article(5, "Primus", TypeArticle.BOISSON), "1", "2", false))
            .quantite(BigDecimal.ONE).montantHt(new BigDecimal("2")).build();
        LigneRetourVente plat = LigneRetourVente.builder().ligneVente(ligneVendue(article(6, "Poulet", TypeArticle.PLAT), "1", "5", false))
            .quantite(BigDecimal.ONE).montantHt(new BigDecimal("5")).build();

        assertThat(RetourVenteService.responsables(retourDe(vente, boisson)))
            .containsExactly(RoleType.RESP_RESTAURANT, RoleType.RESP_BAR);
        RetourVente mixte = retourDe(vente, boisson, plat);
        assertThat(RetourVenteService.responsables(mixte))
            .containsExactlyInAnyOrder(RoleType.RESP_RESTAURANT, RoleType.RESP_BAR, RoleType.RESP_CUISINE);
        assertThat(RetourVenteService.dansLePerimetre(mixte, new PerimetreRestaurant.Perimetre(true, Set.of(TypeArticle.BOISSON))))
            .as("le bar seul ne décide pas d'un retour qui comporte un plat").isFalse();
        assertThat(RetourVenteService.dansLePerimetre(mixte, PerimetreRestaurant.Perimetre.SANS_RESTRICTION)).isTrue();
    }

    @Test
    void le_remboursement_est_le_miroir_de_la_vente_pour_la_part_rendue() {
        CompteOHADARepository comptes = mock(CompteOHADARepository.class);
        CompteOHADA tva = CompteOHADA.builder().id(4431L).numero("4431").build();
        CompteOHADA caisse = CompteOHADA.builder().id(571L).numero("571").build();
        when(comptes.findByNumero("4431")).thenReturn(Optional.of(tva));
        RetourVenteService service = new RetourVenteService(null, null, comptes, null, null, null, null, null, null,
            mock(ConversionDeviseService.class), null, null, null);
        Vente vente = Vente.builder().reference("VTE-2026-000007").devise(ConversionDeviseService.DEVISE_BASE)
            .modeReglement(ModeReglement.CAISSE).dateVente(LocalDate.of(2026, 10, 25)).build();
        Article primus = article(5, "Primus", TypeArticle.BOISSON);
        Article fanta = article(6, "Fanta", TypeArticle.BOISSON);
        RetourVente r = retourDe(vente,
            LigneRetourVente.builder().ligneVente(ligneVendue(primus, "2", "1.10", true)).quantite(new BigDecimal("2"))
                .montantHt(new BigDecimal("2.20")).montantTva(new BigDecimal("0.35")).build(),
            LigneRetourVente.builder().ligneVente(ligneVendue(fanta, "1", "1.00", false)).quantite(BigDecimal.ONE)
                .montantHt(new BigDecimal("1.00")).montantTva(BigDecimal.ZERO).build());

        List<EcritureGrandLivre> e = service.ecrituresRemboursement(r, caisse, "Retour", LocalDate.of(2026, 10, 26));

        assertThat(e).hasSize(4);
        assertThat(e.get(0).getCompte()).isSameAs(primus.getCompteProduit());
        assertThat(e.get(0).getDebit()).isEqualByComparingTo("2.20");
        assertThat(e.get(1).getCompte()).isSameAs(fanta.getCompteProduit());
        assertThat(e.get(1).getDebit()).isEqualByComparingTo("1.00");
        assertThat(e.get(2).getCompte()).isSameAs(tva);
        assertThat(e.get(2).getDebit()).isEqualByComparingTo("0.35");
        assertThat(e.get(3).getCompte()).isSameAs(caisse);
        assertThat(e.get(3).getCredit()).as("crédit = somme exacte des débits").isEqualByComparingTo("3.55");
        BigDecimal debits = e.stream().map(EcritureGrandLivre::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = e.stream().map(EcritureGrandLivre::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debits).isEqualByComparingTo(credits);
    }
}
