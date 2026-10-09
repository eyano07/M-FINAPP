package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.BulletinPaie;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.repository.CompteOHADARepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Écritures de constatation de la paie (SYSCOHADA révisé), écrites au paiement de la note de paie du
 * mois — voir {@code PaieNoteService.apresPaiementInterne}.
 *
 * <p>Mapping des comptes :</p>
 * <ul>
 *   <li>Débit — charges de personnel : 661x pour le personnel national, 662x pour le personnel non
 *       national (expatrié) : appointements 6611/6621, primes 6612/6622, congés payés 6613/6623,
 *       supplément familial 6616/6626, heures supplémentaires 6618/6628 ; indemnités forfaitaires
 *       6631 (logement) et 6638 (transport) ; charges sociales patronales (CNSS) 6641/6642.</li>
 *   <li>Débit — impôts et taxes : 6413 « Taxes sur appointements et salaires » pour l'ONEM et
 *       l'INPP, qui sont des taxes assises sur les salaires et non des cotisations sociales.</li>
 *   <li>Crédit — dettes : 431.1 (CNSS salariale + patronale), 4472 (IPR retenu), 4478.1 (ONEM),
 *       4478.2 (INPP), 4211 (avance sur salaire déjà décaissée), 2762 (prêt), 4221.1 (net à payer,
 *       soldé par le paiement de la note de paie).</li>
 * </ul>
 *
 * <p>L'équilibre découle de la formule de calcul : en substituant {@code net} et la base cotisable
 * dans le total crédit, chaque retenue (CNSS salariale, IPR, avance, prêt) s'annule contre sa propre
 * ligne de crédit ; il reste des deux côtés la rémunération brute et les charges patronales.
 * {@code ComptabiliteService.creerPieceInterne} le revérifie de toute façon avant d'écrire.</p>
 */
@Service
@RequiredArgsConstructor
public class PaieComptabilisationService {

    /** Les montants de paie sont calculés en dollars, devise de base de la comptabilité. */
    private static final String DEVISE = ConversionDeviseService.DEVISE_BASE.name();
    public static final String COMPTE_NET_A_PAYER = "4221.1";

    private final CompteOHADARepository compteRepository;

    /** Écritures d'un seul bulletin (une ligne par compte mouvementé). */
    public List<EcritureGrandLivre> construireLignes(BulletinPaie b) {
        String libelle = "Paie " + periode(b) + " — " + b.getEmploye().getNomComplet();
        return enLignes(montants(b), libelle);
    }

    /**
     * Écritures groupées d'un mois : les montants de tous les bulletins sont cumulés par compte et par
     * sens, pour une seule pièce de constatation de la paie du mois.
     */
    public List<EcritureGrandLivre> construireLignesGroupees(List<BulletinPaie> bulletins, String libelle) {
        Map<String, BigDecimal[]> cumul = new LinkedHashMap<>();
        for (BulletinPaie b : bulletins) {
            montants(b).forEach((compte, m) -> cumul.merge(compte, m,
                (a, c) -> new BigDecimal[] {a[0].add(c[0]), a[1].add(c[1])}));
        }
        return enLignes(cumul, libelle);
    }

    /** Débit/crédit par compte pour un bulletin : {compte -> [débit, crédit]}, dans l'ordre d'écriture. */
    private Map<String, BigDecimal[]> montants(BulletinPaie b) {
        boolean expatrie = b.getEmploye().isExpatrie();
        Map<String, BigDecimal[]> m = new LinkedHashMap<>();

        // --- Débit : charges -------------------------------------------
        debit(m, expatrie ? "6621" : "6611", b.getBaseImposableInss());
        debit(m, expatrie ? "6622" : "6612",
            nz(b.getPrimeDiplome()).add(nz(b.getPrimeAnciennete())).add(nz(b.getPrimeRendement())));
        debit(m, expatrie ? "6623" : "6613", b.getConge());
        debit(m, expatrie ? "6626" : "6616", b.getAllocationFamiliale());
        debit(m, expatrie ? "6628" : "6618", b.getHeuresSupplementaires());
        debit(m, "6631", b.getIndemniteLogement());
        debit(m, "6638", b.getIndemniteTransport());
        debit(m, expatrie ? "6642" : "6641", b.getCnssPatronale());
        debit(m, "6413", nz(b.getOnem()).add(nz(b.getInpp())));

        // --- Crédit : dettes ---------------------------------------------
        credit(m, "431.1", nz(b.getCnssOuvriere()).add(nz(b.getCnssPatronale())));
        credit(m, "4472", b.getIpr());
        credit(m, "4478.1", b.getOnem());
        credit(m, "4478.2", b.getInpp());
        credit(m, "4211", b.getAvanceSalaire());
        credit(m, "2762", b.getPret());
        credit(m, COMPTE_NET_A_PAYER, b.getSalaireNet());
        return m;
    }

    private List<EcritureGrandLivre> enLignes(Map<String, BigDecimal[]> montants, String libelle) {
        List<EcritureGrandLivre> lignes = new ArrayList<>();
        montants.forEach((numero, dc) -> {
            if (dc[0].signum() > 0) lignes.add(ligne(numero, dc[0], BigDecimal.ZERO, libelle));
            if (dc[1].signum() > 0) lignes.add(ligne(numero, BigDecimal.ZERO, dc[1], libelle));
        });
        return lignes;
    }

    private EcritureGrandLivre ligne(String numero, BigDecimal debit, BigDecimal credit, String libelle) {
        return EcritureGrandLivre.builder()
            .compte(compte(numero))
            .debit(debit)
            .credit(credit)
            .libelle(libelle)
            .devise(DEVISE)
            .build();
    }

    private static void debit(Map<String, BigDecimal[]> m, String compte, BigDecimal montant) {
        ajouter(m, compte, montant, 0);
    }

    private static void credit(Map<String, BigDecimal[]> m, String compte, BigDecimal montant) {
        ajouter(m, compte, montant, 1);
    }

    private static void ajouter(Map<String, BigDecimal[]> m, String compte, BigDecimal montant, int sens) {
        if (montant == null || montant.signum() <= 0) {
            return;
        }
        BigDecimal[] dc = m.computeIfAbsent(compte, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
        dc[sens] = dc[sens].add(montant);
    }

    private CompteOHADA compte(String numero) {
        return compteRepository.findByNumero(numero)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Compte OHADA " + numero + " introuvable : il est créé par les migrations DRH (V43, V46, V103) "
                + "— vérifiez le plan comptable."));
    }

    private static String periode(BulletinPaie b) {
        return String.format("%02d/%d", b.getMois(), b.getAnnee());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
