package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.dto.admin.CompteResponse;
import com.mbsc.finapp.dto.comptabilite.EtatAnalytiqueResponse;
import com.mbsc.finapp.dto.comptabilite.LigneAnalytiqueResponse;
import com.mbsc.finapp.dto.comptabilite.SousCompteAnalytiqueRequest;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Comptabilité analytique de gestion (CAGE) — SYSCOHADA révisé, classe 9,
 * comptes 92 à 99 : réfléchis, reclassements, coûts, stocks, écarts sur coûts
 * préétablis, différences de traitement comptable, résultats, liaisons internes.
 *
 * <p>Le référentiel ne fixe que ces rubriques à deux chiffres ; l'entité les
 * subdivise librement (ex. 9411 Coût atelier A).</p>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ComptabiliteAnalytiqueService {

    private final CompteOHADARepository compteRepository;
    private final EcritureGrandLivreRepository ecritureRepository;

    /**
     * État des comptes analytiques sur la période : soldes antérieurs,
     * mouvements et soldes finaux. Les comptes sans mouvement sont présents ;
     * un compte de regroupement cumule les montants de ses sous-comptes.
     */
    @Transactional(readOnly = true)
    public EtatAnalytiqueResponse etat(LocalDate du, LocalDate au) {
        LocalDate debut = du != null ? du : LocalDate.of(LocalDate.now().getYear(), 1, 1);
        LocalDate fin = au != null ? au : LocalDate.now();
        if (fin.isBefore(debut)) {
            throw new IllegalArgumentException("La date de fin doit être postérieure à la date de début.");
        }
        return construireEtat(debut, fin, compteRepository.findAnalytiques(),
            parNumero(ecritureRepository.soldesAnterieursAnalytiques(debut)),
            parNumero(ecritureRepository.mouvementsAnalytiques(debut, fin)));
    }

    /** Calcul pur de l'état (testable sans base de données). */
    static EtatAnalytiqueResponse construireEtat(LocalDate du, LocalDate au, List<CompteOHADA> comptes,
                                                 Map<String, BigDecimal[]> anterieurs,
                                                 Map<String, BigDecimal[]> mouvements) {
        List<LigneAnalytiqueResponse> lignes = new ArrayList<>();
        for (CompteOHADA c : comptes) {
            String num = c.getNumero();
            BigDecimal[] ant = cumul(num, anterieurs);
            BigDecimal[] mvt = cumul(num, mouvements);
            BigDecimal net = ant[0].subtract(ant[1]).add(mvt[0]).subtract(mvt[1]);
            int niveau = (int) comptes.stream()
                .filter(p -> !p.getNumero().equals(num) && num.startsWith(p.getNumero()))
                .count();
            lignes.add(new LigneAnalytiqueResponse(
                c.getId(), num, c.getLibelle(), niveau,
                c.getParent() != null ? c.getParent().getNumero() : null,
                num.length() == 2, c.isManuel(), c.isImputable(), c.isActif(),
                ant[0], ant[1], mvt[0], mvt[1],
                net.signum() > 0 ? net : BigDecimal.ZERO,
                net.signum() < 0 ? net.negate() : BigDecimal.ZERO));
        }

        // Totaux sur les montants propres à chaque compte (pas de double comptage).
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        BigDecimal netGlobalDebiteur = BigDecimal.ZERO;
        BigDecimal netGlobalCrediteur = BigDecimal.ZERO;
        for (CompteOHADA c : comptes) {
            BigDecimal[] ant = anterieurs.getOrDefault(c.getNumero(), zero());
            BigDecimal[] mvt = mouvements.getOrDefault(c.getNumero(), zero());
            totalDebit = totalDebit.add(mvt[0]);
            totalCredit = totalCredit.add(mvt[1]);
            BigDecimal net = ant[0].subtract(ant[1]).add(mvt[0]).subtract(mvt[1]);
            if (net.signum() > 0) netGlobalDebiteur = netGlobalDebiteur.add(net);
            else netGlobalCrediteur = netGlobalCrediteur.add(net.negate());
        }
        return new EtatAnalytiqueResponse(du, au, lignes, totalDebit, totalCredit,
            netGlobalDebiteur, netGlobalCrediteur,
            totalDebit.compareTo(totalCredit) == 0 && netGlobalDebiteur.compareTo(netGlobalCrediteur) == 0);
    }

    /**
     * Crée un sous-compte analytique (numéro = parent + suffixe, ex. 94 + 11 = 9411).
     * Le parent devient un compte de regroupement s'il n'a encore aucune écriture.
     */
    public CompteResponse creerSousCompte(SousCompteAnalytiqueRequest req) {
        String parentNumero = req.parentNumero().trim();
        if (!parentNumero.matches("9[2-9]\\d*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Seuls les comptes analytiques 92 à 99 peuvent être subdivisés ici.");
        }
        CompteOHADA parent = compteRepository.findByNumero(parentNumero)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Compte parent introuvable : " + parentNumero));

        String numero = parentNumero + req.suffixe().trim();
        if (numero.length() > 20) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Numéro de compte trop long (20 caractères maximum).");
        }
        if (compteRepository.existsByNumero(numero)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Numéro de compte déjà existant : " + numero);
        }

        CompteOHADA compte = CompteOHADA.builder()
            .numero(numero)
            .libelle(req.libelle().trim())
            .type(null)
            .classe(9)
            .parent(parent)
            .manuel(true)
            .imputable(true)
            .actif(true)
            .build();
        CompteOHADA saved = compteRepository.save(compte);

        // Règle du plan (V23) : un compte subdivisé n'est plus imputable, sauf s'il porte déjà des écritures.
        if (parent.isImputable() && !ecritureRepository.existsByCompteId(parent.getId())) {
            parent.setImputable(false);
        }
        return CompteResponse.from(saved);
    }

    private static BigDecimal[] cumul(String prefixe, Map<String, BigDecimal[]> montants) {
        BigDecimal d = BigDecimal.ZERO;
        BigDecimal c = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal[]> e : montants.entrySet()) {
            if (e.getKey().startsWith(prefixe)) {
                d = d.add(e.getValue()[0]);
                c = c.add(e.getValue()[1]);
            }
        }
        return new BigDecimal[] {d, c};
    }

    private static Map<String, BigDecimal[]> parNumero(List<Object[]> rows) {
        Map<String, BigDecimal[]> map = new HashMap<>();
        for (Object[] r : rows) {
            map.put((String) r[0], new BigDecimal[] {montant(r[1]), montant(r[2])});
        }
        return map;
    }

    private static BigDecimal montant(Object o) {
        if (o == null) return BigDecimal.ZERO;
        return o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }

    private static BigDecimal[] zero() {
        return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO};
    }
}
