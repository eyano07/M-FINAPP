package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Grand livre de stock (Stock Ledger Entry ERPNext) : trace chaque mouvement
 * valorisé pour un article dans un entrepôt.
 */
@Entity
@Table(name = "stock_grand_livre")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockGrandLivre {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id")
    private Article article;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "entrepot_id")
    private Entrepot entrepot;

    @Column(name = "date_ecriture", nullable = false)
    private LocalDate dateEcriture;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mouvement_id")
    @ToString.Exclude
    private MouvementStock mouvement;

    @Column(name = "qte_entree", nullable = false, precision = 15, scale = 3)
    @Builder.Default
    private BigDecimal qteEntree = BigDecimal.ZERO;

    @Column(name = "qte_sortie", nullable = false, precision = 15, scale = 3)
    @Builder.Default
    private BigDecimal qteSortie = BigDecimal.ZERO;

    @Column(name = "qte_apres", nullable = false, precision = 15, scale = 3)
    @Builder.Default
    private BigDecimal qteApres = BigDecimal.ZERO;

    @Column(name = "valeur_unitaire", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal valeurUnitaire = BigDecimal.ZERO;

    @Column(name = "valeur_apres", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal valeurApres = BigDecimal.ZERO;
}
