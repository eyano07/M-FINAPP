package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.enums.PeriodeRapport;
import com.mbsc.finapp.dto.caisse.RapportCaisseResponse;
import com.mbsc.finapp.dto.caisse.RapportCaisseResponse.Jour;
import com.mbsc.finapp.dto.caisse.RapportCaisseResponse.Mouvement;
import com.mbsc.finapp.dto.caisse.RapportCaisseResponse.Nature;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Calcul pur du rapport de caisse (voir {@link RapportCaisseService}) : classe chaque ligne de la caisse en entree ou en
 * sortie, convertit CHAQUE montant dans la devise d'affichage, puis additionne les montants deja convertis — totaux,
 * resume par jour et par nature, solde de cloture — pour que le rapport s'additionne exactement, en francs comme en
 * dollars. Sans Spring ni base de donnees : testable tel quel.
 */
public final class RapportCaisseCalcul {

    private RapportCaisseCalcul() {
    }

    /**
     * Ligne du compte de caisse telle que le rapport la voit, dans la devise de base : debit = entree d'argent, credit =
     * sortie. Les champs de contrepartie decrivent les autres lignes de la piece.
     */
    public record MouvementBrut(LocalDate date, Instant heure, String pieceReference, String numeroRecu, String libelle,
                                String compteNumero, String compteLibelle, String autresComptes, String caissierNom,
                                boolean annulee, BigDecimal debit, BigDecimal credit) {
    }

    private static final class Cumul {
        BigDecimal entrees = BigDecimal.ZERO;
        BigDecimal sorties = BigDecimal.ZERO;
        int nombreEntrees;
        int nombreSorties;
        String libelle;
    }

    public static RapportCaisseResponse construire(PeriodeRapport periode, LocalDate date, PeriodeRapport.Bornes bornes,
                                                   ConversionDeviseService.Affichage affichage,
                                                   BigDecimal soldeOuvertureBase, List<MouvementBrut> mouvements) {
        BigDecimal ouverture = affichage.convertir(nz(soldeOuvertureBase));
        List<Mouvement> entrees = new ArrayList<>();
        List<Mouvement> sorties = new ArrayList<>();
        Map<LocalDate, Cumul> parJour = new TreeMap<>();
        Map<String, Cumul> parNature = new LinkedHashMap<>();
        BigDecimal totalEntrees = BigDecimal.ZERO;
        BigDecimal totalSorties = BigDecimal.ZERO;

        for (MouvementBrut m : mouvements) {
            BigDecimal net = nz(m.debit()).subtract(nz(m.credit()));
            if (net.signum() == 0) {
                continue;   // ligne a zero : ni entree ni sortie
            }
            boolean entree = net.signum() > 0;
            BigDecimal montant = affichage.convertir(net.abs());
            Mouvement mouvement = new Mouvement(m.date(), m.heure(), m.pieceReference(), m.numeroRecu(), m.libelle(),
                m.compteNumero(), m.compteLibelle(), m.autresComptes(), m.caissierNom(), m.annulee(), montant);
            Cumul jour = parJour.computeIfAbsent(m.date(), d -> new Cumul());
            Cumul nature = parNature.computeIfAbsent(m.compteNumero() == null ? "" : m.compteNumero(), k -> new Cumul());
            if (nature.libelle == null) {
                nature.libelle = m.compteLibelle();
            }
            if (entree) {
                entrees.add(mouvement);
                totalEntrees = totalEntrees.add(montant);
                jour.entrees = jour.entrees.add(montant);
                jour.nombreEntrees++;
                nature.entrees = nature.entrees.add(montant);
                nature.nombreEntrees++;
            } else {
                sorties.add(mouvement);
                totalSorties = totalSorties.add(montant);
                jour.sorties = jour.sorties.add(montant);
                jour.nombreSorties++;
                nature.sorties = nature.sorties.add(montant);
                nature.nombreSorties++;
            }
        }

        List<Jour> jours = new ArrayList<>();
        BigDecimal solde = ouverture;
        for (Map.Entry<LocalDate, Cumul> e : parJour.entrySet()) {
            Cumul c = e.getValue();
            BigDecimal variation = c.entrees.subtract(c.sorties);
            solde = solde.add(variation);
            jours.add(new Jour(e.getKey(), c.entrees, c.sorties, variation, solde, c.nombreEntrees, c.nombreSorties));
        }

        // Les comptes dans l'ordre de leur numero ; les mouvements sans contrepartie identifiable en dernier.
        List<Nature> natures = parNature.entrySet().stream()
            .sorted(Comparator.comparing((Map.Entry<String, Cumul> e) -> e.getKey().isEmpty())
                .thenComparing(Map.Entry::getKey))
            .map(e -> new Nature(e.getKey(), e.getValue().libelle, e.getValue().entrees, e.getValue().sorties,
                e.getValue().nombreEntrees + e.getValue().nombreSorties))
            .toList();

        return new RapportCaisseResponse(periode, date, bornes.du(), bornes.au(), affichage.devise(), affichage.taux(),
            affichage.dateTaux(), ouverture, totalEntrees, totalSorties, ouverture.add(totalEntrees).subtract(totalSorties),
            jours, entrees, sorties, natures);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
