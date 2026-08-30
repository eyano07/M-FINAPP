package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Paramètres comptables globaux (ligne unique, id = 1).
 *
 * <p>{@code dateCloture} est l'équivalent de la « closing date » QuickBooks :
 * aucune écriture ne peut être créée, comptabilisée ou extournée à une date
 * antérieure ou égale à la clôture.</p>
 */
@Entity
@Table(name = "parametres_comptables")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParametresComptables {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "date_cloture")
    private LocalDate dateCloture;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "maj_par_id")
    @ToString.Exclude
    private User majPar;

    @Column(name = "maj_le")
    private Instant majLe;
}
