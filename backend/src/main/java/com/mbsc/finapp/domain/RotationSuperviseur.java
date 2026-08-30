package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Rotation d'un superviseur sur un site opérationnel : 14 jours de
 * prestation (dimanches inclus) puis 7 jours de repos, une fois par mois
 * (module DRH_MISSIONS). {@code numeroCycle} reste toujours 1 en pratique
 * (porté depuis l'ancien outil pour compatibilité de schéma).
 */
@Entity
@Table(name = "drh_rotations_superviseurs",
    uniqueConstraints = @UniqueConstraint(name = "uq_drh_rotation_employe_site_periode",
        columnNames = {"employe_id", "site_id", "annee", "mois", "numero_cycle"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RotationSuperviseur {

    public static final int JOURS_PRESTATION = 14;
    public static final int JOURS_REPOS = 7;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employe_id", nullable = false)
    @ToString.Exclude
    private Employe employe;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "site_id", nullable = false)
    @ToString.Exclude
    private SiteOperationnel site;

    @Column(nullable = false)
    private Integer annee;

    @Column(nullable = false)
    private Integer mois;

    @Column(name = "numero_cycle", nullable = false)
    @Builder.Default
    private Integer numeroCycle = 1;

    @Column(name = "prestation_debut", nullable = false)
    private LocalDate prestationDebut;

    @Column(name = "prestation_fin", nullable = false)
    private LocalDate prestationFin;

    @Column(name = "repos_debut", nullable = false)
    private LocalDate reposDebut;

    @Column(name = "repos_fin", nullable = false)
    private LocalDate reposFin;

    @Column(length = 500)
    private String notes;

    public static LocalDate finPrestation(LocalDate debut) {
        return debut.plusDays(JOURS_PRESTATION - 1L);
    }

    public static LocalDate finRepos(LocalDate debut) {
        return debut.plusDays(JOURS_REPOS - 1L);
    }

    public static int joursInclusifs(LocalDate debut, LocalDate fin) {
        if (debut == null || fin == null) {
            return 0;
        }
        return (int) ChronoUnit.DAYS.between(debut, fin) + 1;
    }
}
