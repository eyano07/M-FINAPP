package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/** Ligne d'un retour de vente : la ligne vendue, la quantite rendue et sa part du montant (dans la devise de la vente). */
@Entity
@Table(name = "lignes_retour_vente")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LigneRetourVente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "retour_id", nullable = false)
    @ToString.Exclude
    private RetourVente retour;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "ligne_vente_id", nullable = false)
    @ToString.Exclude
    private LigneVente ligneVente;

    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal quantite;

    /** Marchandise intacte remise en stock (bouteille non ouverte) ; un plat servi ne revient pas en stock. */
    @Column(name = "remise_en_stock", nullable = false)
    @Builder.Default
    private boolean remiseEnStock = true;

    @Column(name = "montant_ht", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal montantHt = BigDecimal.ZERO;

    @Column(name = "montant_tva", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal montantTva = BigDecimal.ZERO;

    /** Entree en stock du remboursement, s'il y a remise en stock. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mouvement_stock_id")
    @ToString.Exclude
    private MouvementStock mouvementStock;
}
