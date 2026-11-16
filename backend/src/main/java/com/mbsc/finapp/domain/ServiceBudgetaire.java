package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Service (departement) de l'entreprise, designe avec le nom de son responsable. Les lignes de depenses d'un
 * budget et les notes de frais peuvent y etre rattachees : chaque service a son enveloppe dans le budget, qui
 * reste unique (consolide), et ses depenses se suivent a part (voir SuiviBudgetaireService, V122).
 */
@Entity
@Table(name = "services_budgetaires")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServiceBudgetaire {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique, a la casse pres (index ux_services_budgetaires_nom). */
    @Column(nullable = false, length = 120)
    private String nom;

    /** Nom du responsable du service (texte libre : il n'a pas forcement de compte dans l'application). */
    @Column(nullable = false, length = 150)
    private String responsable;

    @Column(length = 500)
    private String description;

    /** Un service desactive ne se choisit plus pour une nouvelle depense ou un nouveau budget ; son historique reste. */
    @Column(nullable = false)
    @Builder.Default
    private boolean actif = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cree_par_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private User creePar;

    @CreationTimestamp
    @Column(name = "date_creation", nullable = false, updatable = false)
    private Instant dateCreation;
}
