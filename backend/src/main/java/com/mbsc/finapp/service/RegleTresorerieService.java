package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.LigneNoteFrais;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.enums.PrioriteNote;
import com.mbsc.finapp.domain.enums.SensTransaction;
import com.mbsc.finapp.domain.enums.StatutNote;
import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.exception.ReglePrioriteException;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.NoteFraisRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Regles metier communes aux trois canaux de tresorerie (caisse, banque,
 * mobile money) pour le paiement d'une note de frais : regle de priorite
 * de paiement et ventilation comptable ligne par ligne.
 *
 * <p>Extrait de la logique originellement propre a {@code CaisseService},
 * parametree par le compte de tresorerie afin d'etre reutilisee telle
 * quelle par {@code BanqueService} et {@code MobileMoneyService} sans
 * dupliquer une regle financiere sensible dans trois classes.</p>
 */
@Service
@RequiredArgsConstructor
public class RegleTresorerieService {

    private static final Logger log = LoggerFactory.getLogger(RegleTresorerieService.class);

    private final NoteFraisRepository noteRepository;
    private final EcritureGrandLivreRepository ecritureRepository;
    private final ConversionDeviseService conversionDevise;
    private final EcritureComptableService comptable;

    private static final BigDecimal CENT = BigDecimal.valueOf(100);

