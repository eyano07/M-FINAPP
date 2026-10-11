package com.mbsc.finapp.service;

import com.mbsc.finapp.service.PeremptionPlats.LotProduit;
import com.mbsc.finapp.service.PeremptionPlats.Mouvement;
import com.mbsc.finapp.service.PeremptionPlats.Part;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Date d'expiration d'une production, et portions qui l'ont dépassée sans être vendues. */
class PeremptionPlatsTest {

    /** Le jour où l'on juge la péremption. */
    private static final LocalDate J = LocalDate.of(2026, 10, 13);

    private static BigDecimal q(String valeur) {
        return new BigDecimal(valeur);
    }

    /** Production faite {@code ilYaJours} jours, expirant à {@code expireDansJours} jours de J (null : jamais). */
    private static LotProduit lot(String reference, int ilYaJours, Integer expireDansJours, String quantite) {
        return new LotProduit(reference, J.minusDays(ilYaJours), expireDansJours == null ? null : J.plusDays(expireDansJours), q(quantite));
    }

    private static Mouvement entree(LotProduit lot) {
        return new Mouvement(lot.dateProduction(), lot.quantite(), BigDecimal.ZERO, lot, false);
    }

    private static Mouvement sortie(int ilYaJours, String quantite) {
        return new Mouvement(J.minusDays(ilYaJours), BigDecimal.ZERO, q(quantite), null, false);
    }

    private static Mouvement retraitPerime(int ilYaJours, String quantite) {
        return new Mouvement(J.minusDays(ilYaJours), BigDecimal.ZERO, q(quantite), null, true);
    }

