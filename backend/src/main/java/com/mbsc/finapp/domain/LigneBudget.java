package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "lignes_budget")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LigneBudget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "budget_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Budget budget;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "compte_id")
    private CompteOHADA compte;

    @Column(name = "montant_prevu", precision = 15, scale = 2, nullable = false)
    private BigDecimal montantPrevu;

    @Column(name = "montant_realise", precision = 15, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal montantRealise = BigDecimal.ZERO;
}
