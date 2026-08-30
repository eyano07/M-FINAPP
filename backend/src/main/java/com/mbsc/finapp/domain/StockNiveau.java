package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Niveau de stock (Bin ERPNext) : quantité et valeur cumulée d'un article
 * dans un entrepôt. Le coût moyen pondéré se déduit (valeurTotale / quantite).
 */
@Entity
@Table(name = "stock_niveaux",
    uniqueConstraints = @UniqueConstraint(name = "uq_stock_niveau", columnNames = {"article_id", "entrepot_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockNiveau {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id")
    private Article article;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "entrepot_id")
    private Entrepot entrepot;

    @Column(nullable = false, precision = 15, scale = 3)
    @Builder.Default
    private BigDecimal quantite = BigDecimal.ZERO;

    @Column(name = "valeur_totale", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal valeurTotale = BigDecimal.ZERO;

    /**
     * Verrou optimiste : deux validations simultanées sur le même couple
     * (article, entrepôt) ne peuvent plus s'écraser silencieusement.
     */
    @Version
    @Column(nullable = false)
    @Builder.Default
    private long version = 0L;
}
