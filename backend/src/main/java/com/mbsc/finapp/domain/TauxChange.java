package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "taux_change")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TauxChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Taux : combien de FC pour 1 USD. */
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal taux;

    @Column(name = "date_effet", nullable = false)
    private LocalDate dateEffet;

    @Column(length = 500)
    private String note;

    /**
     * Reference verifiable de la source du taux (ex. "BCC, cote du 18/08/2026",
     * bulletin, capture d'ecran datee). Distincte de {@code note} : la note
     * reste un commentaire libre, la source est ce qu'un controle exigerait
     * de pouvoir retrouver.
     */
    @Column(length = 200)
    private String source;

    /** Administrateur ayant saisi ou corrige ce taux (tracabilite). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
