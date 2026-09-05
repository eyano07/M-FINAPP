package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.StatutCamionMinerai;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Un chargement de minerais, identifie par sa plaque et son jour d'achat.
 *
 * <p>Les marchandises ordinaires se suivent en quantite : dix sacs de ciment
 * sont interchangeables. Un minerais, non — chaque camion se revend a son
 * propre prix selon sa teneur et le cours du jour, alors qu'ils partagent
 * tous le meme prix d'achat et les memes comptes d'imputation. D'ou ce suivi
 * unitaire, qui ne remplace pas le stock mais s'y superpose : un camion vaut
 * une unite de l'article ({@code minerais = true}).</p>
 *
 * <p><b>Comptablement</b>, le camion suit deux cycles independants :</p>
 * <ul>
 *   <li><b>Reception</b> (logistique) : D 601x Achats / C 4011 Fournisseurs,
 *       puis D 311x Stock / C 6031 Variation — la marchandise entre en stock
 *       et la dette fournisseur nait. Voir {@code MineraiService.receptionner}.</li>
 *   <li><b>Reglement</b> (caisse) : D 4011 Fournisseurs / C 571 Caisse, qui
 *       solde la dette. Independant de la vente : un camion peut etre vendu
 *       avant d'etre paye, ou l'inverse — d'ou {@link #regle} distinct de
 *       {@link #statut}.</li>
 * </ul>
 */
@Entity
@Table(name = "camions_minerai")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CamionMinerai {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entrepot_id", nullable = false)
    private Entrepot entrepot;

    /** Plaque d'immatriculation du camion, telle que relevee a la reception. */
    @Column(nullable = false, length = 40)
    private String plaque;

    /** Jour d'achat/reception du chargement. */
    @Column(name = "date_achat", nullable = false)
    private LocalDate dateAchat;

    /** Prix paye au fournisseur pour le chargement entier, en devise de base. */
    @Column(name = "prix_achat", nullable = false, precision = 15, scale = 2)
    private BigDecimal prixAchat;

    /**
     * Cout d'acquisition = {@link #prixAchat} + frais accessoires incorpores
     * ({@link ChargeCamionMinerai}). C'est ce montant, et non le cout moyen
     * pondere de l'article, qui sort du stock a la vente : deux chargements
     * n'ont ni la meme teneur ni les memes frais de route, le SYSCOHADA admet
     * donc ici l'identification specifique.
     */
    @Column(name = "cout_acquisition", nullable = false, precision = 15, scale = 2)
    private BigDecimal coutAcquisition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StatutCamionMinerai statut = StatutCamionMinerai.EN_STOCK;

    /** true une fois la dette fournisseur soldee par la caisse. */
    @Column(nullable = false)
    @Builder.Default
    private boolean regle = false;

    /** Mouvement d'entree en stock genere a la reception. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mouvement_id")
    private MouvementStock mouvement;

    /** Piece d'achat (D 601 / C 4011) generee a la reception. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "piece_reception_id")
    private PieceComptable pieceReception;

    /** Decaissement de caisse qui a solde la dette fournisseur. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_reglement_id")
    private TransactionCaisse transactionReglement;

    @CreationTimestamp
    @Column(name = "date_creation", updatable = false)
    private Instant dateCreation;

    @UpdateTimestamp
    @Column(name = "date_maj")
    private Instant dateMaj;

    /** "AB 1234 CD — 05/09/2026", pour les libelles d'ecriture et les listes. */
    public String designation() {
        return plaque + " — " + dateAchat;
    }
}
