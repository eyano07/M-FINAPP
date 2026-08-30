package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "lignes_mouvement_stock")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LigneMouvementStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "mouvement_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private MouvementStock mouvement;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id")
    private Article article;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entrepot_source_id")
    private Entrepot entrepotSource;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entrepot_cible_id")
    private Entrepot entrepotCible;

    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal quantite;

    @Column(name = "cout_unitaire", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal coutUnitaire = BigDecimal.ZERO;

    @Column(nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal montant = BigDecimal.ZERO;
}
