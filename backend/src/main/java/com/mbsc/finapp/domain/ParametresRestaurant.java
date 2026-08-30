package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.Devise;
import jakarta.persistence.*;
import lombok.*;

/**
 * Paramètres du module Restaurant (ligne unique, id = 1) : la devise dans
 * laquelle les montants du module (carte, stock, tableaux de bord) sont
 * présentés par défaut.
 *
 * <p>N'affecte que l'affichage : le stockage sous-jacent ne change pas
 * (grand livre toujours en USD, {@code Article.prixVente} toujours en FC) —
 * voir {@code RestaurantService} et les écrans concernés pour la conversion
 * appliquée à la lecture.</p>
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
}
