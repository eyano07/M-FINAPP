package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Provision pour congés payés d'un exercice (audit DRH du 15/11/2026, D-05) : droits à congé acquis et non pris
 * au jour de l'arrêté, et charges sociales correspondantes, rattachés à l'exercice (indépendance des exercices).
 * Une pièce au journal PAIE datée de l'arrêté (6613/6623 contre 4281, 6641/6642 contre 4382) et son extourne au
 * lendemain. Une seule provision ACTIVE par année ; ANNULEE après extourne de ses deux pièces.
 */
@Entity
@Table(name = "drh_provisions_conges")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProvisionConges {

    public static final String ACTIVE = "ACTIVE";
    public static final String ANNULEE = "ANNULEE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Integer annee;

    @Column(name = "date_arrete", nullable = false)
    private LocalDate dateArrete;

    @Column(name = "montant_conges", nullable = false, precision = 15, scale = 2)
    private BigDecimal montantConges;

    @Column(name = "montant_charges", nullable = false, precision = 15, scale = 2)
    private BigDecimal montantCharges;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "piece_id")
    @ToString.Exclude
    private PieceComptable piece;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "piece_extourne_id")
    @ToString.Exclude
    private PieceComptable pieceExtourne;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String statut = ACTIVE;

    /** Calcul par agent au moment de la constitution (JSON), pour justifier le montant. */
    @Column(columnDefinition = "TEXT")
    private String detail;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    @ToString.Exclude
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
