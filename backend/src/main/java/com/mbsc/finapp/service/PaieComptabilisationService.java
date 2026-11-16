package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.BulletinPaie;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.Employe;
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
 *       6631 (logement) et 6634 (transport) ; charges sociales patronales (CNSS) 6641/6642.</li>
 *   <li>Débit — impôts et taxes : 6413 « Taxes sur appointements et salaires » pour l'ONEM et
 *       l'INPP, qui sont des taxes assises sur les salaires et non des cotisations sociales.</li>
 *   <li>Crédit — dettes : CNSS par branche (D-08) — 4313 « Caisse de retraite obligatoire » (part salariale et
 *       part patronale pensions), 4311 « Prestations familiales », 4312 « Accidents du travail » (risques
 *       professionnels) ; un bulletin antérieur sans parts par branche reste en 4315 pour le total ; 4472 (IPR
 *       retenu) ; 44281 (ONEM) et 44282 (INPP), sous 4428 « Autres impôts et taxes » (D-07 : 44781/44782 restent
 *       pour l'historique) ; avance retenue au compte d'avances de l'agent (4211x, à défaut 4211, D-06) ; prêt au
 *       compte 272… de l'agent (à défaut 2728) ; net à payer au compte de rémunérations dues de l'agent (422111,
 *       422112…, à défaut le collectif 42211), soldé par le paiement de la note de paie.</li>
 * </ul>
 *
 * <p>Audit DRH du 15/11/2026 : net à payer par agent (D-02), prêt au compte 272… et non plus 2762 « intérêts
 * courus » (D-03), transport en 6634 « Indemnités de transport » et non plus 6638 (D-04).</p>
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
    public static final String COMPTE_NET_A_PAYER = "42211";
    /** Prêts au personnel, quand la fiche de l'agent ne désigne pas son propre compte 272…. */
    public static final String COMPTE_PRET_PAR_DEFAUT = "2728";
    public static final String COMPTE_TRANSPORT = "6634";
    /** Personnel, avances : collectif des comptes d'avances par agent (42111, 42112…). */
    public static final String COMPTE_AVANCES = "4211";
    /** CNSS par branche (audit DRH du 15/11/2026, D-08). */
    public static final String COMPTE_CNSS_PF = "4311";
    public static final String COMPTE_CNSS_RP = "4312";
    public static final String COMPTE_CNSS_RETRAITE = "4313";
    /** CNSS totale des bulletins antérieurs à la répartition par branche. */
    public static final String COMPTE_CNSS_HISTORIQUE = "4315";
    /** ONEM et INPP sous 4428 « Autres impôts et taxes » (D-07). */
    public static final String COMPTE_ONEM = "44281";
    public static final String COMPTE_INPP = "44282";
    public static final String COMPTE_IPR = "4472";

    /** Vrai pour le collectif 42211 et ses sous-comptes par agent (422111, 422112…). */
    public static boolean estCompteNetAPayer(String numero) {
        return numero != null && numero.startsWith(COMPTE_NET_A_PAYER);
    }

    /** Compte crédité du net à payer de l'agent : son compte propre, à défaut le collectif 42211. */
    public static String compteNetAPayer(Employe e) {
        return e != null && e.getCompteRemuneration() != null && !e.getCompteRemuneration().isBlank()
            ? e.getCompteRemuneration() : COMPTE_NET_A_PAYER;
    }

    /** Compte crédité de la retenue de prêt : le compte 272… de l'agent, à défaut 2728. */
    public static String comptePret(Employe e) {
        return e != null && e.getComptePret() != null && !e.getComptePret().isBlank()
            ? e.getComptePret() : COMPTE_PRET_PAR_DEFAUT;
    }

    /** Compte crédité de la retenue d'avance : le compte d'avances de l'agent (4211x), à défaut 4211 (D-06). */
    public static String compteAvance(Employe e) {
        return e != null && e.getCompteAvance() != null && !e.getCompteAvance().isBlank()
            ? e.getCompteAvance() : COMPTE_AVANCES;
    }

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

    /**
     * Crédits que la constatation d'un bulletin porte à chaque compte : {compte -> montant}. Sert de repli aux notes
     * fiscales quand aucune pièce de constatation n'est trouvée (voir {@code PaieNoteService.creerNoteImpot}).
     */
    public static Map<String, BigDecimal> credits(BulletinPaie b) {
        Map<String, BigDecimal> credits = new LinkedHashMap<>();
        montants(b).forEach((compte, dc) -> {
            if (dc[1].signum() > 0) credits.put(compte, dc[1]);
        });
        return credits;
    }

    /**
     * Appointements (débit 6611/6621) : salaire brut − logement − transport. Depuis le 16/11/2026, la base cotisable
     * ({@code baseImposableInss}) comprend aussi les gains imposables : elle ne peut plus servir ici. Un bulletin
     * sans salaire brut (données anciennes) retombe sur la base cotisable, qui valait alors les appointements.
     */
    static BigDecimal appointements(BulletinPaie b) {
        if (b.getSalaireBrut() == null) {
            return nz(b.getBaseImposableInss());
        }
        return b.getSalaireBrut().subtract(nz(b.getIndemniteLogement())).subtract(nz(b.getIndemniteTransport()));
    }

    /** Débit/crédit par compte pour un bulletin : {compte -> [débit, crédit]}, dans l'ordre d'écriture. */
    private static Map<String, BigDecimal[]> montants(BulletinPaie b) {
        boolean expatrie = b.getEmploye().isExpatrie();
        Map<String, BigDecimal[]> m = new LinkedHashMap<>();

        // --- Débit : charges -------------------------------------------
        debit(m, expatrie ? "6621" : "6611", appointements(b));
        debit(m, expatrie ? "6622" : "6612",
            nz(b.getPrimeDiplome()).add(nz(b.getPrimeAnciennete())).add(nz(b.getPrimeRendement())));
        debit(m, expatrie ? "6623" : "6613", b.getConge());
        debit(m, expatrie ? "6626" : "6616", b.getAllocationFamiliale());
        debit(m, expatrie ? "6628" : "6618", b.getHeuresSupplementaires());
        debit(m, "6631", b.getIndemniteLogement());
        debit(m, COMPTE_TRANSPORT, b.getIndemniteTransport());
        debit(m, expatrie ? "6642" : "6641", b.getCnssPatronale());
        debit(m, "6413", nz(b.getOnem()).add(nz(b.getInpp())));

        // --- Crédit : dettes ---------------------------------------------
        if (b.getCnssPatronalePension() == null && b.getCnssPatronalePf() == null && b.getCnssPatronaleRp() == null) {
            // Bulletin antérieur à la répartition par branche : total CNSS en 4315, comme avant.
            credit(m, COMPTE_CNSS_HISTORIQUE, nz(b.getCnssOuvriere()).add(nz(b.getCnssPatronale())));
        } else {
            credit(m, COMPTE_CNSS_RETRAITE, nz(b.getCnssOuvriere()).add(nz(b.getCnssPatronalePension())));
            credit(m, COMPTE_CNSS_PF, b.getCnssPatronalePf());
            credit(m, COMPTE_CNSS_RP, b.getCnssPatronaleRp());
        }
        credit(m, COMPTE_IPR, b.getIpr());
        credit(m, COMPTE_ONEM, b.getOnem());
        credit(m, COMPTE_INPP, b.getInpp());
        credit(m, compteAvance(b.getEmploye()), b.getAvanceSalaire());
        credit(m, comptePret(b.getEmploye()), b.getPret());
        credit(m, compteNetAPayer(b.getEmploye()), b.getSalaireNet());
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
                "Compte OHADA " + numero + " introuvable : il est créé par les migrations DRH (V43, V46, V103, "
                + "V146, V147) ou à la création de la fiche de l'agent — vérifiez le plan comptable."));
    }

    private static String periode(BulletinPaie b) {
        return String.format("%02d/%d", b.getMois(), b.getAnnee());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
