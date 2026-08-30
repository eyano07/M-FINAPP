package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.StatutBudget;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
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

    @Column(nullable = false, length = 200)
    private String intitule;

    @Column(nullable = false)
    private Integer exercice;   // annee

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

    @CreationTimestamp
    @Column(name = "date_creation", updatable = false)
    private Instant dateCreation;

    public void addLigne(LigneBudget ligne) {
        ligne.setBudget(this);
        this.lignes.add(ligne);
    }
}
