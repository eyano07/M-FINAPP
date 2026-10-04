package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.MotifSortiePlat;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Sortie de stock d'un plat hors vente (périmé, moisi, renversé, offert...),
 * saisie depuis l'écran Sorties de plats du restaurant.
 *
 * <p>La sortie elle-même est un {@link MouvementStock} ordinaire, valorisé au
 * coût de production moyen et comptabilisé (D 736x / C 361x) ; cette entité
 * en garde le motif et l'état. Elle s'annule d'ici, jamais par son seul
 * mouvement — voir {@code StockService.exigerAnnulableSeul}.</p>
 */
@Entity
@Table(name = "restaurant_sorties_plats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SortiePlat {

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
    @Column(nullable = false, length = 30)
    private MotifSortiePlat motif;

    @Column(name = "precision_motif", length = 500)
    private String precisionMotif;

    @Column(name = "date_sortie", nullable = false)
    private LocalDate dateSortie;

    /** Coût sorti (quantité × coût de production moyen), en USD comme le grand livre. */
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
