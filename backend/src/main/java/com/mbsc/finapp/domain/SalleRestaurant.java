package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

/** Salle du restaurant (ex. "Terrasse", "Salle principale"), support du plan de tables. */
@Entity
@Table(name = "salles_restaurant")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalleRestaurant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String nom;

    /** Ordre d'affichage des onglets de salle, du plus petit au plus grand. */
    @Column(nullable = false)
    @Builder.Default
    private int ordre = 0;

    @Column(nullable = false)
    @Builder.Default
    private boolean actif = true;
}
