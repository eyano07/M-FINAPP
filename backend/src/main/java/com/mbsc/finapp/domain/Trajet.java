package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.StatutTrajet;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "trajets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Trajet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String reference;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicule_id")
    private Vehicule vehicule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conducteur_id")
    private User conducteur;

    @Column(name = "date_depart")
    private Instant dateDepart;

    @Column(name = "date_arrivee")
    private Instant dateArrivee;

    @Column(length = 200)
    private String origine;

    @Column(length = 200)
    private String destination;

    @Column(name = "distance_km", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal distanceKm = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatutTrajet statut;
}
