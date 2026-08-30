package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "grand_livre")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EcritureGrandLivre {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private TransactionCaisse transaction;

    @ManyToOne(optional = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_bancaire_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private TransactionBancaire transactionBancaire;

    @ManyToOne(optional = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_mobile_money_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private TransactionMobileMoney transactionMobileMoney;

    @ManyToOne(optional = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "piece_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private PieceComptable piece;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "compte_id")
    private CompteOHADA compte;

    @Column(precision = 15, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal debit = BigDecimal.ZERO;

    @Column(precision = 15, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal credit = BigDecimal.ZERO;

    @Column(length = 255)
    private String libelle;

    @Column(name = "date_ecriture", nullable = false)
    private LocalDate dateEcriture;

    /** Devise d'origine de l'opération (devise de base : CDF). */
    @Column(nullable = false, length = 3)
    @Builder.Default
    private String devise = "CDF";

    /** Montant dans la devise d'origine quand elle diffère de la devise de base. */
    @Column(name = "montant_devise", precision = 15, scale = 2)
    private BigDecimal montantDevise;

    /** Taux de conversion appliqué à la comptabilisation (FC pour 1 unité de devise). */
    @Column(name = "taux_applique", precision = 15, scale = 6)
    private BigDecimal tauxApplique;
}
