package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.LigneNoteFrais;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.repository.CompteOHADARepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Comptabilisation des ecarts de change realises sur le reglement d'une note
 * de frais libellee en devise etrangere.
 *
 * <p><b>Le probleme.</b> Une note en USD n'etait convertie qu'au moment du
 * paiement, au taux de ce jour-la. Si le taux avait bouge entre l'engagement
 * de la depense et son reglement, l'ecart etait absorbe silencieusement dans
 * le compte de charge : la perte de change existait bel et bien, mais aucune
 * ligne du grand livre ne permettait de la voir, de la chiffrer, ni de la
 * suivre dans le temps. Les comptes 676 « Pertes de change » et 776 « Gains
 * de change » existaient au plan comptable sans jamais etre mouvementes.</p>
 *
 * <p><b>Le principe retenu.</b> Le taux est fige lors de la transmission de la
 * note en tresorerie ({@code notes_frais.taux_engagement}) : c'est le taux
 * auquel l'entreprise s'est engagee. Au reglement, la tresorerie sort au taux
 * du jour — c'est la sortie de caisse reelle, elle n'est pas modifiee. Une
 * piece distincte reclasse ensuite la difference : la charge est ramenee au
 * taux d'engagement, et l'ecart est porte en 676 ou 776.</p>
 *
 * <p><b>Pourquoi une piece separee.</b> Les trois canaux de reglement (caisse,
 * banque, mobile money) construisent leurs ecritures par des chemins
 * distincts. Passer par une piece de reclassement posterieure permet de
 * n'ecrire cette regle qu'une seule fois, sans toucher a une logique de
 * paiement sensible et deja eprouvee, et laisse une trace d'audit explicite
 * (une piece libellee « Ecart de change sur NF-... ») plutot qu'une ligne
 * noyee dans l'ecriture de paiement.</p>
 *
 * <p>Le service est volontairement silencieux en cas d'impossibilite : un
 * ecart non comptabilisable ne doit jamais faire echouer un paiement deja
 * valide (voir {@link #comptabiliserEcart}).</p>
 */
@Service
@RequiredArgsConstructor
public class EcartChangeService {

    private static final Logger log = LoggerFactory.getLogger(EcartChangeService.class);

    /** Comptes SYSCOHADA imputables, verifies presents et actifs au plan comptable. */
    private static final String COMPTE_PERTE_CHANGE = "676";
    private static final String COMPTE_GAIN_CHANGE = "776";

    private static final BigDecimal CENT = BigDecimal.valueOf(100);

    private final CompteOHADARepository compteRepository;
    private final ComptabiliteService comptabilite;
    /** Porte {@code compteChargesDiversesParDefaut()}, compte de repli 6588. */
    private final EcritureComptableService ecritures;
    private final ConversionDeviseService conversionDevise;
    /** Tient le niveau de stock (CMP) en phase quand l'ecart touche un compte de stock. */
    private final StockService stockService;

    /**
     * Comptabilise l'ecart de change realise sur le reglement d'une note, s'il
     * y en a un.
     *
     * <p>Sans effet — et sans erreur — lorsque la note est en devise de base,
     * qu'aucun taux d'engagement n'a ete fige (notes anterieures a cette
     * fonctionnalite), ou que le taux n'a pas bouge entre l'engagement et le
     * reglement.</p>
     *
     * @param note          note reglee, avec son taux d'engagement fige
     * @param tauxPaiement  taux effectivement applique au decaissement
     * @param dateOperation date de la piece d'ecart (celle du reglement)
     * @param auteur        utilisateur a l'origine du reglement
     * @return la piece creee, ou {@code null} si aucun ecart n'etait a constater
     */
    @Transactional
    public PieceComptable comptabiliserEcart(NoteFrais note, BigDecimal tauxPaiement,
                                              LocalDate dateOperation, User auteur) {
        BigDecimal tauxEngagement = note.getTauxEngagement();
        if (tauxEngagement == null || tauxPaiement == null) {
            return null;
        }
        if (tauxEngagement.signum() <= 0 || tauxPaiement.signum() <= 0) {
            return null;
        }
        if (tauxEngagement.compareTo(tauxPaiement) == 0) {
            return null;
        }

        // Ecart total = contre-valeur en devise de base au taux de reglement
        // moins contre-valeur au taux d'engagement. On convertit deux fois via
        // le service centralise plutot que d'ecrire "montant x delta_taux" :
        // ce raccourci n'est vrai que lorsque la devise etrangere est
        // multipliee pour rejoindre la base (ancienne base FC). Depuis le
        // passage en base USD, une note en FC est elle DIVISEE par le taux —
        // la relation entre montant et taux n'est plus lineaire, il faut donc
        // reconvertir aux deux taux et soustraire, jamais deriver une formule
        // a la main.
        // > 0 : la contre-valeur en devise de base a augmente entre
        // l'engagement et le reglement -> perte.
        BigDecimal baseEngagement = conversionDevise.enDeviseBase(note.getMontant(), note.getDevise(), tauxEngagement).montantBase();
        BigDecimal baseReglement = conversionDevise.enDeviseBase(note.getMontant(), note.getDevise(), tauxPaiement).montantBase();
        BigDecimal ecartTotal = baseReglement.subtract(baseEngagement);
        if (ecartTotal.signum() == 0) {
            return null;
        }
        boolean perte = ecartTotal.signum() > 0;
        BigDecimal ecartAbsolu = ecartTotal.abs();

        CompteOHADA compteEcart = compteRepository
            .findByNumero(perte ? COMPTE_PERTE_CHANGE : COMPTE_GAIN_CHANGE)
            .orElse(null);
        if (compteEcart == null) {
            log.warn("Ecart de change non comptabilise sur {} : compte {} absent du plan comptable",
                note.getReference(), perte ? COMPTE_PERTE_CHANGE : COMPTE_GAIN_CHANGE);
            return null;
        }

        String libelle = "Ecart de change sur " + note.getReference()
            + " (engagement " + tauxEngagement.stripTrailingZeros().toPlainString()
            + " -> reglement " + tauxPaiement.stripTrailingZeros().toPlainString() + ")";

        List<EcritureGrandLivre> lignes = new ArrayList<>();
        // Contrepartie : les comptes de charge de la note, corriges chacun au
        // prorata de son poids, afin que chaque compte revienne exactement au
        // taux d'engagement. La derniere ligne absorbe le residu d'arrondi,
        // comme le fait deja la ventilation des paiements.
        List<LigneDetail> repartition = repartir(note, ecartAbsolu);
        for (LigneDetail d : repartition) {
            lignes.add(ligne(d.compte(), perte ? BigDecimal.ZERO : d.montant(),
                perte ? d.montant() : BigDecimal.ZERO, libelle, dateOperation));
            // Le compte de stock d'une ligne "achat de marchandise" vient
            // d'etre corrige au meme titre qu'un compte de charge : le niveau
            // de stock (CMP) doit suivre exactement, sinon sa valorisation
            // diverge durablement de celle du grand livre.
            if (d.estStock() && d.ligne() != null && d.ligne().getArticle() != null && d.ligne().getEntrepot() != null) {
                BigDecimal delta = perte ? d.montant().negate() : d.montant();
                stockService.ajusterValeurStockInterne(d.ligne().getArticle(), d.ligne().getEntrepot(), delta, dateOperation);
            }
        }
        // Ligne d'ecart : debit si perte (charge constatee), credit si gain.
        lignes.add(ligne(compteEcart, perte ? ecartAbsolu : BigDecimal.ZERO,
            perte ? BigDecimal.ZERO : ecartAbsolu, libelle, dateOperation));

        try {
            PieceComptable piece = comptabilite.creerPieceInterne(
                JournalComptable.OPERATIONS_DIVERSES, libelle, dateOperation, lignes, auteur);
            log.info("Ecart de change comptabilise [note={}, sens={}, montant={} " + ConversionDeviseService.DEVISE_BASE + ", piece={}]",
                note.getReference(), perte ? "PERTE" : "GAIN", ecartAbsolu, piece.getReference());
            return piece;
        } catch (RuntimeException e) {
            // Un ecart non comptabilisable (periode close, compte de la note
            // devenu non imputable...) ne doit pas annuler un reglement deja
            // execute : on trace sans interrompre.
            log.warn("Ecart de change non comptabilise sur {} : {}", note.getReference(), e.getMessage());
            return null;
        }
    }

    /**
     * @param ligne    ligne d'origine (pour retrouver article/entrepot si estStock), {@code null} si repli générique
     * @param estStock true si ce compte est le compte de stock d'une ligne "achat de marchandise"
     */
    private record LigneDetail(CompteOHADA compte, BigDecimal montant, LigneNoteFrais ligne, boolean estStock) {}

    /**
     * Repartit l'ecart sur les comptes mouvementes par chaque ligne, au
     * prorata du montant de chaque ligne. La derniere ligne prend le residu
     * pour que la somme redonne exactement l'ecart total.
     *
     * <p>Une ligne soumise a la TVA a mouvemente DEUX comptes au paiement
     * (compte d'imputation pour le HT, compte de TVA recuperable pour la
     * TVA) : sa part d'ecart est donc elle-meme sous-ventilee au meme taux,
     * plutot que d'etre imputee en totalite au seul compte d'imputation —
     * sans quoi la TVA recuperable ne serait jamais corrigee et le compte
     * d'imputation absorberait a tort la part qui revient a l'Etat.</p>
     */
    private List<LigneDetail> repartir(NoteFrais note, BigDecimal ecartAbsolu) {
        List<LigneNoteFrais> lignesNote = note.getLignes();
        List<LigneDetail> resultat = new ArrayList<>();

        if (lignesNote == null || lignesNote.isEmpty()) {
            resultat.add(new LigneDetail(ecritures.compteChargesDiversesParDefaut(), ecartAbsolu, null, false));
            return resultat;
        }

        // Le poids de chaque ligne dans la repartition de l'ecart est son
        // montant TTC (ce qui a reellement ete decaisse pour elle), pas le
        // seul HT saisi — sans quoi une ligne soumise a la TVA peserait
        // artificiellement moins que sa part reelle du paiement.
        BigDecimal total = lignesNote.stream()
            .map(LigneNoteFrais::montantTtc)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) {
            resultat.add(new LigneDetail(ecritures.compteChargesDiversesParDefaut(), ecartAbsolu, null, false));
            return resultat;
        }

        BigDecimal cumul = BigDecimal.ZERO;
        for (int i = 0; i < lignesNote.size(); i++) {
            LigneNoteFrais l = lignesNote.get(i);
            CompteOHADA compte = l.getCompteImputation() != null
                ? l.getCompteImputation()
                : ecritures.compteChargesDiversesParDefaut();
            BigDecimal partLigne = (i == lignesNote.size() - 1)
                ? ecartAbsolu.subtract(cumul)
                : ecartAbsolu.multiply(l.montantTtc()).divide(total, 2, RoundingMode.HALF_UP);
            cumul = cumul.add(partLigne);
            if (partLigne.signum() == 0) {
                continue;
            }

            // Taux fige a la saisie de la ligne, pas celui du jour : la
            // ventilation de l'ecart doit rester coherente avec la TVA
            // effectivement comptabilisee au paiement (voir RegleTresorerieService).
            BigDecimal tauxTva = l.getTauxTvaApplique();
            if (l.isSoumisTva() && l.getCompteTva() != null && tauxTva != null && tauxTva.signum() > 0) {
                BigDecimal partTva = partLigne.multiply(tauxTva)
                    .divide(CENT.add(tauxTva), 2, RoundingMode.HALF_UP);
                BigDecimal partHt = partLigne.subtract(partTva);
                if (partHt.signum() != 0) {
                    resultat.add(new LigneDetail(compte, partHt, l, l.isAchatMarchandise()));
                }
                if (partTva.signum() != 0) {
                    resultat.add(new LigneDetail(l.getCompteTva(), partTva, l, false));
                }
            } else {
                resultat.add(new LigneDetail(compte, partLigne, l, l.isAchatMarchandise()));
            }
        }
        return resultat;
    }

    private EcritureGrandLivre ligne(CompteOHADA compte, BigDecimal debit, BigDecimal credit,
                                      String libelle, LocalDate date) {
        return EcritureGrandLivre.builder()
            .compte(compte)
            .debit(debit)
            .credit(credit)
            .libelle(libelle)
            .dateEcriture(date)
            .build();
    }
}
