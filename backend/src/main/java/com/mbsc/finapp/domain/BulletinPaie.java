package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.StatutBulletin;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Bulletin de paie mensuel d'un employé (module DRH_PAIE). Un bulletin par
 * (employé, mois, année) — contrainte unique en base, absente de l'ancien
 * outil PayMBSC où elle n'était vérifiée qu'en Java.
 *
 * <p>Les 15 champs calculés (de {@code salaireBrut} à {@code netFc}) sont
 * produits par {@code PayrollCalculationService.calculer} et stockés tels
 * quels : ce ne sont pas des colonnes dérivées recalculées à la volée, pour
 * que le bulletin reste reproductible même si les paramètres de paie ou le
 * taux de change changent ensuite (voir {@code tauxChangeApplique}, figé au
 * moment du calcul comme {@link EcritureGrandLivre#getTauxApplique()}).</p>
 */
@Entity
@Table(name = "drh_bulletins_paie",
    uniqueConstraints = @UniqueConstraint(name = "uq_drh_bulletin_employe_periode",
        columnNames = {"employe_id", "mois", "annee"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BulletinPaie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employe_id", nullable = false)
    @ToString.Exclude
    private Employe employe;

    @Column(nullable = false)
    private Integer mois;

    @Column(nullable = false)
    private Integer annee;

    // --- Saisies -----------------------------------------------------

    @Column(name = "salaire_base_usd", nullable = false, precision = 15, scale = 2)
    private BigDecimal salaireBaseUsd;

    @Column(name = "presence_pct", nullable = false, precision = 6, scale = 2)
    @Builder.Default
    private BigDecimal presencePct = new BigDecimal("100");

    /**
     * Copié depuis {@code employe.nombreEnfants} à la création, mais stocké
     * indépendamment : la réduction IPR appliquée à ce bulletin doit rester
     * reproductible même si le nombre d'enfants déclaré de l'employé change
     * ensuite (naissance, mise à jour de dossier...).
     */
    @Column(name = "nombre_enfants", nullable = false)
    @Builder.Default
    private Integer nombreEnfants = 0;

    @Column(nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal conge = BigDecimal.ZERO;

    @Column(name = "heures_supplementaires", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal heuresSupplementaires = BigDecimal.ZERO;

    @Column(name = "allocation_familiale", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal allocationFamiliale = BigDecimal.ZERO;

    @Column(name = "prime_diplome", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal primeDiplome = BigDecimal.ZERO;

    @Column(name = "prime_anciennete", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal primeAnciennete = BigDecimal.ZERO;

    @Column(name = "prime_rendement", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal primeRendement = BigDecimal.ZERO;

    @Column(name = "avance_salaire", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal avanceSalaire = BigDecimal.ZERO;

    @Column(nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal pret = BigDecimal.ZERO;

    // --- Calculés (PayrollCalculationService) -------------------------

    @Column(name = "salaire_brut", precision = 15, scale = 2)
    private BigDecimal salaireBrut;

    @Column(name = "indemnite_logement", precision = 15, scale = 2)
    private BigDecimal indemniteLogement;

    @Column(name = "indemnite_transport", precision = 15, scale = 2)
    private BigDecimal indemniteTransport;

    @Column(name = "base_imposable_inpp", precision = 15, scale = 2)
    private BigDecimal baseImposableInpp;

    @Column(name = "base_imposable_inss", precision = 15, scale = 2)
    private BigDecimal baseImposableInss;

    @Column(name = "base_imposable_ipr", precision = 15, scale = 2)
    private BigDecimal baseImposableIpr;

    @Column(name = "cnss_ouvriere", precision = 15, scale = 2)
    private BigDecimal cnssOuvriere;

    @Column(name = "cnss_patronale", precision = 15, scale = 2)
    private BigDecimal cnssPatronale;

    @Column(precision = 15, scale = 2)
    private BigDecimal onem;

    @Column(name = "total_inss", precision = 15, scale = 2)
    private BigDecimal totalInss;

    @Column(precision = 15, scale = 2)
    private BigDecimal inpp;

    @Column(precision = 15, scale = 2)
    private BigDecimal ipr;

    @Column(name = "salaire_net", precision = 15, scale = 2)
    private BigDecimal salaireNet;

    @Column(name = "taux_change_applique", precision = 18, scale = 4)
    private BigDecimal tauxChangeApplique;

    @Column(name = "net_fc", precision = 15, scale = 2)
    private BigDecimal netFc;

    // --- Cycle de vie --------------------------------------------------

    @Column(name = "date_paiement", nullable = false)
    private LocalDate datePaiement;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StatutBulletin statut = StatutBulletin.BROUILLON;

    /**
     * Renseignée à la clôture, que la paie soit comptabilisée ou non (voir
     * {@code ParametresPaie.comptabiliserPaie}) ; l'état "comptabilisé" se lit
     * lui sur {@code pieceComptable}/{@code pieceComptable.statut}. C'est ce
     * champ, et non plus la seule présence de {@code pieceComptable}, qui
     * verrouille le bulletin — voir {@code BulletinPaieService.exigerModifiable}.
     */
    @Column(name = "date_cloture")
    private Instant dateCloture;

    /** Renseignée à la clôture uniquement si la paie est comptabilisée ; null sinon. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "piece_comptable_id")
    @ToString.Exclude
    private PieceComptable pieceComptable;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    @ToString.Exclude
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
