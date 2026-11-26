package com.mbsc.finapp.service;

import com.mbsc.finapp.dto.comptabilite.JournalCompteResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calcul pur du journal d'un compte de tresorerie (voir {@link JournalCompteService}) : convertit CHAQUE mouvement dans
 * la devise d'affichage, puis cumule le solde ligne apres ligne a partir du solde d'ouverture converti, pour que le
 * journal s'additionne exactement en francs comme en dollars. Sans Spring ni base de donnees : testable tel quel.
 */
public final class JournalCompteCalcul {

    /** Reference d'une note de frais citee dans un libelle (journal importe, ecriture sans operation). */
    private static final Pattern REFERENCE_NOTE = Pattern.compile("\\bNF-\\d{4}-\\d+\\b");

    private JournalCompteCalcul() {
    }

    /** Ligne du compte telle que le journal la voit, dans la devise de base : debit = entree, credit = sortie. */
    public record MouvementBrut(LocalDate date, String note, String objet, String beneficiaire, String libelle,
                                String comptes, String piece, boolean annulee, BigDecimal debit, BigDecimal credit) {
    }

    public static JournalCompteResponse construire(String numero, String libelle, LocalDate du, LocalDate au,
                                                   ConversionDeviseService.Affichage affichage,
                                                   BigDecimal ouvertureBase, List<MouvementBrut> mouvements) {
        BigDecimal ouverture = nz(affichage.convertir(nz(ouvertureBase)));
        BigDecimal solde = ouverture;
        BigDecimal entrees = BigDecimal.ZERO;
        BigDecimal sorties = BigDecimal.ZERO;
        List<JournalCompteResponse.Ligne> lignes = new ArrayList<>();
        for (MouvementBrut m : mouvements) {
            BigDecimal entree = nz(affichage.convertir(nz(m.debit())));
            BigDecimal sortie = nz(affichage.convertir(nz(m.credit())));
            if (entree.signum() == 0 && sortie.signum() == 0) {
                continue;
            }
            solde = solde.add(entree).subtract(sortie);
            entrees = entrees.add(entree);
            sorties = sorties.add(sortie);
            String note = m.note() != null ? m.note() : referenceCitee(m.libelle());
            String objet = m.objet() != null ? m.objet() : m.libelle();
            lignes.add(new JournalCompteResponse.Ligne(m.date(), note, objet, m.beneficiaire(), m.comptes(), m.piece(),
                m.annulee(), entree.signum() == 0 ? null : entree, sortie.signum() == 0 ? null : sortie, solde));
        }
        return new JournalCompteResponse(numero, libelle, du, au, affichage.devise(), affichage.taux(), ouverture,
            entrees, sorties, solde, lignes);
    }

    static String referenceCitee(String libelle) {
        if (libelle == null) {
            return null;
        }
        Matcher m = REFERENCE_NOTE.matcher(libelle);
        return m.find() ? m.group() : null;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
