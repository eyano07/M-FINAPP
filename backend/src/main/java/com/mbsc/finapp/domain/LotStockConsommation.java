package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Part d'un {@link LotStock} prélevée par une ligne de sortie (ou par le
 * départ d'un transfert) : garde, pour chaque sortie, le détail exact de ce
 * qu'elle a pris à chaque lot, afin qu'une annulation restitue précisément
 * les mêmes lots — quelle que soit la méthode (FIFO ou CMP) en vigueur au
 * moment de l'annulation, qui peut différer de celle appliquée à la sortie
 * d'origine.
 *
 * <p>Voir {@code LotStockService.consommer} et {@code .annuler}.</p>
 */
@Entity
@Table(name = "lots_stock_consommations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LotStockConsommation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "ligne_mouvement_id")
    private LigneMouvementStock ligneMouvement;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "lot_id")
    private LotStock lot;

    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal quantite;
}
