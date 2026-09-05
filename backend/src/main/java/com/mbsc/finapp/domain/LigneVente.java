package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Ligne d'une vente : un article, une quantite, un prix unitaire hors
 * taxes et la TVA calculee a la validation.
 */
@Entity
@Table(name = "lignes_vente")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LigneVente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "vente_id", nullable = false)
    @ToString.Exclude
    private Vente vente;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    /**
     * Chargement de minerais cede par cette ligne, non nul pour un article
     * {@code minerais} uniquement : c'est lui qui porte l'identite du camion
     * (plaque, jour d'achat) derriere le prix de vente propre a la ligne.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "camion_id")
    private CamionMinerai camion;

    /** Libelle fige a la saisie : la facture reste lisible si l'article change. */
    @Column(nullable = false, length = 200)
    private String designation;

    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal quantite;

    @Column(name = "prix_unitaire", nullable = false, precision = 15, scale = 2)
    private BigDecimal prixUnitaire;

    @Column(name = "soumis_tva", nullable = false)
    @Builder.Default
    private boolean soumisTva = true;

    @Column(name = "montant_ht", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal montantHt = BigDecimal.ZERO;

    @Column(name = "montant_tva", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal montantTva = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private Integer ordre = 1;
}
