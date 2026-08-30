package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Reprise (totale ou partielle) d'une provision : le risque s'est réalisé,
 * s'est éteint, ou a été réestimé à la baisse.
 *
 * <p>Le compte de reprise (79x/77x) est saisi à la reprise et non déduit du
 * compte de dotation : une dotation d'exploitation peut être reprise en
 * exploitation comme en HAO selon la nature de l'évènement, et c'est au
 * comptable de trancher.</p>
 */
@Entity
@Table(name = "provision_reprises")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProvisionReprise {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "provision_id")
    @ToString.Exclude
    private Provision provision;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal montant;

    @Column(length = 300)
    private String motif;

    @Column(name = "date_reprise", nullable = false)
    private LocalDate dateReprise;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "compte_reprise_id")
    private CompteOHADA compteReprise;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "piece_id")
    @ToString.Exclude
    private PieceComptable piece;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    @ToString.Exclude
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
