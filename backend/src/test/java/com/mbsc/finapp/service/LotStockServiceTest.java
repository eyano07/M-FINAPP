package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.Entrepot;
import com.mbsc.finapp.domain.LigneMouvementStock;
import com.mbsc.finapp.domain.LigneNoteFrais;
import com.mbsc.finapp.domain.LotStock;
import com.mbsc.finapp.domain.LotStockConsommation;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.repository.LotStockConsommationRepository;
import com.mbsc.finapp.repository.LotStockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Répartition d'une sortie sur les lots. Cas central : le retour d'achat rend au fournisseur une partie d'une livraison
 * précise, c'est le lot de cette livraison qui diminue — pas le plus ancien de l'article, que le FIFO viderait d'abord
 * (la page « Stock cuisine & bar » montrait alors 60 bouteilles pour une livraison dont 40 avaient été rendues).
 */
class LotStockServiceTest {

    private static final long LIGNE_NOTE_RETOUR = 11L;   // la ligne de la note dont la livraison est rendue
    private static final long LIGNE_NOTE_AUTRE = 9L;     // une autre note, plus ancienne

    private final LotStockRepository lots = mock(LotStockRepository.class);
    private final LotStockConsommationRepository consommations = mock(LotStockConsommationRepository.class);
    private final LotStockService service = new LotStockService(lots, consommations);

    private final Entrepot bar = Entrepot.builder().id(5L).code("BAR").build();
    private final Article nkoyi = Article.builder().id(1L).code("BRAC-NKOY-BLAC-50CL").type(TypeArticle.BOISSON).build();
    private final Article riz = Article.builder().id(2L).code("PROV-RIZ").type(TypeArticle.PROVISION).build();
    private final LigneMouvementStock sortie = LigneMouvementStock.builder().id(900L).build();

    private LotStock ancien;   // livraison du 18/10 d'une autre note : 96 restantes sur 100
    private LotStock achete;   // livraison du 22/10, celle de la note retournée : 60 restantes sur 60

    private static LotStock lot(long id, Article article, String date, String initiale, String restante, Long ligneNoteId) {
        return LotStock.builder().id(id).article(article).dateEntree(LocalDate.parse(date))
            .quantiteInitiale(new BigDecimal(initiale)).quantiteRestante(new BigDecimal(restante))
            .ligneNoteFrais(ligneNoteId == null ? null : LigneNoteFrais.builder().id(ligneNoteId).build())
            .build();
    }

    @BeforeEach
    void preparer() {
        ancien = lot(1L, nkoyi, "2026-10-18", "100", "96", LIGNE_NOTE_AUTRE);
        achete = lot(2L, nkoyi, "2026-10-22", "60", "60", LIGNE_NOTE_RETOUR);
        // Ordre du repository : du plus ancien au plus récent (l'ordre du FIFO).
        when(lots.findByArticleIdAndEntrepotIdAndQuantiteRestanteGreaterThanOrderByDateEntreeAscIdAsc(1L, 5L, BigDecimal.ZERO))
            .thenReturn(List.of(ancien, achete));
    }

    private static String qte(LotStock lot) {
        return lot.getQuantiteRestante().stripTrailingZeros().toPlainString();
    }

    @Test
    void une_vente_ordinaire_vide_d_abord_le_plus_ancien_lot_FIFO() {
        service.consommer(nkoyi, bar, new BigDecimal("40"), sortie);

        assertThat(qte(ancien)).isEqualTo("56");
        assertThat(qte(achete)).isEqualTo("60");
    }

    @Test
    void un_retour_d_achat_vide_le_lot_de_la_livraison_rendue_pas_le_plus_ancien() {
        // Le cas signalé : 40 des 60 bouteilles de la livraison du 22/10 sont rendues au fournisseur.
        List<LotStockService.Part> parts = service.consommer(nkoyi, bar, new BigDecimal("40"), sortie, LIGNE_NOTE_RETOUR);

        assertThat(qte(achete)).isEqualTo("20");
        assertThat(qte(ancien)).isEqualTo("96");   // l'autre livraison n'est pas touchée
        assertThat(parts).singleElement().satisfies(p -> {
            assertThat(p.lot()).isSameAs(achete);
            assertThat(p.quantite()).isEqualByComparingTo("40");
        });
    }

    @Test
    void le_detail_pris_sur_chaque_lot_est_journalise_pour_une_annulation_exacte() {
        service.consommer(nkoyi, bar, new BigDecimal("40"), sortie, LIGNE_NOTE_RETOUR);

        ArgumentCaptor<LotStockConsommation> c = ArgumentCaptor.forClass(LotStockConsommation.class);
        verify(consommations).save(c.capture());
        assertThat(c.getValue().getLot()).isSameAs(achete);
        assertThat(c.getValue().getLigneMouvement()).isSameAs(sortie);
        assertThat(c.getValue().getQuantite()).isEqualByComparingTo("40");
    }

    @Test
    void la_livraison_rendue_deja_en_partie_vendue_complete_sur_les_autres_lots_dans_l_ordre_FIFO() {
        achete.setQuantiteRestante(new BigDecimal("10"));   // 50 des 60 déjà vendues

        service.consommer(nkoyi, bar, new BigDecimal("40"), sortie, LIGNE_NOTE_RETOUR);

        assertThat(qte(achete)).isEqualTo("0");
        assertThat(qte(ancien)).isEqualTo("66");   // 96 - (40 - 10)
    }

