package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Lot d'entrée en stock d'une boisson ou d'une provision : la quantité reçue
 * à une date donnée, d'un fournisseur donné, à un prix d'achat et un prix de
 * transport donnés — voir {@code LotStockService}.
 *
 * <p><b>Suivi de gestion, jamais comptable.</b> Ce lot n'écrit aucune
 * écriture et ne modifie jamais {@code StockNiveau} : il vit à côté du coût
 * moyen pondéré, qui reste la seule source de vérité du grand livre. Sert
 * uniquement à afficher, sur les pages de stock du module Restaurant, la
 * traçabilité des achats (date, fournisseur, prix) et à répartir les
 * sorties sur les lots (FIFO : le plus ancien d'abord ; CMP : tous au
 * prorata — {@link com.mbsc.finapp.domain.enums.MethodeSortieLots}).</p>
 */
@Entity
@Table(name = "lots_stock")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LotStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id")
    private Article article;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "entrepot_id")
    private Entrepot entrepot;

    /**
     * Ligne d'entrée (ou de transfert) qui a créé ce lot — {@code null} pour
     * le lot d'ouverture inséré par la migration V89 pour le stock déjà en
     * place. Sert à {@code LotStockService.annuler} à retrouver le lot créé
     * par une ligne annulée.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ligne_mouvement_id")
    private LigneMouvementStock ligneMouvement;

    @Column(name = "date_entree", nullable = false)
    private LocalDate dateEntree;

    /** Bénéficiaire de la note de frais qui a financé cet achat ; {@code null} si le lot ne vient pas d'un achat. */
    @Column(length = 200)
    private String fournisseur;

    @Column(name = "quantite_initiale", nullable = false, precision = 15, scale = 3)
    private BigDecimal quantiteInitiale;

    @Column(name = "quantite_restante", nullable = false, precision = 15, scale = 3)
    private BigDecimal quantiteRestante;

    /** Prix d'achat unitaire hors frais de transport/manutention. */
    @Column(name = "prix_achat_unitaire", nullable = false, precision = 15, scale = 6)
    @Builder.Default
    private BigDecimal prixAchatUnitaire = BigDecimal.ZERO;

    /** Part de transport et manutention incorporée par unité (0 si le lot n'en a reçu aucune). */
    @Column(name = "prix_transport_unitaire", nullable = false, precision = 15, scale = 6)
    @Builder.Default
    private BigDecimal prixTransportUnitaire = BigDecimal.ZERO;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
