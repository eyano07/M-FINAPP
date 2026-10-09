package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;

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

    /** Montant annuel prevu : toujours egal a la somme des douze mois (maintenu par le service). */
    @Column(name = "montant_prevu", precision = 15, scale = 2, nullable = false)
    private BigDecimal montantPrevu;

    /**
     * Ventilation mensuelle du montant prevu : mois (1 = janvier de l'exercice) -> montant. Les trimestres,
     * semestres et l'annee en sont des totaux (voir MoteurBudgetaire).
     */
    @ElementCollection
    @CollectionTable(name = "lignes_budget_mois", joinColumns = @JoinColumn(name = "ligne_budget_id"))
    @MapKeyColumn(name = "mois")
    @Column(name = "montant", precision = 15, scale = 2, nullable = false)
    @Builder.Default
    private Map<Integer, BigDecimal> mois = new TreeMap<>();

    /** Hypothese ou base de calcul de la ligne (ex. « 2 vehicules x 120 l/mois »). */
    @Column(length = 500)
    private String commentaire;

    /**
     * Ancienne colonne, plus utilisee : le realise est desormais calcule a la demande depuis le grand livre
     * (SuiviBudgetaireService). Conservee a zero pour la compatibilite du schema.
     */
    @Column(name = "montant_realise", precision = 15, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal montantRealise = BigDecimal.ZERO;

    /** Montant prevu d'un mois (0 si non renseigne). */
    public BigDecimal prevuDuMois(int numeroMois) {
        return mois.getOrDefault(numeroMois, BigDecimal.ZERO);
    }
}
