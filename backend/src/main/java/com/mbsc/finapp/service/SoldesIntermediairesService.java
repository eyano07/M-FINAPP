package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.dto.comptabilite.SoldesIntermediairesResponse;
import com.mbsc.finapp.dto.comptabilite.SoldesIntermediairesResponse.Solde;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Soldes intermediaires de gestion (SIG) du SYSCOHADA revise.
 *
 * <p>Le compte de resultat existant restitue un couple produits/charges : utile
 * mais muet sur la <i>formation</i> du resultat. Les SIG decomposent cette
 * formation en etages normalises — marge commerciale, valeur ajoutee, EBE,
 * resultat d'exploitation, financier, H.A.O. — qui sont la lecture attendue
 * d'une entreprise OHADA et la base de tout ratio de gestion.</p>
 *
 * <p><b>Methode.</b> Les soldes sont agreges par prefixe de numero de compte,
 * et le sens est pris sur le TYPE du compte (produit = credit - debit, charge
 * = debit - credit) plutot que devine depuis la classe : la classe 8 porte les
 * deux, et un compte peut se retrouver en sens inverse apres extourne. Les
 * pieces marquees « solde d'ouverture » sont exclues par la requete
 * sous-jacente ({@code mouvementsParCompte}) : une reprise d'a-nouveaux n'est
 * pas un flux de la periode.</p>
 *
 * <p><b>Perimetre.</b> Un compte non mouvemente sur la periode n'apparait pas
 * dans l'agregat et vaut donc zero — aucune ligne n'est perdue, seuls les
 * etages sans activite ressortent a zero.</p>
 */
@Service
@RequiredArgsConstructor
public class SoldesIntermediairesService {

