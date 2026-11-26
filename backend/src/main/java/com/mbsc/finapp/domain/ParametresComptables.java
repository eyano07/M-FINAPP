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

    /**
     * Date de la dernière clôture définitive d'exercice (audit SYSCOHADA du 10/11/2026, C-03) : le verrou de
     * période ne peut jamais redescendre en dessous.
     */
    @Column(name = "date_cloture_exercice")
    private LocalDate dateClotureExercice;

    /**
     * Dérogation à la validation par une autre personne (C-06) : true = l'auteur d'une pièce peut la
     * comptabiliser lui-même. Réservée aux très petites équipes, posée par le DA ou le DG et tracée.
     */
    @Column(name = "auto_validation_autorisee", nullable = false)
    private boolean autoValidationAutorisee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "maj_par_id")
    @ToString.Exclude
    private User majPar;

    @Column(name = "maj_le")
    private Instant majLe;
}
