package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.enums.ActiviteAnalytique;
import com.mbsc.finapp.domain.enums.TypeArticle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.mbsc.finapp.domain.enums.ActiviteAnalytique.BAR;
import static com.mbsc.finapp.domain.enums.ActiviteAnalytique.CUISINE;
import static com.mbsc.finapp.domain.enums.ActiviteAnalytique.MARCHANDISES;
import static com.mbsc.finapp.domain.enums.ActiviteAnalytique.MINERAIS;
import static com.mbsc.finapp.domain.enums.ActiviteAnalytique.SERVICES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Reflet analytique d'une vente : chiffre d'affaires HT et coût des ventes par activité. Le chiffre d'affaires reprend au
 * centime les crédits de produit de la pièce de vente (la classe 9 se rapproche ainsi exactement de la classe 7).
 */
class AnalytiqueVentesCalculTest {

    private static final long COMPTE_BOISSONS = 1L;
    private static final long COMPTE_PLATS = 2L;
    private static final long COMPTE_SERVICES = 3L;
    private static final long COMPTE_TVA = 9L;

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static AnalytiqueVentesCalcul.LigneVue ligne(ActiviteAnalytique a, long compte, String ht) {
        return new AnalytiqueVentesCalcul.LigneVue(a, compte, d(ht));
    }

    private static AnalytiqueVentesCalcul.CreditProduit credit(long compte, String montant) {
        return new AnalytiqueVentesCalcul.CreditProduit(compte, d(montant));
    }

    private static AnalytiqueVentesCalcul.Cout cout(ActiviteAnalytique a, String montant) {
        return new AnalytiqueVentesCalcul.Cout(a, d(montant));
    }

    @Test
    void lActiviteSuitLArticleVendu() {
        assertThat(ActiviteAnalytique.de(Article.builder().type(TypeArticle.BOISSON).build())).isEqualTo(BAR);
        assertThat(ActiviteAnalytique.de(Article.builder().type(TypeArticle.PLAT).build())).isEqualTo(CUISINE);
        assertThat(ActiviteAnalytique.de(Article.builder().type(TypeArticle.PROVISION).build())).isEqualTo(CUISINE);
        assertThat(ActiviteAnalytique.de(Article.builder().type(TypeArticle.SERVICE).build())).isEqualTo(SERVICES);
        assertThat(ActiviteAnalytique.de(Article.builder().type(TypeArticle.MARCHANDISE).build())).isEqualTo(MARCHANDISES);
        assertThat(ActiviteAnalytique.de(Article.builder().type(TypeArticle.CONSOMMABLE).build())).isEqualTo(MARCHANDISES);
        // Un minerais (camion) relève des Minerais, même s'il est une marchandise.
        assertThat(ActiviteAnalytique.de(Article.builder().type(TypeArticle.MARCHANDISE).minerais(true).build())).isEqualTo(MINERAIS);
    }

    @Test
    void chaqueActiviteASonCompteDeResultatEtSesDeuxSousComptes() {
        assertThat(BAR.compte()).isEqualTo("981");
        assertThat(BAR.compteChiffreAffaires()).isEqualTo("9811");
        assertThat(BAR.compteCout()).isEqualTo("9812");
        assertThat(CUISINE.compte()).isEqualTo("982");
        assertThat(MARCHANDISES.compte()).isEqualTo("983");
        assertThat(MINERAIS.compte()).isEqualTo("984");
        assertThat(SERVICES.compteChiffreAffaires()).isEqualTo("9851");
    }

    @Test
    void leChiffreDAffairesReprendLesCreditsDeLaPieceDeVenteParActivite() {
        // Vente en francs : le HT des lignes (CDF) ne sert qu'à répartir ; les montants sont ceux du grand livre (USD).
        AnalytiqueVentesCalcul.Reflet r = AnalytiqueVentesCalcul.calculer(
            List.of(ligne(BAR, COMPTE_BOISSONS, "10000"), ligne(BAR, COMPTE_BOISSONS, "2500"), ligne(CUISINE, COMPTE_PLATS, "5000")),
            List.of(credit(COMPTE_BOISSONS, "5.56"), credit(COMPTE_PLATS, "2.22"), credit(COMPTE_TVA, "1.24")),
            List.of());

        assertThat(r.chiffreAffaires()).containsExactly(entry(BAR, d("5.56")), entry(CUISINE, d("2.22")));
        assertThat(r.totalChiffreAffaires()).isEqualByComparingTo("7.78");   // la TVA n'est pas du chiffre d'affaires
        assertThat(r.couts()).isEmpty();
    }

    @Test
    void unCompteDeProduitPartageSeRepartitAuProrataEtLaSommeEstExacte() {
        AnalytiqueVentesCalcul.Reflet r = AnalytiqueVentesCalcul.calculer(
            List.of(ligne(BAR, COMPTE_BOISSONS, "1"), ligne(MARCHANDISES, COMPTE_BOISSONS, "2")),
            List.of(credit(COMPTE_BOISSONS, "1.00")),
            List.of());

        assertThat(r.chiffreAffaires()).containsExactly(entry(BAR, d("0.33")), entry(MARCHANDISES, d("0.67")));
        assertThat(r.totalChiffreAffaires()).isEqualByComparingTo("1.00");
    }

    @Test
    void lesCoutsSAdditionnentParActivite() {
        AnalytiqueVentesCalcul.Reflet r = AnalytiqueVentesCalcul.calculer(
            List.of(ligne(BAR, COMPTE_BOISSONS, "10"), ligne(CUISINE, COMPTE_PLATS, "20"), ligne(SERVICES, COMPTE_SERVICES, "5")),
            List.of(credit(COMPTE_BOISSONS, "10.00"), credit(COMPTE_PLATS, "20.00"), credit(COMPTE_SERVICES, "5.00")),
            List.of(cout(BAR, "2.50"), cout(BAR, "1.25"), cout(CUISINE, "12.00")));

        assertThat(r.couts()).containsExactly(entry(BAR, d("3.75")), entry(CUISINE, d("12.00")));
        assertThat(r.totalCouts()).isEqualByComparingTo("15.75");
        // Un service n'a pas de coût de sortie de stock.
        assertThat(r.couts()).doesNotContainKey(SERVICES);
    }

    @Test
    void lesMontantsNulsSontOmisEtUneVenteSansRienEstVide() {
        AnalytiqueVentesCalcul.Reflet r = AnalytiqueVentesCalcul.calculer(
            List.of(ligne(BAR, COMPTE_BOISSONS, "0")),
            List.of(credit(COMPTE_TVA, "3.00")),
            List.of(cout(BAR, "0.00")));

        assertThat(r.estVide()).isTrue();
        assertThat(r.totalChiffreAffaires()).isZero();
    }

    @Test
    void sansPoidsLaRepartitionEstEgaleEtExacte() {
        Map<ActiviteAnalytique, BigDecimal> poids = new EnumMap<>(ActiviteAnalytique.class);
        poids.put(BAR, BigDecimal.ZERO);
        poids.put(CUISINE, BigDecimal.ZERO);
        poids.put(SERVICES, BigDecimal.ZERO);

        Map<ActiviteAnalytique, BigDecimal> parts = AnalytiqueVentesCalcul.repartir(d("1.00"), poids);

        assertThat(parts).containsExactly(entry(BAR, d("0.33")), entry(CUISINE, d("0.33")), entry(SERVICES, d("0.34")));
    }
}
