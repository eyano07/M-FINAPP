package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Opération d'un relevé bancaire : entrée (crédit de la banque) ou sortie (débit de la banque) sur le compte. */
@Entity
@Table(name = "lignes_releve")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LigneReleve {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "releve_id")
    @ToString.Exclude
    private ReleveBancaire releve;

    @Column(nullable = false)
    private Integer ordre;

    @Column(name = "date_operation", nullable = false)
    private LocalDate dateOperation;

    @Column(nullable = false, length = 500)
    private String libelle;

    @Column(length = 100)
    private String reference;

    /** Argent reçu sur le compte (versement, virement reçu, intérêts). */
    @Column(nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal entree = BigDecimal.ZERO;

    /** Argent sorti du compte (paiement, retrait, frais, agios). */
    @Column(nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal sortie = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pointage_id")
    @ToString.Exclude
    private PointageRapprochement pointage;

    /** Montant signé : entrée − sortie. */
    public BigDecimal net() {
        return entree.subtract(sortie);
    }
}
