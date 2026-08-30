package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ParametresPaie;
import com.mbsc.finapp.dto.drh.ControleConformite;
import com.mbsc.finapp.dto.drh.EntreesCalculPaie;
import com.mbsc.finapp.dto.drh.ResultatCalculPaie;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Moteur de calcul de la paie MBSC (formule 2026, portée depuis l'outil de
 * paie PayMBSC — {@code PayrollService.recalculer}/{@code calculerIpr}).
 *
 * <p><b>Fonction pure, sans persistance ni dépendance JPA</b> : prend les
 * saisies d'un bulletin + les paramètres de paie + le taux de change déjà
 * résolu, renvoie le résultat complet. {@code BulletinPaieService} est seul
 * responsable de mapper ce résultat sur l'entité et de le persister — ce
 * découplage permet de tester le moteur directement, sans contexte Spring,
 * contre les cas connus du classeur DEBOURS MBSC 2026 et de l'ancien outil.</p>
 *
 * <p>Arrondi : {@link RoundingMode#HALF_UP} à 2 décimales sur chaque montant
 * intermédiaire stocké, convention déjà en place dans
 * {@link ConversionDeviseService} pour toute conversion de devise dans cette
 * application — pas une invention propre à ce module.</p>
 */
@Service
public class PayrollCalculationService {

    private static final BigDecimal CENT = new BigDecimal("100");

    private static final BigDecimal PALIER_1 = new BigDecimal("162000");
    private static final BigDecimal PALIER_2 = new BigDecimal("1800000");
    private static final BigDecimal PALIER_3 = new BigDecimal("3600000");
    private static final BigDecimal TAUX_1 = new BigDecimal("0.03");
    private static final BigDecimal TAUX_2 = new BigDecimal("0.15");
    private static final BigDecimal TAUX_3 = new BigDecimal("0.30");
    private static final BigDecimal TAUX_4 = new BigDecimal("0.40");
    private static final BigDecimal CUMUL_2 = new BigDecimal("4860");
    private static final BigDecimal CUMUL_3 = new BigDecimal("250560");
    private static final BigDecimal CUMUL_4 = new BigDecimal("790560");
    private static final BigDecimal PLAFOND_FRACTION_REVENU = new BigDecimal("0.30");

    /**
     * Calcule un bulletin complet.
     *
     * @param entrees saisies du bulletin (salaire, gains, retenues, présence, enfants)
     * @param params  paramètres de paie en vigueur (taux, barème IPR)
     * @param tauxFC  taux de change (FC pour 1 USD) déjà résolu à la date de paiement —
     *                voir {@code ConversionDeviseService.tauxALaDate}
     */
    public ResultatCalculPaie calculer(EntreesCalculPaie entrees, ParametresPaie params, BigDecimal tauxFC) {
        BigDecimal presenceFraction = arrondiFraction(nz(entrees.presencePct()).divide(CENT, 6, RoundingMode.HALF_UP));

        BigDecimal rBrut = arrondiMontant(nz(entrees.salaireBaseUsd()).multiply(presenceFraction));

        BigDecimal indLogement = arrondiMontant(rBrut.multiply(nz(params.getTauxLogement())));
        BigDecimal indTransport = arrondiMontant(rBrut.multiply(nz(params.getTauxTransport())));

        // H = base reelle des cotisations = R - I - J (= R*0.60 avec les taux par defaut)
        BigDecimal h = arrondiMontant(rBrut.subtract(indLogement).subtract(indTransport));

        BigDecimal gains = nz(entrees.conge())
            .add(nz(entrees.heuresSupplementaires()))
            .add(nz(entrees.allocationFamiliale()))
            .add(nz(entrees.primeDiplome()))
            .add(nz(entrees.primeAnciennete()))
            .add(nz(entrees.primeRendement()));

        BigDecimal q = arrondiMontant(h.add(gains));

        BigDecimal cnssOuvriere = arrondiMontant(h.multiply(nz(params.getTauxCnssOuvriere())));
        BigDecimal cnssPatronale = arrondiMontant(h.multiply(nz(params.getTauxCnssPatronale())));
        BigDecimal onem = arrondiMontant(h.multiply(nz(params.getTauxOnem())));
        BigDecimal totalInss = cnssOuvriere.add(cnssPatronale);
        BigDecimal inpp = arrondiMontant(q.multiply(nz(params.getTauxInpp())));

        int nombreEnfants = entrees.nombreEnfants() != null ? entrees.nombreEnfants() : 0;

        // Base imposable IPR : H, éventuellement diminuée de la CNSS ouvrière
        // selon le paramétrage (voir ParametresPaie.cnssDeductibleIpr — les
        // sources fiscales RDC se contredisent, d'où le choix laissé ouvert ;
        // false = comportement historique du classeur DEBOURS MBSC).
        BigDecimal baseIpr = params.isCnssDeductibleIpr() ? arrondiMontant(h.subtract(cnssOuvriere)) : h;
        BigDecimal ipr = calculerIpr(baseIpr, tauxFC, nombreEnfants, params);

        BigDecimal net = arrondiMontant(
            rBrut.subtract(cnssOuvriere).subtract(ipr)
                .add(gains)
                .subtract(nz(entrees.avanceSalaire()))
                .subtract(nz(entrees.pret())));

        BigDecimal netFc = tauxFC != null && tauxFC.signum() > 0
            ? net.multiply(tauxFC).setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        BigDecimal retenues = nz(entrees.avanceSalaire()).add(nz(entrees.pret()));
        ControleConformite conformite = controler(
            rBrut, indTransport, nz(entrees.allocationFamiliale()), retenues, nombreEnfants, params, tauxFC);

        return new ResultatCalculPaie(
            rBrut, indLogement, indTransport, q, h, baseIpr,
            cnssOuvriere, cnssPatronale, onem, totalInss, inpp, ipr,
            net, tauxFC, netFc, conformite);
    }

    /**
     * Contrôle de conformité au droit congolais — <b>consultatif</b> : ne
     * modifie ni ne bloque aucun montant, se contente de signaler les écarts
     * et de fournir les références légales calculées (voir
     * {@link ControleConformite}).
     *
     * <p>Les seuils viennent tous de {@link ParametresPaie} plutôt que d'être
     * codés en dur : le SMIG et les quotas légaux évoluent par décret, et le
     * plafond d'exonération transport dépend du tarif local.</p>
     */
    public ControleConformite controler(BigDecimal rBrut, BigDecimal indTransport, BigDecimal allocationFamiliale,
                                         BigDecimal retenues, int nombreEnfants, ParametresPaie params,
                                         BigDecimal tauxFC) {
        List<String> avertissements = new ArrayList<>();
        boolean tauxUtilisable = tauxFC != null && tauxFC.signum() > 0;

        int jours = params.getJoursOuvrablesStandard() != null ? params.getJoursOuvrablesStandard() : 26;

        // --- SMIG (Décret n° 25/22 du 30/05/2025) -------------------------
        BigDecimal smigMensuelUsd = BigDecimal.ZERO;
        if (tauxUtilisable) {
            BigDecimal smigMensuelFc = nz(params.getSmigJournalierFc()).multiply(BigDecimal.valueOf(jours));
            smigMensuelUsd = smigMensuelFc.divide(tauxFC, 2, RoundingMode.HALF_UP);
            if (rBrut.signum() > 0 && rBrut.compareTo(smigMensuelUsd) < 0) {
                avertissements.add(String.format(Locale.FRENCH,
                    "Salaire brut (%.2f USD) inférieur au SMIG légal (%.2f USD, soit %s FC/jour × %d jours) "
                    + "— Décret n° 25/22 du 30/05/2025.",
                    rBrut, smigMensuelUsd, nz(params.getSmigJournalierFc()).toPlainString(), jours));
            }
        }

        // --- Allocation familiale minimale (Décret n° 25/22, Art. 5) ------
        BigDecimal allocationMinimumUsd = BigDecimal.ZERO;
        int diviseur = params.getDiviseurAllocationFamiliale() != null ? params.getDiviseurAllocationFamiliale() : 27;
        if (tauxUtilisable && nombreEnfants > 0 && diviseur > 0) {
            BigDecimal parEnfantParJourFc = nz(params.getSmigJournalierFc())
                .divide(BigDecimal.valueOf(diviseur), 6, RoundingMode.HALF_UP);
            BigDecimal minimumFc = parEnfantParJourFc
                .multiply(BigDecimal.valueOf(nombreEnfants))
                .multiply(BigDecimal.valueOf(jours));
            allocationMinimumUsd = minimumFc.divide(tauxFC, 2, RoundingMode.HALF_UP);
            if (allocationFamiliale.compareTo(allocationMinimumUsd) < 0) {
                avertissements.add(String.format(Locale.FRENCH,
                    "Allocation familiale (%.2f USD) inférieure au minimum légal (%.2f USD pour %d enfant(s), "
                    + "soit 1/%dᵉ du SMIG journalier par enfant) — Décret n° 25/22, Art. 5.",
                    allocationFamiliale, allocationMinimumUsd, nombreEnfants, diviseur));
            }
        }

        // --- Plafond légal des retenues (1/10ᵉ du salaire) ----------------
        BigDecimal plafondRetenuesUsd = arrondiMontant(rBrut.multiply(nz(params.getPlafondRetenuePct())));
        if (retenues.signum() > 0 && retenues.compareTo(plafondRetenuesUsd) > 0) {
            avertissements.add(String.format(Locale.FRENCH,
                "Retenues (%.2f USD) supérieures au plafond légal (%.2f USD, soit %s %% du salaire) "
                + "— les retenues pour avances sont limitées au dixième du salaire.",
                retenues, plafondRetenuesUsd,
                nz(params.getPlafondRetenuePct()).multiply(CENT).stripTrailingZeros().toPlainString()));
        }

        // --- Exonération transport (désactivée si le plafond vaut 0) ------
        BigDecimal plafondTransportFcJour = nz(params.getPlafondTransportExonereFcJour());
        if (tauxUtilisable && plafondTransportFcJour.signum() > 0) {
            BigDecimal plafondTransportUsd = plafondTransportFcJour
                .multiply(BigDecimal.valueOf(jours))
                .divide(tauxFC, 2, RoundingMode.HALF_UP);
            if (indTransport.compareTo(plafondTransportUsd) > 0) {
                avertissements.add(String.format(Locale.FRENCH,
                    "Indemnité de transport (%.2f USD) supérieure au quota exonéré (%.2f USD) : "
                    + "l'excédent est en principe imposable.",
                    indTransport, plafondTransportUsd));
            }
        }

        // --- Référence heures supplémentaires (Art. 119-120) --------------
        // Informatif : le montant des heures sup reste saisi manuellement,
        // le système ne peut pas le valider sans connaître les heures.
        int heuresHebdo = params.getHeuresLegalesHebdo() != null ? params.getHeuresLegalesHebdo() : 45;
        BigDecimal tauxHoraire = BigDecimal.ZERO;
        if (heuresHebdo > 0 && rBrut.signum() > 0) {
            BigDecimal heuresMensuelles = BigDecimal.valueOf(heuresHebdo)
                .multiply(new BigDecimal("52"))
                .divide(new BigDecimal("12"), 6, RoundingMode.HALF_UP);
            tauxHoraire = rBrut.divide(heuresMensuelles, 2, RoundingMode.HALF_UP);
        }

        return new ControleConformite(
            avertissements,
            smigMensuelUsd,
            allocationMinimumUsd,
            plafondRetenuesUsd,
            tauxHoraire,
            majorer(tauxHoraire, params.getTauxMajorationHs1()),
            majorer(tauxHoraire, params.getTauxMajorationHs2()),
            majorer(tauxHoraire, params.getTauxMajorationHsFerie()));
    }

    private static BigDecimal majorer(BigDecimal tauxHoraire, BigDecimal majoration) {
        return arrondiMontant(tauxHoraire.multiply(BigDecimal.ONE.add(nz(majoration))));
    }

    /**
     * IPR (Impôt Professionnel sur les Revenus) — barème progressif RDC
     * (LF 2020), formule complète du classeur DEBOURS MBSC 2026 : plafond à
     * 30 % du revenu imposable, réduction 2 %/enfant (max configurable, 9 par
     * défaut) appliquée sur le montant plafonné, puis plancher appliqué
     * <b>après</b> la réduction familiale.
     *
     * <p>Diverge délibérément de la version simplifiée qui tournait dans
     * l'ancien outil PayMBSC (bases cumulées 245 700/785 700, sans plafond ni
     * réduction) — voir la décision actée avec l'utilisateur au moment de la
     * conception de ce module : c'est la formule du classeur, source de
     * référence métier, qui fait foi.</p>
     */
    public BigDecimal calculerIpr(BigDecimal baseUsd, BigDecimal tauxFC, int nombreEnfants, ParametresPaie params) {
        if (baseUsd == null || tauxFC == null || tauxFC.signum() <= 0 || baseUsd.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal baseFc = baseUsd.multiply(tauxFC).setScale(2, RoundingMode.HALF_UP);

        BigDecimal bracketRaw;
        if (baseFc.compareTo(PALIER_1) <= 0) {
            bracketRaw = baseFc.multiply(TAUX_1);
        } else if (baseFc.compareTo(PALIER_2) <= 0) {
            bracketRaw = CUMUL_2.add(baseFc.subtract(PALIER_1).multiply(TAUX_2));
        } else if (baseFc.compareTo(PALIER_3) <= 0) {
            bracketRaw = CUMUL_3.add(baseFc.subtract(PALIER_2).multiply(TAUX_3));
        } else {
            bracketRaw = CUMUL_4.add(baseFc.subtract(PALIER_3).multiply(TAUX_4));
        }

        BigDecimal plafond = baseFc.multiply(PLAFOND_FRACTION_REVENU);
        BigDecimal plafonne = bracketRaw.min(plafond);

        int enfantsRetenus = Math.min(nombreEnfants, params.getPlafondEnfantsIpr());
        BigDecimal reduction = nz(params.getReductionIprParEnfant()).multiply(new BigDecimal(enfantsRetenus));
        BigDecimal reduit = plafonne.multiply(BigDecimal.ONE.subtract(reduction));

        BigDecimal iprFc = reduit.max(nz(params.getPlancherIprFc())).setScale(2, RoundingMode.HALF_UP);

        return iprFc.divide(tauxFC, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal arrondiMontant(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal arrondiFraction(BigDecimal v) {
        return v.setScale(6, RoundingMode.HALF_UP);
    }
}
