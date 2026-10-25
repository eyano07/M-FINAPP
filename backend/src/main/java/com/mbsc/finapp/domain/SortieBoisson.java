package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.MotifSortieBoisson;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Perte d'une boisson sans consigne (casse, peremption, cadeau), saisie depuis l'ecran des mouvements de vides du bar
 * (V134). La sortie elle-meme est un {@link MouvementStock} ordinaire, valorise au cout moyen et comptabilise (D compte de
 * la perte / C compte de stock de la boisson) ; cette entite en garde le motif et l'etat, comme {@link SortiePlat}. Une
 * boisson consignee perd, elle, aussi sa bouteille : elle passe par les mouvements de vides.
 */
@Entity
@Table(name = "restaurant_sorties_boissons")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SortieBoisson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id")
    private Article article;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "entrepot_id")
    private Entrepot entrepot;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "mouvement_stock_id")
    @ToString.Exclude
    private MouvementStock mouvementStock;

    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal quantite;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MotifSortieBoisson motif;

    @Column(name = "precision_motif", length = 500)
    private String precisionMotif;

    @Column(name = "date_sortie", nullable = false)
    private LocalDate dateSortie;

    /** Cout sorti (quantite × cout moyen), en devise de base comme le grand livre. */
    @Column(nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal valeur = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private boolean annulee = false;

    @Column(name = "annulee_le")
    private Instant annuleeLe;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "annulee_par_id")
    @ToString.Exclude
    private User annuleePar;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    @ToString.Exclude
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
