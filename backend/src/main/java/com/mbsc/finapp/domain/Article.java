package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.TypeArticle;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "articles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Article {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 200)
    private String libelle;

    @Column(name = "unite_mesure", length = 20)
    private String uniteMesure;

    /** MARCHANDISE (stockee) ou SERVICE (prestation, sans stock). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TypeArticle type = TypeArticle.MARCHANDISE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "compte_stock_id")
    private CompteOHADA compteStock;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "compte_charge_id")
    private CompteOHADA compteCharge;

    /** Compte d'achat (601x), débité au règlement d'une note de frais d'achat de marchandise — voir NoteFraisService. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "compte_achat_id")
    private CompteOHADA compteAchat;

    /**
     * true pour un minerais : le stock se suit camion par camion
     * ({@link CamionMinerai}), chacun ayant son propre prix de vente. Réservé
     * aux marchandises stockées ; sans effet sur les comptes d'imputation,
     * identiques pour tous les chargements d'un même minerais.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean minerais = false;

    /** Entrepot d'affectation, obligatoire pour une marchandise, toujours nul pour un service. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entrepot_id")
    private Entrepot entrepot;

    /** Compte de produit credite a la vente (7011 marchandises, 7061 services). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "compte_produit_id")
    private CompteOHADA compteProduit;

    /** Prix de vente unitaire hors taxes, en devise de base (FC). */
    @Column(name = "prix_vente", precision = 15, scale = 2)
    private BigDecimal prixVente;

    /**
     * Prix d'achat unitaire indicatif, en devise de base (FC). Facultatif :
     * il ne sert qu'a preremplir le montant d'un achat saisi a la caisse, que
     * le caissier corrige au prix reellement paye. Le cout d'entree en stock
     * (CMP) reste toujours celui effectivement saisi, jamais cette valeur.
     */
    @Column(name = "prix_achat", precision = 15, scale = 2)
    private BigDecimal prixAchat;

    /** false = article exonere de TVA. */
    @Column(name = "soumis_tva", nullable = false)
    @Builder.Default
    private boolean soumisTva = true;

    @Column(name = "stock_min", nullable = false, precision = 15, scale = 3)
    @Builder.Default
    private BigDecimal stockMin = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private boolean actif = true;

    /**
     * true si l'article peut figurer sur une vente (prix et compte de produit
     * definis).
     *
     * <p>Un CONSOMMABLE en est exclu par nature : il est achete pour etre
     * consomme en interne, pas revendu. Sans cette exclusion, un consommable
     * pourvu d'un prix apparaitrait dans le catalogue de vente.</p>
     */
    public boolean estVendable() {
        return actif
            && type != TypeArticle.CONSOMMABLE
            && type != TypeArticle.PROVISION
            && prixVente != null && prixVente.signum() > 0
            && compteProduit != null;
    }
}
