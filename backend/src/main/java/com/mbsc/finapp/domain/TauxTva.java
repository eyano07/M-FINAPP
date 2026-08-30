package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Taux de TVA en vigueur a partir d'une date.
 *
 * <p>Table en ajout seul : une facture emise conserve le taux applique a
 * sa date meme apres un changement de taux legal, ce qui garantit la
 * reproductibilite des declarations.</p>
 */
@Entity
@Table(name = "taux_tva")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TauxTva {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Taux en pourcentage (16.00 = 16 %). */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal taux;

    @Column(name = "date_effet", nullable = false)
    private LocalDate dateEffet;

    @Column(length = 500)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    @ToString.Exclude
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