    @Test
    void sans_lot_connu_pour_la_ligne_le_retour_se_comporte_comme_une_sortie_ordinaire() {
        service.consommer(nkoyi, bar, new BigDecimal("40"), sortie, 999L);

        assertThat(qte(ancien)).isEqualTo("56");
        assertThat(qte(achete)).isEqualTo("60");
    }

    @Test
    void un_lot_sans_ligne_de_note_n_est_jamais_pris_pour_la_livraison_rendue() {
        LotStock ouverture = lot(3L, nkoyi, "2026-10-01", "30", "30", null);
        when(lots.findByArticleIdAndEntrepotIdAndQuantiteRestanteGreaterThanOrderByDateEntreeAscIdAsc(1L, 5L, BigDecimal.ZERO))
            .thenReturn(List.of(ouverture, ancien, achete));

        service.consommer(nkoyi, bar, new BigDecimal("40"), sortie, LIGNE_NOTE_RETOUR);

        assertThat(qte(ouverture)).isEqualTo("30");
        assertThat(qte(achete)).isEqualTo("20");
    }

    @Test
    void une_provision_rendue_vide_aussi_sa_livraison_d_abord_puis_le_reste_au_prorata() {
        LotStock vieux = lot(10L, riz, "2026-10-10", "20", "20", LIGNE_NOTE_AUTRE);
        LotStock livre = lot(11L, riz, "2026-10-22", "10", "10", LIGNE_NOTE_RETOUR);
        LotStock voisin = lot(12L, riz, "2026-10-20", "20", "20", null);
        when(lots.findByArticleIdAndEntrepotIdAndQuantiteRestanteGreaterThanOrderByDateEntreeAscIdAsc(2L, 5L, BigDecimal.ZERO))
            .thenReturn(List.of(vieux, voisin, livre));

        // 25 kg rendus : les 10 kg de la livraison, puis 15 kg répartis au prorata sur les deux autres lots (20 + 20)
        service.consommer(riz, bar, new BigDecimal("25"), sortie, LIGNE_NOTE_RETOUR);

        assertThat(qte(livre)).isEqualTo("0");
        assertThat(qte(vieux)).isEqualTo("12.5");
        assertThat(qte(voisin)).isEqualTo("12.5");
    }

    @Test
    void une_provision_dont_la_livraison_couvre_tout_ne_touche_pas_les_autres_lots() {
        LotStock vieux = lot(10L, riz, "2026-10-10", "20", "20", LIGNE_NOTE_AUTRE);
        LotStock livre = lot(11L, riz, "2026-10-22", "10", "10", LIGNE_NOTE_RETOUR);
        when(lots.findByArticleIdAndEntrepotIdAndQuantiteRestanteGreaterThanOrderByDateEntreeAscIdAsc(2L, 5L, BigDecimal.ZERO))
            .thenReturn(List.of(vieux, livre));

        service.consommer(riz, bar, new BigDecimal("2.25"), sortie, LIGNE_NOTE_RETOUR);

        assertThat(qte(livre)).isEqualTo("7.75");
        assertThat(qte(vieux)).isEqualTo("20");
    }

    @Test
    void la_quantite_demandee_est_toujours_entierement_repartie_quand_les_lots_la_couvrent() {
        // 120 bouteilles rendues alors que la livraison n'en a que 60 : 60 d'elle + 60 du plus ancien.
        service.consommer(nkoyi, bar, new BigDecimal("120"), sortie, LIGNE_NOTE_RETOUR);

        assertThat(qte(achete)).isEqualTo("0");
        assertThat(qte(ancien)).isEqualTo("36");
    }

    @Test
    void un_article_plat_n_a_pas_de_lot() {
        Article plat = Article.builder().id(3L).code("PLAT").type(TypeArticle.PLAT).build();

        assertThat(service.consommer(plat, bar, BigDecimal.TEN, sortie, LIGNE_NOTE_RETOUR)).isEmpty();
        verify(consommations, never()).save(any());
    }

    @Test
    void l_entree_par_note_de_frais_retient_la_ligne_de_la_note() {
        LigneNoteFrais ligneNote = LigneNoteFrais.builder().id(LIGNE_NOTE_RETOUR).build();
        LigneMouvementStock entree = LigneMouvementStock.builder().id(800L).build();

        service.enregistrerEntree(nkoyi, bar, LocalDate.of(2026, 10, 22), "Dépôt Luwawa", new BigDecimal("60"),
            new BigDecimal("2450"), new BigDecimal("450"), entree, ligneNote);

        ArgumentCaptor<LotStock> c = ArgumentCaptor.forClass(LotStock.class);
        verify(lots).save(c.capture());
        assertThat(c.getValue().getLigneNoteFrais()).isSameAs(ligneNote);
        assertThat(c.getValue().getFournisseur()).isEqualTo("Dépôt Luwawa");
        assertThat(c.getValue().getQuantiteRestante()).isEqualByComparingTo("60");
    }

    @Test
    void une_entree_hors_note_de_frais_n_a_pas_de_ligne_de_note() {
        service.enregistrerEntree(nkoyi, bar, LocalDate.of(2026, 10, 22), null, new BigDecimal("12"),
            BigDecimal.ONE, BigDecimal.ZERO, LigneMouvementStock.builder().id(801L).build());

        ArgumentCaptor<LotStock> c = ArgumentCaptor.forClass(LotStock.class);
        verify(lots).save(c.capture());
        assertThat(c.getValue().getLigneNoteFrais()).isNull();
    }
}
