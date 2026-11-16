package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.DureeBudget;
import com.mbsc.finapp.domain.enums.StatutBudget;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "budgets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Budget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** BUD-AAAA-NNNNNN (voir ReferenceGenerator#pourBudget). */
    @Column(nullable = false, unique = true, length = 30)
    private String reference;

    @Column(nullable = false, length = 200)
    private String intitule;

    @Column(nullable = false)
    private Integer exercice;   // annee

    /**
     * Duree couverte : l'exercice entier, un semestre, un trimestre ou un mois (V122). Avec {@link #periode},
     * elle fixe les mois couverts ; les montants des autres mois restent a zero.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    @Builder.Default
    private DureeBudget duree = DureeBudget.ANNUEL;

    /** Numero de la periode dans l'exercice : 1 pour l'annee, 1-2 (semestre), 1-4 (trimestre), 1-12 (mois). */
    @Column(nullable = false)
    @Builder.Default
    private int periode = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StatutBudget statut = StatutBudget.BROUILLON;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "elabore_par_id")
    private User elaborePar;   // DFIN

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approuve_par_id")
    private User approuvePar;  // DA

    @OneToMany(mappedBy = "budget", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    private List<LigneBudget> lignes = new ArrayList<>();

    @Column(length = 1000)
    private String observation;

    /** Motif du dernier rejet par le DA (conserve meme apres reprise, pour la tracabilite). */
    @Column(name = "motif_rejet", length = 1000)
    private String motifRejet;

    /** Budget dont celui-ci est une revision (budget rectificatif) ; null pour la version initiale. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revision_de_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Budget revisionDe;

    /** 0 pour la version initiale, puis 1, 2... a chaque revision. */
    @Column(name = "numero_revision", nullable = false)
    @Builder.Default
    private int numeroRevision = 0;

    @Column(name = "date_soumission")
    private Instant dateSoumission;

    @Column(name = "date_approbation")
    private Instant dateApprobation;

    @Column(name = "date_execution")
    private Instant dateExecution;

    @Column(name = "date_cloture")
    private Instant dateCloture;

    @CreationTimestamp
    @Column(name = "date_creation", updatable = false)
    private Instant dateCreation;

    public void addLigne(LigneBudget ligne) {
        ligne.setBudget(this);
        this.lignes.add(ligne);
    }

    /** Premier mois couvert (1 = janvier de l'exercice). */
    public int moisDebut() {
        return duree.moisDebut(periode);
    }

    /** Dernier mois couvert (12 = decembre de l'exercice). */
    public int moisFin() {
        return duree.moisFin(periode);
    }

    public boolean couvreMois(int mois) {
        return mois >= moisDebut() && mois <= moisFin();
    }

    /** Premier jour de la periode couverte. */
    public LocalDate premierJour() {
        return LocalDate.of(exercice, moisDebut(), 1);
    }

    /** Dernier jour de la periode couverte. */
    public LocalDate dernierJour() {
        return YearMonth.of(exercice, moisFin()).atEndOfMonth();
    }

    /** « Exercice 2026 », « 2e semestre 2026 », « 4e trimestre 2026 », « Décembre 2026 ». */
    public String libellePeriode() {
        return duree.libelle(periode, exercice);
    }

    /** true si les deux budgets couvrent au moins un mois en commun. */
    public boolean chevauche(Budget autre) {
        return exercice.equals(autre.getExercice()) && moisDebut() <= autre.moisFin() && autre.moisDebut() <= moisFin();
    }

    /** true si au moins une ligne est rattachee a un service (budget organise par service). */
    public boolean parService() {
        return lignes.stream().anyMatch(l -> l.getService() != null);
    }
}
