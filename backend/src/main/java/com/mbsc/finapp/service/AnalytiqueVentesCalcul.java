package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.enums.ActiviteAnalytique;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Reflet analytique d'une vente, calcul pur (testable sans base) : chiffre d'affaires HT et coût des ventes par activité,
 * en devise de base — voir {@link AnalytiqueVentesService}.
 *
 * <p>Le chiffre d'affaires reprend <b>exactement</b> les crédits des comptes de produit de la pièce de vente (montants du
 * grand livre, déjà convertis) : la classe 9 se rapproche ainsi au centime de la classe 7. Un compte de produit porté par
 * des articles de plusieurs activités se répartit au prorata de leur HT, la dernière activité absorbant l'arrondi. Le coût
 * reprend les lignes de la sortie de stock de la vente (coût moyen, ou coût du camion de minerais).</p>
 */
final class AnalytiqueVentesCalcul {

    private AnalytiqueVentesCalcul() {
    }

    /** Ligne de la vente : activité de son article, compte de produit crédité, montant HT dans la devise de la vente. */
    record LigneVue(ActiviteAnalytique activite, Long compteProduitId, BigDecimal montantHt) {}

    /** Crédit d'un compte de produit dans la pièce de vente (devise de base, montant exact du grand livre). */
    record CreditProduit(Long compteId, BigDecimal montant) {}

    /** Coût d'une ligne de la sortie de stock de la vente (devise de base). */
    record Cout(ActiviteAnalytique activite, BigDecimal montant) {}

    /** Chiffre d'affaires HT et coût des ventes par activité (devise de base), sans montant nul, dans l'ordre des activités. */
    record Reflet(Map<ActiviteAnalytique, BigDecimal> chiffreAffaires, Map<ActiviteAnalytique, BigDecimal> couts) {

        BigDecimal totalChiffreAffaires() {
            return somme(chiffreAffaires);
        }

        BigDecimal totalCouts() {
            return somme(couts);
        }

        boolean estVide() {
            return chiffreAffaires.isEmpty() && couts.isEmpty();
        }
    }

    static Reflet calculer(List<LigneVue> lignes, List<CreditProduit> credits, List<Cout> couts) {
        Map<ActiviteAnalytique, BigDecimal> chiffreAffaires = new EnumMap<>(ActiviteAnalytique.class);
        for (CreditProduit credit : credits) {
            Map<ActiviteAnalytique, BigDecimal> poids = new EnumMap<>(ActiviteAnalytique.class);
            for (LigneVue l : lignes) {
                if (credit.compteId() != null && credit.compteId().equals(l.compteProduitId())) {
                    poids.merge(l.activite(), nz(l.montantHt()), BigDecimal::add);
                }
            }
            if (!poids.isEmpty()) {
                repartir(nz(credit.montant()), poids).forEach((a, m) -> chiffreAffaires.merge(a, m, BigDecimal::add));
            }
        }
        Map<ActiviteAnalytique, BigDecimal> coutsParActivite = new EnumMap<>(ActiviteAnalytique.class);
        for (Cout c : couts) {
            coutsParActivite.merge(c.activite(), nz(c.montant()), BigDecimal::add);
        }
        chiffreAffaires.values().removeIf(m -> m.signum() == 0);
        coutsParActivite.values().removeIf(m -> m.signum() == 0);
        return new Reflet(Collections.unmodifiableMap(chiffreAffaires), Collections.unmodifiableMap(coutsParActivite));
    }

    /**
     * Répartit {@code montant} au prorata des poids (au centime) ; la dernière activité reçoit le reste, si bien que la
     * somme des parts vaut exactement {@code montant}. Une seule activité : tout lui revient.
     */
    static Map<ActiviteAnalytique, BigDecimal> repartir(BigDecimal montant, Map<ActiviteAnalytique, BigDecimal> poids) {
        Map<ActiviteAnalytique, BigDecimal> parts = new EnumMap<>(ActiviteAnalytique.class);
        List<ActiviteAnalytique> ordre = new ArrayList<>(poids.keySet());
        BigDecimal total = somme(poids);
        BigDecimal reparti = BigDecimal.ZERO;
        for (int i = 0; i < ordre.size(); i++) {
            ActiviteAnalytique a = ordre.get(i);
            BigDecimal part;
            if (i == ordre.size() - 1) {
                part = montant.subtract(reparti);
            } else if (total.signum() == 0) {
                part = montant.divide(BigDecimal.valueOf(ordre.size()), 2, RoundingMode.HALF_UP);
            } else {
                part = montant.multiply(poids.get(a)).divide(total, 2, RoundingMode.HALF_UP);
            }
            reparti = reparti.add(part);
            parts.put(a, part);
        }
        return parts;
    }

    private static BigDecimal somme(Map<ActiviteAnalytique, BigDecimal> montants) {
        return montants.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
