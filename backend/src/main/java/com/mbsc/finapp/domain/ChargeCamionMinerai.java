package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Frais accessoire d'achat rattache a un camion de minerais : transport, pont
 * bascule, peage routier, document de chargement/dechargement, manutention...
 *
 * <p><b>Pourquoi les rattacher au camion plutot que les passer en charges de
 * la periode.</b> Le SYSCOHADA revise range les frais accessoires d'achat
 * dans le <em>cout d'acquisition</em> de la marchandise. Les laisser en
 * charges immediates surevaluerait la marge du camion tant qu'il dort en
 * stock, puis la sous-evaluerait a la vente : le resultat serait juste sur
 * l'exercice entier mais faux mois par mois, et impossible a analyser camion
 * par camion.</p>
 *
 * <p><b>Deux ecritures, comme la reception du camion :</b> la charge est
 * d'abord constatee par nature (D 61x/62x / C 4011 Fournisseurs, journal
 * ACHATS) puis incorporee au stock (D 311x / C 6031, journal STOCK). La
 * premiere donne au compte de resultat la lecture par nature attendue, la
 * seconde porte le cout la ou il doit etre jusqu'a la vente. Voir
 * {@code MineraiService.ajouterCharge}.</p>
 */
@Entity
@Table(name = "charges_camion_minerai")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChargeCamionMinerai {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "camion_id", nullable = false)
    private CamionMinerai camion;

    /** Nature de la depense en clair : « Pont bascule », « Peage routier »... */
    @Column(nullable = false, length = 200)
    private String libelle;

    /** Compte de charge par nature (611 transports sur achats, 6288 divers...). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compte_charge_id", nullable = false)
    private CompteOHADA compteCharge;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal montant;

    @Column(name = "date_charge", nullable = false)
    private LocalDate dateCharge;

    /** true une fois la dette envers le prestataire soldee par la caisse. */
    @Column(nullable = false)
    @Builder.Default
    private boolean regle = false;

    /** Piece de constatation par nature (D charge / C 4011). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "piece_id")
    private PieceComptable piece;

    /** Piece d'incorporation au stock (D 311x / C 6031). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "piece_incorporation_id")
    private PieceComptable pieceIncorporation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_reglement_id")
    private TransactionCaisse transactionReglement;

    /**
     * Note de frais en cours demandant le reglement de ce frais via le
     * circuit DFIN/DA/Tresorerie (meme mecanisme que {@code
     * CamionMinerai.noteFraisReglement}) — empeche qu'un meme frais soit
     * inclus dans deux notes a la fois. Nul tant qu'aucune note n'a ete
     * creee, remis a nul si elle est annulee.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "note_frais_reglement_id")
    private NoteFrais noteFraisReglement;

    @CreationTimestamp
    @Column(name = "date_creation", updatable = false)
    private Instant dateCreation;

    @UpdateTimestamp
    @Column(name = "date_maj")
    private Instant dateMaj;
}
