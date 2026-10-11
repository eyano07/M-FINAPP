package com.mbsc.finapp.domain.enums;

import org.junit.jupiter.api.Test;

import static com.mbsc.finapp.domain.enums.TypeMouvementEmballage.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Effet de chaque mouvement sur le stock de bouteilles vides (le sens est porté par le type). */
class TypeMouvementEmballageTest {

    @Test
    void uneBouteilleConsommeeSurPlaceRevientEnVides() {
        assertThat(VENTE.delta(3)).isEqualTo(3);
        // Cadeau : le contenu est consommé comme pour une vente, la bouteille vide revient en stock.
        assertThat(CADEAU.delta(3)).isEqualTo(3);
    }

    @Test
    void uneBoissonPerimeeRemetSaBouteilleEnVides() {
        // C'est la boisson qui est périmée, pas la bouteille : le contenu est jeté, la bouteille vidée revient en stock.
        assertThat(PERIME.delta(5)).isEqualTo(5);
        assertThat(PERIME.delta(5)).isEqualTo(CADEAU.delta(5));
    }

    @Test
    void lesCassesEtLesRetoursRetirentDesVides() {
        assertThat(CASSE.delta(2)).isEqualTo(-2);
        assertThat(CASSE_PLEINE.delta(2)).isEqualTo(-2);
        assertThat(RETOUR_VENTE.delta(2)).isEqualTo(-2);
        assertThat(ACHAT.delta(2)).isEqualTo(-2);
    }

    @Test
    void lesAjustementsVontDansLesDeuxSens() {
        assertThat(AJUSTEMENT_PLUS.delta(4)).isEqualTo(4);
        assertThat(AJUSTEMENT_MOINS.delta(4)).isEqualTo(-4);
    }

    @Test
    void lesPertesDeBoissonSortentDuStockAvecLeurEcriture() {
        assertThat(CADEAU.sortDuStockDeBoisson()).isTrue();
        assertThat(PERIME.sortDuStockDeBoisson()).isTrue();
        assertThat(CASSE_PLEINE.sortDuStockDeBoisson()).isTrue();
        assertThat(VENTE.sortDuStockDeBoisson()).isFalse();
        assertThat(CASSE.sortDuStockDeBoisson()).isFalse();
    }

    @Test
    void annulerUneBoissonOfferteOuPerimeeRetireLesVidesRemis() {
        // L'annulation produit le mouvement inverse du delta : ajustement en moins pour un delta positif.
        for (TypeMouvementEmballage type : new TypeMouvementEmballage[] { CADEAU, PERIME }) {
            int delta = type.delta(5);
            assertThat(delta).as(type.name()).isPositive();
            assertThat(AJUSTEMENT_MOINS.delta(Math.abs(delta))).as(type.name()).isEqualTo(-delta);
        }
    }
}
