package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Une periode (mois) du plan d'amortissement d'un bien.
 *
 * <p>Le plan entier est calcule et stocke des la creation du bien : il est
 * ainsi consultable en previsionnel, et la comptabilisation se contente de
 * marquer les periodes echues. Le couple (bien, periode) est unique en base :
 * c'est cette contrainte — et non un simple test applicatif — qui garantit
 * qu'une dotation ne peut pas etre passee deux fois.</p>
 */
@Entity
@Table(name = "lignes_amortissement")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LigneAmortissement {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "immobilisation_id")
    @ToString.Exclude
    private Immobilisation immobilisation;

    /** Premier jour du mois amorti. */
    @Column(nullable = false)
    private LocalDate periode;

    @Column(name = "base_amortissable", nullable = false, precision = 15, scale = 2)
    private BigDecimal baseAmortissable;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal dotation;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal cumul;

    @Column(name = "valeur_nette", nullable = false, precision = 15, scale = 2)
    private BigDecimal valeurNette;

    @Column(nullable = false)
    @Builder.Default
    private boolean comptabilise = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "piece_id")
    private PieceComptable piece;

    @Column(name = "date_comptabilisation")
    private Instant dateComptabilisation;
}
