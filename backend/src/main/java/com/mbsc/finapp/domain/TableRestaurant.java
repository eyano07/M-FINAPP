package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.FormeTable;
import jakarta.persistence.*;
import lombok.*;

/**
 * Table positionnee sur le plan d'une {@link SalleRestaurant}.
 *
 * <p>Position et dimensions sont en pixels, dans le repere propre a l'editeur
 * de plan cote frontend — de simples coordonnees d'affichage, sans unite
 * physique ni lien avec les ventes (voir pages/restaurant/salles.vue).</p>
 */
@Entity
@Table(name = "tables_restaurant")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TableRestaurant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "salle_id", nullable = false)
    private SalleRestaurant salle;

    @Column(nullable = false, length = 20)
    private String numero;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private FormeTable forme = FormeTable.CARRE;

    @Column(name = "pos_x", nullable = false)
    @Builder.Default
    private int posX = 0;

    @Column(name = "pos_y", nullable = false)
    @Builder.Default
    private int posY = 0;

    @Column(nullable = false)
    @Builder.Default
    private int largeur = 100;

    @Column(nullable = false)
    @Builder.Default
    private int hauteur = 100;

    @Column(name = "nb_chaises", nullable = false)
    @Builder.Default
    private int nbChaises = 4;

    /**
     * Statut d'occupation au quotidien, independant du plan lui-meme : se
     * modifie sans passer par l'editeur de disposition (voir
     * RestaurantService.ECRITURE, plus permissif que ECRITURE_SALLES).
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean occupee = false;
}
