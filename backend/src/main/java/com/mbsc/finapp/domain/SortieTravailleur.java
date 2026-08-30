package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

/** Sortie ponctuelle d'un employé pendant la journée de travail (module DRH_PRESENCES). */
@Entity
@Table(name = "drh_sorties_travailleurs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SortieTravailleur {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employe_id", nullable = false)
    @ToString.Exclude
    private Employe employe;

    @Column(name = "date_sortie", nullable = false)
    private LocalDate dateSortie;

    @Column(name = "heure_sortie", nullable = false)
    private LocalTime heureSortie;

    /** Null tant que l'employé n'est pas revenu. */
    @Column(name = "heure_retour")
    private LocalTime heureRetour;

    @Column(nullable = false, length = 300)
    private String motif;

    @Column(length = 500)
    private String notes;

    public Long getDureeMinutes() {
        if (heureSortie == null || heureRetour == null) {
            return null;
        }
        return java.time.Duration.between(heureSortie, heureRetour).toMinutes();
    }
}