    private static Part de(List<Part> parts, String reference) {
        return parts.stream().filter(p -> reference.equals(p.lot().reference())).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------------
    // La date d'expiration
    // ---------------------------------------------------------------------

    @Test
    void laDateDExpirationEstLaDateDeProductionPlusLaDuree() {
        LocalDate production = LocalDate.of(2026, 10, 11);
        assertThat(PeremptionPlats.dateExpiration(production, 2)).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(PeremptionPlats.dateExpiration(production, 0)).isEqualTo(production);
        assertThat(PeremptionPlats.dateExpiration(production, 365)).isEqualTo(LocalDate.of(2027, 10, 11));
    }

    @Test
    void sansDureeDeConservationLePlatNeSePerimePas() {
        assertThat(PeremptionPlats.dateExpiration(LocalDate.of(2026, 10, 11), null)).isNull();
        assertThat(PeremptionPlats.estPerimee(null, J)).isFalse();
    }

    @Test
    void ilSeVendJusquAuJourDExpirationInclusEtSePerimeLeLendemain() {
        assertThat(PeremptionPlats.estPerimee(J.plusDays(1), J)).isFalse();
        assertThat(PeremptionPlats.estPerimee(J, J)).isFalse();
        assertThat(PeremptionPlats.estPerimee(J.minusDays(1), J)).isTrue();
    }

    @Test
    void unPlatADureeNulleSeVendLeJourMemeEtSePerimeLeLendemain() {
        LocalDate expiration = PeremptionPlats.dateExpiration(J, 0);
        assertThat(PeremptionPlats.estPerimee(expiration, J)).isFalse();
        assertThat(PeremptionPlats.estPerimee(expiration, J.plusDays(1))).isTrue();
    }

    // ---------------------------------------------------------------------
    // Les portions qui restent
    // ---------------------------------------------------------------------

    @Test
    void uneProductionFraicheNEstPasPerimee() {
        LotProduit p = lot("PRD-1", 0, 1, "10");

        List<Part> parts = PeremptionPlats.rejouer(List.of(entree(p), sortie(0, "4")), J);

        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).quantite()).isEqualByComparingTo("6");
        assertThat(PeremptionPlats.quantitePerimee(parts)).isEqualByComparingTo("0");
    }

    @Test
    void lesPortionsNonVenduesApresLExpirationSontPerimees() {
        // 8 portions produites il y a 3 jours, expirées hier : 3 vendues avant l'expiration, 5 restent.
        LotProduit p = lot("PRD-1", 3, -1, "8");

        List<Part> parts = PeremptionPlats.rejouer(List.of(entree(p), sortie(2, "3")), J);

        assertThat(PeremptionPlats.quantitePerimee(parts)).isEqualByComparingTo("5");
        assertThat(de(parts, "PRD-1").perimee()).isTrue();
    }

    @Test
    void uneProductionExpireeResteperimeeQuandOnVendLesProductionsSuivantes() {
        // Le cas courant : on produit chaque jour. La production d'avant-hier est expirée ; on vend 8 portions aujourd'hui.
        // Les ventes prennent les portions encore bonnes (celles du jour), pas les périmées : 5 restent périmées.
        LotProduit p1 = lot("PRD-1", 3, -1, "8");
        LotProduit p2 = lot("PRD-2", 0, 1, "10");

        List<Part> parts = PeremptionPlats.rejouer(
            List.of(entree(p1), sortie(2, "3"), entree(p2), sortie(0, "8")), J);

        assertThat(de(parts, "PRD-1").quantite()).isEqualByComparingTo("5");
        assertThat(de(parts, "PRD-1").perimee()).isTrue();
        assertThat(de(parts, "PRD-2").quantite()).isEqualByComparingTo("2");
        assertThat(de(parts, "PRD-2").perimee()).isFalse();
        assertThat(PeremptionPlats.quantitePerimee(parts)).isEqualByComparingTo("5");
    }

    @Test
    void lesPortionsPerimeesNeSontEntameesQueSiIlNeResteRienDeBon() {
        LotProduit p1 = lot("PRD-1", 3, -1, "8");
        LotProduit p2 = lot("PRD-2", 0, 1, "10");

        // 12 vendues aujourd'hui : les 10 bonnes, puis 2 des périmées.
        List<Part> parts = PeremptionPlats.rejouer(
            List.of(entree(p1), sortie(2, "3"), entree(p2), sortie(0, "12")), J);

        assertThat(PeremptionPlats.quantitePerimee(parts)).isEqualByComparingTo("3");
        assertThat(parts.stream().anyMatch(x -> "PRD-2".equals(x.lot().reference()))).isFalse();
    }

    @Test
    void unRetraitPerimeEntameLePerimeEnPremier() {
        LotProduit p1 = lot("PRD-1", 3, -1, "8");
        LotProduit p2 = lot("PRD-2", 0, 1, "10");

        List<Part> parts = PeremptionPlats.rejouer(
            List.of(entree(p1), sortie(2, "3"), entree(p2), retraitPerime(0, "5")), J);

        assertThat(PeremptionPlats.quantitePerimee(parts)).isEqualByComparingTo("0");
        assertThat(de(parts, "PRD-2").quantite()).isEqualByComparingTo("10");
        assertThat(parts.stream().anyMatch(x -> "PRD-1".equals(x.lot().reference()))).isFalse();
    }

    @Test
    void unRetraitPerimeQuiDepasseLePerimeEntameLeRestePuisqueLeStockFaitFoi() {
        LotProduit p1 = lot("PRD-1", 3, -1, "8");
        LotProduit p2 = lot("PRD-2", 0, 1, "10");

        List<Part> parts = PeremptionPlats.rejouer(
            List.of(entree(p1), sortie(2, "3"), entree(p2), retraitPerime(0, "7")), J);

        assertThat(PeremptionPlats.quantitePerimee(parts)).isEqualByComparingTo("0");
        assertThat(de(parts, "PRD-2").quantite()).isEqualByComparingTo("8");
    }

    @Test
    void uneSortieAvantLExpirationPrendLaPlusAncienneProduction() {
        LotProduit p1 = lot("PRD-1", 3, -1, "8");
        LotProduit p2 = lot("PRD-2", 2, 0, "8");

        // Avant-hier, les deux productions sont bonnes : les 10 portions vendues viennent d'abord de la plus ancienne.
        List<Part> parts = PeremptionPlats.rejouer(List.of(entree(p1), entree(p2), sortie(2, "10")), J);

        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).lot().reference()).isEqualTo("PRD-2");
        assertThat(parts.get(0).quantite()).isEqualByComparingTo("6");
        // PRD-2 expire aujourd'hui : elle est encore vendable, et périmée demain.
        assertThat(parts.get(0).perimee()).isFalse();
        assertThat(PeremptionPlats.quantitePerimee(PeremptionPlats.rejouer(
            List.of(entree(p1), entree(p2), sortie(2, "10")), J.plusDays(1)))).isEqualByComparingTo("6");
    }

    @Test
    void uneEntreeSansProductionNExpireJamais() {
        LotProduit inconnue = new LotProduit(null, J.minusDays(400), null, q("7"));

        List<Part> parts = PeremptionPlats.rejouer(List.of(entree(inconnue)), J.plusDays(1000));

        assertThat(parts).hasSize(1);
        assertThat(PeremptionPlats.quantitePerimee(parts)).isEqualByComparingTo("0");
    }

    @Test
    void uneSortiePlusGrandeQueLeStockConnuNeFaitPasEchouerLeCalcul() {
        LotProduit p = lot("PRD-1", 1, 1, "10");

        assertThat(PeremptionPlats.rejouer(List.of(entree(p), sortie(0, "20")), J)).isEmpty();
    }

    @Test
    void sansMouvementIlNYARienDePerime() {
        assertThat(PeremptionPlats.rejouer(List.of(), J)).isEmpty();
        assertThat(PeremptionPlats.quantitePerimee(List.of())).isEqualByComparingTo("0");
    }

    @Test
    void laMemeProductionNEstPerimeeQueLeLendemainDeSonExpiration() {
        List<Mouvement> historique = List.of(entree(lot("PRD-1", 3, 0, "8")));

        assertThat(PeremptionPlats.quantitePerimee(PeremptionPlats.rejouer(historique, J))).isEqualByComparingTo("0");
        assertThat(PeremptionPlats.quantitePerimee(PeremptionPlats.rejouer(historique, J.plusDays(1)))).isEqualByComparingTo("8");
    }

    @Test
    void plusieursProductionsExpireesSeCumulent() {
        List<Part> parts = PeremptionPlats.rejouer(List.of(
            entree(lot("PRD-1", 6, -4, "5")), entree(lot("PRD-2", 4, -2, "5")), entree(lot("PRD-3", 0, 2, "10"))), J);

        assertThat(PeremptionPlats.quantitePerimee(parts)).isEqualByComparingTo("10");
        assertThat(parts.stream().filter(Part::perimee).map(p -> p.lot().reference())).containsExactly("PRD-1", "PRD-2");
    }
}
