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

    /** CNSS part patronale (charge employeur, non déduite) = base H × ce taux. */
    @Column(name = "taux_cnss_patronale", nullable = false, precision = 6, scale = 4)
    private BigDecimal tauxCnssPatronale;

    /** ONEM (charge employeur) = base H × ce taux. */
    @Column(name = "taux_onem", nullable = false, precision = 6, scale = 4)
    private BigDecimal tauxOnem;

    /** INPP (charge employeur) = base Q × ce taux. */
    @Column(name = "taux_inpp", nullable = false, precision = 6, scale = 4)
    private BigDecimal tauxInpp;

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
    // Ces champs ne pilotent aucun calcul automatique hormis la base IPR :
    // ils alimentent des contrôles purement consultatifs.
    // ------------------------------------------------------------------

    /**
     * Déduire la CNSS ouvrière de la base imposable IPR. {@code false} =
     * comportement historique du classeur DEBOURS MBSC (base = H). Les
     * sources fiscales consultées se contredisent sur ce point, d'où le
     * paramétrage plutôt qu'un choix codé en dur.
     */
    @Column(name = "cnss_deductible_ipr", nullable = false)
    @Builder.Default
    private boolean cnssDeductibleIpr = false;

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
     * Si vrai, la clôture d'une période de paie poste une pièce comptable
     * BROUILLON par bulletin (salaire net + charges patronales CNSS/ONEM/INPP,
     * voir {@code PaieComptabilisationService}). Si faux, la clôture
     * verrouille les bulletins sans générer aucune écriture comptable.
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
