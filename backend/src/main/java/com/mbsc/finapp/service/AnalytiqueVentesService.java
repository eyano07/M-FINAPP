package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.LigneMouvementStock;
import com.mbsc.finapp.domain.LigneVente;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.Vente;
import com.mbsc.finapp.domain.enums.ActiviteAnalytique;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.StatutPiece;
import com.mbsc.finapp.domain.enums.StatutVente;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.PieceComptableRepository;
import com.mbsc.finapp.repository.VenteRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Comptabilité analytique alimentée par les ventes (classe 9, migration V129) : chaque vente validée est reflétée, par
 * activité ({@link ActiviteAnalytique}), dans une pièce du journal ANALYTIQUE :
 * <pre>
 *   D 927  Produits réfléchis         / C 98x1 Chiffre d'affaires HT de l'activité
 *   D 98x2 Coût des ventes de l'activité / C 926 Charges réfléchies
 * </pre>
 * Le solde du compte 98x (regroupement) est la marge de l'activité. Le chiffre d'affaires reprend exactement les crédits
 * de produit de la pièce de vente, le coût la sortie de stock de la vente (voir {@link AnalytiqueVentesCalcul}).
 *
 * <p><b>Ne bloque jamais une vente.</b> Comptes manquants, désactivés ou devenus regroupements, période clôturée : tout
 * est vérifié AVANT d'écrire, la vente reste alors sans reflet (journalisé) et la reprise au démarrage de l'application
 * la rattrape ({@link #reprendreVentesSansReflet}) — elle reprend aussi les ventes validées avant cette fonctionnalité.
 * Une vente annulée voit son reflet contre-passé, comme sa pièce de vente.</p>
 */
@Service
@RequiredArgsConstructor
public class AnalytiqueVentesService {

    private static final Logger log = LoggerFactory.getLogger(AnalytiqueVentesService.class);

    /** Contrepartie du coût des ventes reflété. */
    static final String COMPTE_CHARGES_REFLECHIES = "926";
    /** Contrepartie du chiffre d'affaires reflété. */
    static final String COMPTE_PRODUITS_REFLECHIS = "927";

    private final CompteOHADARepository compteRepository;
    private final ComptabiliteService comptabilite;
    private final PeriodeComptableService periodeService;
    private final VenteRepository venteRepository;
    private final PieceComptableRepository pieceRepository;
    private final PlatformTransactionManager transactionManager;

    /**
     * Reflète une vente qu'on vient de valider (sa pièce de vente et sa sortie de stock existent déjà). N'écrit rien et
     * ne lève rien si le reflet est impossible : la vente se valide quand même.
     */
    @Transactional
    public void refleterInterne(Vente vente, User auteur) {
        try {
            List<EcritureGrandLivre> lignesVente = vente.getPiece() == null ? List.of() : vente.getPiece().getLignes();
            List<LigneMouvementStock> sorties = vente.getMouvement() == null ? List.of() : vente.getMouvement().getLignes();
            PieceComptable reflet = creerReflet(vente, lignesVente, sorties, auteur);
            if (reflet != null) {
                vente.setPieceAnalytique(reflet);
            }
        } catch (RuntimeException e) {
            // Filet de securite : la vente passe avant son reflet, que la reprise au demarrage rattrapera.
            log.warn("Reflet analytique de la vente {} reporte a la prochaine reprise : {}", vente.getReference(), e.getMessage());
        }
    }

    /** Contre-passe le reflet d'une vente qu'on annule (extourne, comme sa pièce de vente) ; sans effet si elle n'en a pas. */
    @Transactional
    public void annulerRefletInterne(Vente vente, User auteur) {
        PieceComptable reflet = vente.getPieceAnalytique();
        if (reflet == null) {
            return;
        }
        if (reflet.getStatut() != StatutPiece.COMPTABILISEE) {
            log.warn("Reflet analytique {} de la vente {} déjà {} : rien à contre-passer.",
                reflet.getReference(), vente.getReference(), reflet.getStatut());
            return;
        }
        comptabilite.annulerInterne(reflet.getId(), auteur);
    }

    /**
     * Reprise : reflète chaque vente validée qui ne l'est pas encore (ventes antérieures à ce reflet, ou restées sans
     * reflet), à sa propre date. Une transaction par vente : un échec est journalisé et n'empêche pas les autres. Une vente
     * annulée n'est pas reprise (sa pièce et son extourne s'annulent déjà).
     *
     * @return le nombre de ventes reflétées
     */
    public int reprendreVentesSansReflet() {
        List<Long> ids = venteRepository.idsValideesSansRefletAnalytique();
        if (ids.isEmpty()) {
            return 0;
        }
        TransactionTemplate parVente = new TransactionTemplate(transactionManager);
        parVente.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int refletees = 0;
        for (Long id : ids) {
            try {
                if (Boolean.TRUE.equals(parVente.execute(statut -> refleterVenteExistante(id)))) {
                    refletees++;
                }
            } catch (RuntimeException e) {
                log.warn("Reprise analytique : la vente {} n'a pas pu être reflétée ({}).", id, e.getMessage());
            }
        }
        log.info("Reprise analytique des ventes : {} vente(s) reflétée(s) sur {} sans reflet.", refletees, ids.size());
        return refletees;
    }

    private boolean refleterVenteExistante(Long id) {
        Vente vente = venteRepository.findWithLignesById(id).orElse(null);
        if (vente == null || vente.getStatut() != StatutVente.VALIDEE || vente.getPiece() == null
                || vente.getPieceAnalytique() != null) {
            return false;
        }
        PieceComptable pieceVente = pieceRepository.findWithLignesById(vente.getPiece().getId()).orElse(null);
        if (pieceVente == null || pieceVente.getStatut() != StatutPiece.COMPTABILISEE) {
            return false;
        }
        List<LigneMouvementStock> sorties = vente.getMouvement() == null ? List.of() : vente.getMouvement().getLignes();
        User auteur = pieceVente.getCreatedBy() != null ? pieceVente.getCreatedBy() : vente.getCreatedBy();
        PieceComptable reflet = creerReflet(vente, pieceVente.getLignes(), sorties, auteur);
        if (reflet == null) {
            return false;
        }
        if (venteRepository.lierPieceAnalytique(vente.getId(), reflet) != 1) {
            // Une autre opération l'a reflétée entre-temps : on annule cette transaction plutôt que de garder un doublon.
            throw new IllegalStateException("la vente " + vente.getReference() + " a été reflétée entre-temps");
        }
        return true;
    }

    /**
     * Pièce analytique d'une vente, ou {@code null} si elle n'a rien à refléter ou ne peut pas l'être (journalisé). Tout
     * est vérifié avant d'appeler {@link ComptabiliteService#creerPieceInterne} : une exception là-bas marquerait la
     * transaction de la vente pour annulation.
     */
    PieceComptable creerReflet(Vente vente, List<EcritureGrandLivre> lignesVente, List<LigneMouvementStock> sorties,
                               User auteur) {
        List<AnalytiqueVentesCalcul.LigneVue> lignes = new ArrayList<>();
        for (LigneVente l : vente.getLignes()) {
            Article article = l.getArticle();
            if (article != null && article.getCompteProduit() != null) {
                lignes.add(new AnalytiqueVentesCalcul.LigneVue(ActiviteAnalytique.de(article),
                    article.getCompteProduit().getId(), l.getMontantHt()));
            }
        }
        Set<Long> comptesProduits = new LinkedHashSet<>();
        lignes.forEach(l -> comptesProduits.add(l.compteProduitId()));
        List<AnalytiqueVentesCalcul.CreditProduit> credits = lignesVente.stream()
            .filter(e -> e.getCompte() != null && comptesProduits.contains(e.getCompte().getId())
                && e.getCredit() != null && e.getCredit().signum() > 0)
            .map(e -> new AnalytiqueVentesCalcul.CreditProduit(e.getCompte().getId(), e.getCredit()))
            .toList();
        List<AnalytiqueVentesCalcul.Cout> couts = sorties.stream()
            .filter(s -> s.getArticle() != null && s.getMontant() != null)
            .map(s -> new AnalytiqueVentesCalcul.Cout(ActiviteAnalytique.de(s.getArticle()), s.getMontant()))
            .toList();
        AnalytiqueVentesCalcul.Reflet reflet = AnalytiqueVentesCalcul.calculer(lignes, credits, couts);
        if (reflet.estVide()) {
            return null;
        }

        LocalDate date = vente.getDateVente();
        LocalDate cloture = periodeService.dateCloture();
        if (cloture != null && !date.isAfter(cloture)) {
            log.warn("Reflet analytique de la vente {} impossible : sa date ({}) est dans une période clôturée (au {}).",
                vente.getReference(), date, cloture);
            return null;
        }

        Map<String, CompteOHADA> comptes = new LinkedHashMap<>();
        List<String> numeros = new ArrayList<>();
        if (!reflet.chiffreAffaires().isEmpty()) {
            numeros.add(COMPTE_PRODUITS_REFLECHIS);
            reflet.chiffreAffaires().keySet().forEach(a -> numeros.add(a.compteChiffreAffaires()));
        }
        if (!reflet.couts().isEmpty()) {
            numeros.add(COMPTE_CHARGES_REFLECHIES);
            reflet.couts().keySet().forEach(a -> numeros.add(a.compteCout()));
        }
        for (String numero : numeros) {
            CompteOHADA compte = compteRepository.findByNumero(numero).orElse(null);
            if (compte == null || !compte.isActif() || !compte.isImputable()) {
                log.warn("Reflet analytique de la vente {} impossible : le compte {} est {}.", vente.getReference(), numero,
                    compte == null ? "absent du plan" : !compte.isActif() ? "désactivé" : "un compte de regroupement");
                return null;
            }
            comptes.put(numero, compte);
        }

        String ref = vente.getReference();
        BigDecimal taux = vente.getTauxJournalier();
        List<EcritureGrandLivre> ecritures = new ArrayList<>();
        if (!reflet.chiffreAffaires().isEmpty()) {
            ecritures.add(ecriture(comptes.get(COMPTE_PRODUITS_REFLECHIS), reflet.totalChiffreAffaires(), BigDecimal.ZERO,
                "Produits réfléchis — vente " + ref, taux));
            reflet.chiffreAffaires().forEach((a, m) -> ecritures.add(ecriture(comptes.get(a.compteChiffreAffaires()),
                BigDecimal.ZERO, m, "Chiffre d'affaires HT " + a.libelle() + " — vente " + ref, taux)));
        }
        reflet.couts().forEach((a, m) -> ecritures.add(ecriture(comptes.get(a.compteCout()), m, BigDecimal.ZERO,
            "Coût des ventes " + a.libelle() + " — vente " + ref, taux)));
        if (!reflet.couts().isEmpty()) {
            ecritures.add(ecriture(comptes.get(COMPTE_CHARGES_REFLECHIES), BigDecimal.ZERO, reflet.totalCouts(),
                "Charges réfléchies — vente " + ref, taux));
        }
        PieceComptable piece = comptabilite.creerPieceInterne(JournalComptable.ANALYTIQUE, "Analytique — vente " + ref, date,
            ecritures, auteur);
        log.info("Vente {} reflétée en analytique [pièce={}, CA={}, coût={}]", ref, piece.getReference(),
            reflet.totalChiffreAffaires(), reflet.totalCouts());
        return piece;
    }

    /** Écriture en devise de base, au taux de la vente (pour la réafficher en francs au taux de l'opération). */
    private static EcritureGrandLivre ecriture(CompteOHADA compte, BigDecimal debit, BigDecimal credit, String libelle,
                                               BigDecimal taux) {
        return EcritureGrandLivre.builder()
            .compte(compte)
            .debit(debit)
            .credit(credit)
            .libelle(libelle)
            .tauxApplique(taux)
            .build();
    }
}
