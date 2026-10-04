package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.Devise;
import jakarta.persistence.*;
import lombok.*;

/**
 * Paramètres du module Restaurant (ligne unique, id = 1) : la devise dans
 * laquelle les montants du module (carte, stock, tableaux de bord) sont
 * présentés par défaut, et avec combien de décimales.
 *
 * <p>N'affecte que l'affichage : le stockage sous-jacent ne change pas
 * (grand livre toujours en USD, {@code Article.prixVente} toujours en FC) —
 * voir {@code RestaurantService} et les écrans concernés pour la conversion
 * appliquée à la lecture. La répartition des sorties sur les lots de stock
 * n'est plus un paramètre : FIFO pour les boissons, CMP pour les provisions
 * (voir {@code LotStockService.methode}).</p>
 */
@Entity
@Table(name = "restaurant_parametres")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParametresRestaurant {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "devise_affichage", nullable = false, length = 3)
    @Builder.Default
    private Devise deviseAffichage = Devise.USD;

    /**
     * Chiffres après la virgule pour les montants affichés (0 à 4), ou
     * {@code null} pour l'affichage automatique : francs sans décimale,
     * dollars à 2 décimales. Affichage seulement, comme la devise.
     */
    @Column(name = "decimales_montants")
    private Integer decimalesMontants;

    /** Chiffres après la virgule, au plus, pour les quantités de stock affichées (0 à 3). */
    @Column(name = "decimales_quantites", nullable = false)
    @Builder.Default
    private Integer decimalesQuantites = 2;
}
