package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

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

    /**
     * Majoration appliquee au prix catalogue de chaque article vendu a une
     * table de cette salle (ex. 15.00 = +15%, salle VIP). 0 = prix inchange.
     * Purement indicative cote serveur : comme tout prix de vente dans cette
     * application, la majoration se propose au client au moment de choisir
     * l'article, jamais imposee — voir ventes/nouvelle.vue prixCatalogue.
     */
    @Column(name = "majoration_pourcentage", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal majorationPourcentage = BigDecimal.ZERO;
}
