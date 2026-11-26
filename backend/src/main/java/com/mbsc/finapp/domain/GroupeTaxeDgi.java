package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/** Groupe de taxation DGI (A, B...) avec son libellé et son taux. */
@Entity
@Table(name = "groupes_taxe_dgi")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupeTaxeDgi {

    @Id
    @Column(length = 5)
    private String code;

    @Column(nullable = false, length = 120)
    private String libelle;

    @Column(nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal taux = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private boolean actif = true;

    @Column(nullable = false)
    private int ordre;
}
