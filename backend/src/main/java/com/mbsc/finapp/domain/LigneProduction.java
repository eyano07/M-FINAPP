package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Un ingredient reellement consomme par une {@link Production}.
 *
 * <p>Peut s'ecarter de la fiche technique — c'est le but d'une fiche
 * ajustable. Le cout unitaire est celui du CMP au moment de la sortie, fige
 * ici : le relire plus tard donnerait un cout de revient qui bouge avec les
 * achats posterieurs.</p>
 */
@Entity
@Table(name = "lignes_production")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LigneProduction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "production_id", nullable = false)
    @ToString.Exclude
    private Production production;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provision_id", nullable = false)
    @ToString.Exclude
    private Article provision;

    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal quantite;

    /** CMP de la provision au moment de la sortie. */
    @Column(name = "cout_unitaire", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal coutUnitaire = BigDecimal.ZERO;

    @Column(nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal montant = BigDecimal.ZERO;
}