    private final EcritureGrandLivreRepository ecritureRepository;

    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public SoldesIntermediairesResponse calculer(LocalDate du, LocalDate au) {
        LocalDate debut = du != null ? du : LocalDate.of(LocalDate.now().getYear(), 1, 1);
        LocalDate fin = au != null ? au : LocalDate.now();

        // Solde signe par numero de compte : positif = sens naturel du compte
        // (produit crediteur, charge debitrice).
        Map<String, BigDecimal> parCompte = new LinkedHashMap<>();
        for (Object[] row : ecritureRepository.mouvementsParCompte(debut, fin)) {
            String numero = (String) row[0];
            TypeCompte type = (TypeCompte) row[2];
            BigDecimal debit = nz((BigDecimal) row[4]);
            BigDecimal credit = nz((BigDecimal) row[5]);
            if (type == null) {
                continue;
            }
            BigDecimal solde = switch (type) {
                case PRODUIT -> credit.subtract(debit);
                case CHARGE -> debit.subtract(credit);
                // Actif et passif n'entrent dans aucun SIG : ils ne mesurent
                // pas la formation du resultat.
                case ACTIF, PASSIF -> BigDecimal.ZERO;
            };
            if (solde.signum() != 0) {
                parCompte.merge(numero, solde, BigDecimal::add);
            }
        }

        // --- Etage 1 : marge commerciale (activite de negoce) --------------
        BigDecimal ventesMarchandises = somme(parCompte, "701");
        BigDecimal achatsMarchandises = somme(parCompte, "601");
        BigDecimal variationStocksMarchandises = somme(parCompte, "6031");
        BigDecimal margeCommerciale = ventesMarchandises
            .subtract(achatsMarchandises)
            .subtract(variationStocksMarchandises);

        // --- Etage 2 : production de l'exercice ---------------------------
        BigDecimal ventesProduits = somme(parCompte, "702", "703", "704", "705", "706", "707");
        BigDecimal productionImmobilisee = somme(parCompte, "72");
        BigDecimal variationStocksProduits = somme(parCompte, "73");
        BigDecimal production = ventesProduits.add(productionImmobilisee).add(variationStocksProduits);

        BigDecimal chiffreAffaires = somme(parCompte, "70");

        // --- Etage 3 : valeur ajoutee -------------------------------------
        // Consommations intermediaires : achats hors marchandises, leurs
        // variations de stock, transports et services exterieurs.
        BigDecimal autresAchats = somme(parCompte, "602", "604", "605", "608")
            .add(somme(parCompte, "6032", "6033", "6038"));
        BigDecimal transports = somme(parCompte, "61");
        BigDecimal servicesExterieurs = somme(parCompte, "62", "63");
        BigDecimal consommationsIntermediaires = autresAchats.add(transports).add(servicesExterieurs);
        BigDecimal valeurAjoutee = margeCommerciale.add(production).subtract(consommationsIntermediaires);

        // --- Etage 4 : excedent brut d'exploitation -----------------------
        BigDecimal subventions = somme(parCompte, "71");
        BigDecimal impotsEtTaxes = somme(parCompte, "64");
        BigDecimal chargesPersonnel = somme(parCompte, "66");
        BigDecimal ebe = valeurAjoutee.add(subventions).subtract(impotsEtTaxes).subtract(chargesPersonnel);

        // --- Etage 5 : resultat d'exploitation ----------------------------
        BigDecimal autresProduits = somme(parCompte, "75");
        BigDecimal transfertsCharges = somme(parCompte, "78");
        BigDecimal reprises = somme(parCompte, "79");
        BigDecimal autresCharges = somme(parCompte, "65");
        BigDecimal dotations = somme(parCompte, "68", "69");
        BigDecimal resultatExploitation = ebe
            .add(autresProduits).add(transfertsCharges).add(reprises)
            .subtract(autresCharges).subtract(dotations);

        // --- Etage 6 : resultat financier ---------------------------------
        BigDecimal revenusFinanciers = somme(parCompte, "77");
        BigDecimal fraisFinanciers = somme(parCompte, "67");
        BigDecimal resultatFinancier = revenusFinanciers.subtract(fraisFinanciers);

        BigDecimal resultatActivitesOrdinaires = resultatExploitation.add(resultatFinancier);

        // --- Etage 7 : resultat H.A.O. ------------------------------------
        BigDecimal produitsHao = somme(parCompte, "82", "84", "86", "88");
        BigDecimal chargesHao = somme(parCompte, "81", "83", "85");
        BigDecimal resultatHao = produitsHao.subtract(chargesHao);

        // --- Etage 8 : resultat net ---------------------------------------
        BigDecimal participation = somme(parCompte, "87");
        BigDecimal impotResultat = somme(parCompte, "89");
        BigDecimal resultatNet = resultatActivitesOrdinaires.add(resultatHao)
            .subtract(participation).subtract(impotResultat);

        List<Solde> soldes = new ArrayList<>();
        soldes.add(new Solde("ca", "Chiffre d'affaires", "Comptes 70", chiffreAffaires, false));
        soldes.add(new Solde("marge_commerciale", "Marge commerciale",
            "701 − 601 − 6031", margeCommerciale, false));
        soldes.add(new Solde("production", "Production de l'exercice",
            "702 à 707 + 72 + 73", production, false));
        soldes.add(new Solde("consommations", "Consommations intermédiaires",
            "602, 604, 605, 608, 6032-6038 + 61 + 62 + 63", consommationsIntermediaires, false));
        soldes.add(new Solde("valeur_ajoutee", "Valeur ajoutée",
            "Marge commerciale + Production − Consommations intermédiaires", valeurAjoutee, true));
        soldes.add(new Solde("charges_personnel", "Charges de personnel", "Comptes 66", chargesPersonnel, false));
        soldes.add(new Solde("impots_taxes", "Impôts et taxes", "Comptes 64", impotsEtTaxes, false));
        soldes.add(new Solde("ebe", "Excédent brut d'exploitation",
            "Valeur ajoutée + 71 − 64 − 66", ebe, true));
        soldes.add(new Solde("dotations", "Dotations aux amortissements et provisions",
            "Comptes 68 et 69", dotations, false));
        soldes.add(new Solde("resultat_exploitation", "Résultat d'exploitation",
            "EBE + 75 + 78 + 79 − 65 − 68 − 69", resultatExploitation, true));
        soldes.add(new Solde("resultat_financier", "Résultat financier",
            "77 − 67", resultatFinancier, false));
        soldes.add(new Solde("rao", "Résultat des activités ordinaires",
            "Résultat d'exploitation + Résultat financier", resultatActivitesOrdinaires, true));
        soldes.add(new Solde("resultat_hao", "Résultat hors activités ordinaires",
            "(82, 84, 86, 88) − (81, 83, 85)", resultatHao, false));
        soldes.add(new Solde("participation", "Participation des travailleurs", "Comptes 87", participation, false));
        soldes.add(new Solde("impot_resultat", "Impôts sur le résultat", "Comptes 89", impotResultat, false));
        soldes.add(new Solde("resultat_net", "Résultat net de l'exercice",
            "R.A.O. + Résultat H.A.O. − 87 − 89", resultatNet, true));

        return new SoldesIntermediairesResponse(debut, fin, soldes);
    }

    /**
     * Somme des soldes des comptes dont le numero commence par l'un des
     * prefixes. Le prefixe est compare sur le numero brut : le plan comptable
     * de cette application utilise des sous-comptes pointes (431.1, 4478.2),
     * qu'un prefixe « 43 » doit bien capturer.
     */
    private BigDecimal somme(Map<String, BigDecimal> parCompte, String... prefixes) {
        BigDecimal total = BigDecimal.ZERO;
        for (var entree : parCompte.entrySet()) {
            for (String prefixe : prefixes) {
                if (entree.getKey().startsWith(prefixe)) {
                    total = total.add(entree.getValue());
                    break;   // un compte ne doit etre compte qu'une fois par appel
                }
            }
        }
        return total;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