    /**
     * Applique la règle de priorité de paiement :
     * <ul>
     *   <li>HAUTE  : peut toujours être payée si le solde est suffisant.</li>
     *   <li>MOYENNE : bloquée si des notes HAUTE sont encore en attente.</li>
     *   <li>BASSE  : bloquée si des notes HAUTE ou MOYENNE sont encore en attente.</li>
     * </ul>
     * Dans tous les cas, le solde disponible du compte de trésorerie concerné
     * (après réserve pour les notes de priorité supérieure en attente) doit
     * couvrir le montant de la note. Le blocage par priorité, lui, est
     * global : il porte sur toutes les notes TRANSMISE_CAISSE en attente,
     * quel que soit le canal qui finira par les payer.
     *
     * @param comptePrefixe numero (ou prefixe) du compte de tresorerie a
     *                      créditer : "571" (caisse), "5211" (banque),
     *                      "552" (mobile money — remappé depuis "582" par
     *                      la V23, qui a rendu "582" à son sens SYSCOHADA
     *                      réel d'"Accréditifs")...
     * @param libelleCanal  designation du canal pour les messages d'erreur,
     *                      ex. "la caisse", "la banque", "le mobile money"
     */
    public void validerReglePriorite(NoteFrais note, BigDecimal montantCDF,
                                     String comptePrefixe, String libelleCanal) {
        PrioriteNote priorite = note.getPriorite();
        Long noteId = note.getId();
        BigDecimal montant = montantCDF;

        // ── 1. Blocage si des notes de priorité supérieure sont en attente ──
        if (priorite == PrioriteNote.BASSE) {
            long nbHaute   = noteRepository.countPendingByPriority(PrioriteNote.HAUTE, noteId);
            long nbMoyenne = noteRepository.countPendingByPriority(PrioriteNote.MOYENNE, noteId);
            if (nbHaute > 0 || nbMoyenne > 0) {
                throw new ReglePrioriteException(
                    "Impossible de payer la note \"" + note.getReference() + "\" (priorité BASSE). "
                    + "Il reste " + (nbHaute + nbMoyenne) + " note(s) de priorité supérieure "
                    + "en attente de paiement "
                    + "(" + nbHaute + " HAUTE, " + nbMoyenne + " MOYENNE). "
                    + "Traitez d'abord toutes les notes HAUTE et MOYENNE avant de passer aux notes BASSE.");
            }
        } else if (priorite == PrioriteNote.MOYENNE) {
            long nbHaute = noteRepository.countPendingByPriority(PrioriteNote.HAUTE, noteId);
            if (nbHaute > 0) {
                throw new ReglePrioriteException(
                    "Impossible de payer la note \"" + note.getReference() + "\" (priorité MOYENNE). "
                    + "Il reste " + nbHaute + " note(s) de priorité HAUTE en attente de paiement. "
                    + "Les notes HAUTE doivent être traitées avant les notes MOYENNE.");
            }
        }
        // HAUTE : aucun blocage sur la priorité

        // ── 2. Vérification du solde disponible (après réserve HP > MP) ─────
        // Tout est comparé dans la devise de base (CDF), y compris la réserve
        // des notes en attente qui peuvent être libellées en USD.
        BigDecimal soldeCompte = ecritureRepository.soldePourCompte(comptePrefixe);
        if (soldeCompte == null) soldeCompte = BigDecimal.ZERO;

        // Réserve = montant total (converti en CDF) des notes de priorité
        // STRICTEMENT supérieure en attente
        BigDecimal reserve = BigDecimal.ZERO;
        if (priorite == PrioriteNote.MOYENNE) {
            reserve = noteRepository.findByStatut(StatutNote.TRANSMISE_CAISSE).stream()
                .filter(n -> !n.getId().equals(noteId))
                .filter(n -> n.getPriorite() == PrioriteNote.HAUTE)
                .map(n -> conversionDevise.enDeviseBase(n.getMontant(), n.getDevise()).montantBase())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        // BASSE : plus aucune note HP/MP en attente (vérifiées ci-dessus) ;
        // HAUTE : aucune priorité supérieure. Réserve = 0 dans les deux cas.

        BigDecimal soldeDisponible = soldeCompte.subtract(reserve);
        if (montant.compareTo(soldeDisponible) > 0) {
            throw new ReglePrioriteException(
                "Solde insuffisant pour payer la note \"" + note.getReference()
                + "\" (" + montant.toPlainString() + " FC). "
                + "Solde actuel de " + libelleCanal + " : " + soldeCompte.toPlainString() + " FC"
                + (reserve.signum() > 0
                    ? ", dont " + reserve.toPlainString() + " FC réservés pour des notes HAUTE priorité en attente."
                    : ".")
                + " Solde disponible : " + soldeDisponible.toPlainString() + " FC.");
        }

        log.debug("Regle priorite OK pour note {} (canal={}, priorite={}, montantCDF={}, soldeDispo={})",
            note.getReference(), libelleCanal, priorite, montant, soldeDisponible);
    }

    /**
     * Construit une ligne de debit par ligne de depense de la note, chacune
     * imputee a son propre compte (ou au compte de charges diverses 6588 par
     * defaut). Les montants sont convertis en devise de base au meme taux que
     * le montant global ; la derniere ligne absorbe l'ecart d'arrondi pour
     * garantir que la somme des debits egale exactement le montant total
     * credite au compte de tresorerie.
     */
    /**
     * Ventilation d'une note réglée : les écritures de débit (une ou deux par
     * ligne selon la TVA), et les entrées de stock à porter pour les lignes
     * "achat de marchandise" — même montant HT que celui débité, pour que la
     * valorisation du stock corresponde exactement à ce qui a été comptabilisé.
     */
    public record VentilationNote(List<EcritureComptableService.LigneDebit> lignesDebit,
                                  List<StockService.EntreeNoteFrais> entreesStock) {}

    @Transactional(readOnly = true)
    public VentilationNote construireLignesDebitDepuisNote(
            NoteFrais note, ConversionDeviseService.Conversion conversionTotale) {
        List<LigneNoteFrais> lignes = note.getLignes();
        List<EcritureComptableService.LigneDebit> lignesDebit = new ArrayList<>();
        List<StockService.EntreeNoteFrais> entreesStock = new ArrayList<>();
        BigDecimal sommeConvertie = BigDecimal.ZERO;
        for (int i = 0; i < lignes.size(); i++) {
            LigneNoteFrais ligne = lignes.get(i);
            CompteOHADA compte = ligne.getCompteImputation() != null
                ? ligne.getCompteImputation()
                : comptable.compteChargesDiversesParDefaut();

            // Le montant de la ligne est HORS TAXE ; c'est son montant TTC
            // (HT + TVA au taux fige a la saisie, voir LigneNoteFrais#montantTtc)
            // qui pese dans la repartition proportionnelle du montant total
            // credite en tresorerie — pas le seul HT, qui sous-evaluerait le
            // poids d'une ligne soumise a la TVA.
            BigDecimal montantCDF;
            boolean derniereLigne = (i == lignes.size() - 1);
            if (derniereLigne) {
                montantCDF = conversionTotale.montantBase().subtract(sommeConvertie);
            } else if (conversionTotale.estConvertie()) {
                montantCDF = ligne.montantTtc().multiply(conversionTotale.tauxApplique())
                    .setScale(2, RoundingMode.HALF_UP);
            } else {
                montantCDF = ligne.montantTtc();
            }
            sommeConvertie = sommeConvertie.add(montantCDF);

            // Une depense soumise a la TVA est ventilee entre le compte de
            // charge (HT) et le compte de TVA recuperable, plutot que
            // d'imputer la totalite du montant (TTC) en charge — sans quoi
            // la TVA payee par l'employe ne serait jamais recuperee. Le taux
            // utilise est celui fige a la saisie de la ligne, pas le taux du
            // jour du paiement : le montant TTC annonce sur la note ne doit
            // jamais bouger au gre d'un changement de taux legal entre-temps.
            BigDecimal montantHt = montantCDF;
            if (ligne.isSoumisTva() && ligne.getCompteTva() != null) {
                BigDecimal taux = ligne.getTauxTvaApplique();
                if (taux != null && taux.signum() > 0) {
                    BigDecimal montantTva = montantCDF.multiply(taux)
                        .divide(CENT.add(taux), 2, RoundingMode.HALF_UP);
                    montantHt = montantCDF.subtract(montantTva);
                    lignesDebit.add(new EcritureComptableService.LigneDebit(compte, montantHt));
                    lignesDebit.add(new EcritureComptableService.LigneDebit(ligne.getCompteTva(), montantTva));
                } else {
                    lignesDebit.add(new EcritureComptableService.LigneDebit(compte, montantCDF));
                }
            } else {
                lignesDebit.add(new EcritureComptableService.LigneDebit(compte, montantCDF));
            }

            // La TVA n'est jamais portee au stock (elle est recuperable, pas
            // une composante du cout de l'article) : c'est le montant HT,
            // identique a celui debite au compte de stock, qui valorise
            // l'entree — la comptabilite et le stock restent en accord.
            if (ligne.isAchatMarchandise() && ligne.getArticle() != null && ligne.getEntrepot() != null) {
                entreesStock.add(new StockService.EntreeNoteFrais(
                    ligne.getArticle(), ligne.getEntrepot(), ligne.getQuantiteMarchandise(), montantHt));
            }
        }
        return new VentilationNote(lignesDebit, entreesStock);
    }

    /**
     * Verifie que le compte de contrepartie choisi correspond au sens de
     * l'operation.
     *
     * <p><b>Comptes de reglement.</b> Les classes 1 (emprunts et dettes
     * financieres) et 4 (tiers : clients, fournisseurs, personnel, Etat) sont
     * acceptees dans les deux sens, car elles se denouent par nature dans les
     * deux sens : encaisser une creance client credite le 411 (un ACTIF), et
     * payer un fournisseur debite le 401 (un PASSIF).
     *
     * <p>La regle precedente ne raisonnait que sur le type du compte et
     * rejetait ces deux operations. Concretement, une vente a credit ne
     * pouvait plus jamais etre encaissee et une dette fournisseur ne pouvait
     * plus etre reglee : creances et dettes s'accumulaient au grand livre sans
     * aucun moyen de les solder.</p>
     */
    public void validerSensContrepartie(SensTransaction sens, CompteOHADA contrepartie) {
        TypeCompte type = contrepartie.getType();
        if (type == null) {
            return; // type non renseigne : on ne bloque pas
        }
        Integer classe = contrepartie.getClasse();
        if (classe != null && (classe == 1 || classe == 4)) {
            return; // compte de reglement : legitime dans les deux sens
        }
        if (sens == SensTransaction.DECAISSEMENT && type != TypeCompte.CHARGE && type != TypeCompte.ACTIF) {
            throw new IllegalArgumentException(
                "Un decaissement doit avoir pour contrepartie un compte de CHARGE, d'ACTIF,"
                + " ou un compte de tiers/emprunt (classes 1 et 4)");
        }
        if (sens == SensTransaction.ENCAISSEMENT && type != TypeCompte.PRODUIT && type != TypeCompte.PASSIF) {
            throw new IllegalArgumentException(
                "Un encaissement doit avoir pour contrepartie un compte de PRODUIT, de PASSIF,"
                + " ou un compte de tiers/emprunt (classes 1 et 4)");
        }
    }
}
