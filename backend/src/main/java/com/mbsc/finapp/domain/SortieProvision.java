package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.TypeSortieProvision;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Type et observation d'une sortie de stock de provision (utilisation en cuisine, casse, péremption...),
 * saisis depuis l'écran Entrées / sorties de provisions du restaurant.
 *
 * <p>La sortie elle-même est un {@link MouvementStock} ordinaire, valorisé au coût moyen pondéré et
 * comptabilisé ; cette entité en garde le type et l'observation, que le mouvement de stock ne sait pas
 * porter. Elle s'annule avec son mouvement, depuis le même écran. Une sortie saisie avant sa création
 * n'en a pas : son type reste inconnu.</p>
 */
@Entity
@Table(name = "restaurant_sorties_provisions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SortieProvision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "mouvement_stock_id")
    @ToString.Exclude
    private MouvementStock mouvementStock;

    @Enumerated(EnumType.STRING)
    @Column(name = "type_sortie", nullable = false, length = 30)
    private TypeSortieProvision typeSortie;

    @Column(length = 500)
    private String observation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    @ToString.Exclude
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
