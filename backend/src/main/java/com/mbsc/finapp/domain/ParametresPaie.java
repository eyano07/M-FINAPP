package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Paramètres de paie globaux (ligne unique, id = 1) : taux de cotisations,
 * barème IPR configurable et informations de signature des documents
 * imprimés (bulletins, reçus, ordres de mission...).
 *
 * <p>Les taux sont stockés en fractions (0.30, pas 30) : c'est la valeur
 * directement utilisable dans les formules de {@code PayrollCalculationService},
 * la conversion en pourcentage pour l'affichage est une responsabilité du
 * frontend, pas du modèle.</p>
 */
@Entity
@Table(name = "drh_parametres_paie")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParametresPaie {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    /** Indemnité de logement = salaire brut × ce taux. */
    @Column(name = "taux_logement", nullable = false, precision = 6, scale = 4)
    private BigDecimal tauxLogement;

    /** Indemnité de transport = salaire brut × ce taux. */
    @Column(name = "taux_transport", nullable = false, precision = 6, scale = 4)
    private BigDecimal tauxTransport;

    /** CNSS part ouvrière (déduite du net) = base H × ce taux. */
    @Column(name = "taux_cnss_ouvriere", nullable = false, precision = 6, scale = 4)
    private BigDecimal tauxCnssOuvriere;

    /**
     * CNSS part patronale totale (charge employeur, non déduite) : somme des trois branches ci-dessous, recalculée à
     * chaque enregistrement des paramètres (voir {@code ParametresPaieService}). Le calcul de la paie applique les
     * taux par branche.
     */
    @Column(name = "taux_cnss_patronale", nullable = false, precision = 6, scale = 4)
    private BigDecimal tauxCnssPatronale;

    /** CNSS patronale, branche des pensions (décret n° 18/041 du 24/11/2018 : 5 %), créditée en 4313. */
    @Column(name = "taux_cnss_pension_patronale", nullable = false, precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal tauxCnssPensionPatronale = new BigDecimal("0.05");

    /** CNSS patronale, branche des prestations familiales (décret n° 18/041 : 6,5 %), créditée en 4311. */
    @Column(name = "taux_cnss_pf", nullable = false, precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal tauxCnssPf = new BigDecimal("0.065");

    /** CNSS patronale, branche des risques professionnels (décret n° 18/041 : 1,5 %), créditée en 4312. */
    @Column(name = "taux_cnss_rp", nullable = false, precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal tauxCnssRp = new BigDecimal("0.015");

    /** ONEM (charge employeur) = base cotisable × ce taux (arrêté d'août 2025 : 0,5 %). */
    @Column(name = "taux_onem", nullable = false, precision = 6, scale = 4)
    private BigDecimal tauxOnem;

    /** INPP (charge employeur) = base cotisable × ce taux, pour un effectif de 1 à 50 employés (3 %). */
    @Column(name = "taux_inpp", nullable = false, precision = 6, scale = 4)
    private BigDecimal tauxInpp;

    /** INPP pour un effectif de 51 à 300 employés (2 %). Paramétrable : une réforme des taux est annoncée. */
    @Column(name = "taux_inpp_51_300", nullable = false, precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal tauxInpp51A300 = new BigDecimal("0.02");

    /** INPP pour un effectif de plus de 300 employés (1 %). */
    @Column(name = "taux_inpp_plus_300", nullable = false, precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal tauxInppPlus300 = new BigDecimal("0.01");

    /**
     * Compter le conjoint d'un agent marié comme personne à charge dans la réduction familiale de l'IPR (pratique
     * courante en RDC, d'où le paramètre).
     */
    @Column(name = "conjoint_a_charge", nullable = false)
    @Builder.Default
    private boolean conjointACharge = true;

    /** Congé payé acquis par mois entier de service, en jours ouvrables (Code du travail : au moins 1). */
    @Column(name = "jours_conge_par_mois", nullable = false, precision = 6, scale = 2)
    @Builder.Default
    private BigDecimal joursCongeParMois = BigDecimal.ONE;

    /** Jours ouvrables de congé ajoutés par période de 5 ans d'ancienneté (Code du travail : 1). */
    @Column(name = "jours_conge_par_tranche_5_ans", nullable = false, precision = 6, scale = 2)
    @Builder.Default
    private BigDecimal joursCongeParTranche5Ans = BigDecimal.ONE;

    /** Réduction IPR par enfant à charge (2 % = 0.02), plafonnée à {@link #plafondEnfantsIpr}. */
    @Column(name = "reduction_ipr_par_enfant", nullable = false, precision = 6, scale = 4)
    private BigDecimal reductionIprParEnfant;

    /** Nombre maximal d'enfants pris en compte dans la réduction IPR. */
    @Column(name = "plafond_enfants_ipr", nullable = false)
    private Integer plafondEnfantsIpr;

    /**
     * Si vrai, le nombre d'enfants à charge réduit l'IPR (voir
     * {@code PayrollCalculationService#calculerIpr}). Si faux, le DRH a
     * désactivé cette réduction : l'IPR est calculé comme si aucun employé
     * n'avait d'enfant, quels que soient {@link #reductionIprParEnfant} et
     * {@link #plafondEnfantsIpr} ci-dessus, qui restent configurés en
     * prévision d'une réactivation.
     */
    @Column(name = "calcul_enfants_actif", nullable = false)
    @Builder.Default
    private boolean calculEnfantsActif = true;

    /** Plancher IPR en francs congolais, appliqué après réduction familiale. */
    @Column(name = "plancher_ipr_fc", nullable = false, precision = 15, scale = 2)
    private BigDecimal plancherIprFc;

    /** Nombre de jours ouvrables standard du mois (pour le taux de présence et l'affichage "Temps"). */
    @Column(name = "jours_ouvrables_standard", nullable = false)
    private Integer joursOuvrablesStandard;

    // ------------------------------------------------------------------
    // Conformité au droit congolais (voir V47__drh_conformite_rdc.sql).
    // Ces champs pilotent la base IPR et la part exonérée de l'allocation
    // familiale (minimum légal) ; les autres alimentent des contrôles consultatifs.
    // ------------------------------------------------------------------

    /**
     * Déduire la CNSS salariale de la base imposable IPR. Vrai par défaut depuis V147 (PwC Tax Summaries RDC :
     * seule la part salariale est déductible de la base IPR) ; {@code false} = comportement historique du classeur
     * DEBOURS MBSC.
     */
    @Column(name = "cnss_deductible_ipr", nullable = false)
    @Builder.Default
    private boolean cnssDeductibleIpr = true;

    /** SMIG journalier du manœuvre ordinaire, en FC (Décret n° 25/22 du 30/05/2025). */
    @Column(name = "smig_journalier_fc", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal smigJournalierFc = new BigDecimal("21500");

    /** Allocation familiale minimale par enfant = SMIG journalier ÷ ce diviseur (Décret n° 25/22, Art. 5). */
    @Column(name = "diviseur_allocation_familiale", nullable = false)
    @Builder.Default
    private Integer diviseurAllocationFamiliale = 27;

    /** Plafond légal des retenues pour avances (1/10ᵉ du salaire). */
    @Column(name = "plafond_retenue_pct", nullable = false, precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal plafondRetenuePct = new BigDecimal("0.10");

    /** Plafond journalier d'exonération de l'indemnité de transport, en FC ; 0 désactive le contrôle. */
    @Column(name = "plafond_transport_exonere_fc_jour", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal plafondTransportExonereFcJour = BigDecimal.ZERO;

    /** Durée légale hebdomadaire du travail (Code du travail, Art. 119). */
    @Column(name = "heures_legales_hebdo", nullable = false)
    @Builder.Default
    private Integer heuresLegalesHebdo = 45;

    /** Majoration des 6 premières heures supplémentaires (Art. 120). */
    @Column(name = "taux_majoration_hs1", nullable = false, precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal tauxMajorationHs1 = new BigDecimal("0.30");

    /** Majoration des heures supplémentaires au-delà de la 6ᵉ (Art. 120). */
    @Column(name = "taux_majoration_hs2", nullable = false, precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal tauxMajorationHs2 = new BigDecimal("0.60");

    /** Majoration des heures effectuées un jour de repos hebdomadaire ou férié (Art. 120). */
    @Column(name = "taux_majoration_hs_ferie", nullable = false, precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal tauxMajorationHsFerie = BigDecimal.ONE;

    /**
     * Mode de comptabilisation de la paie (audit DRH du 15/11/2026, D-01).
     * Vrai (mode AUTOMATIQUE) : la clôture du mois par le DFIN écrit aussitôt
     * la pièce de constatation de la paie au journal PAIE, datée du dernier jour
     * du mois (voir {@code PaieNoteService.constaterALaCloture}). Faux (mode
     * MANUEL) : la clôture verrouille seulement les bulletins ; le DFIN
     * comptabilise ensuite le mois par l'action « Comptabiliser la paie »
     * ({@code BulletinPaieService.comptabiliserPeriode}). À défaut, le paiement
     * de la note de paie écrit la constatation ({@code PaieNoteService.apresPaiementInterne}).
     */
    @Column(name = "comptabiliser_paie", nullable = false)
    @Builder.Default
    private boolean comptabiliserPaie = true;

    @Column(name = "directeur_drh", length = 150)
    private String directeurDrh;

    @Column(name = "fonction_directeur", length = 150)
    private String fonctionDirecteur;

    @Column(name = "ville_signature", length = 80)
    private String villeSignature;
}
