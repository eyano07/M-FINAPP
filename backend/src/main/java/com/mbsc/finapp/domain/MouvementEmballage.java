package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.TypeMouvementEmballage;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Mouvement du stock de bouteilles vides. Append-only : le compteur de
 * {@link EmballageBoisson} porte l'etat courant, cette table en est
 * l'historique verifiable — la somme signee des mouvements doit toujours
 * egaler le compteur.
 *
 * <p>La quantite est toujours positive ; le sens vient du type.</p>
 */
@Entity
@Table(name = "restaurant_mouvements_emballage")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MouvementEmballage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "emballage_id")
    @ToString.Exclude
    private EmballageBoisson emballage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TypeMouvementEmballage type;

    @Column(name = "quantite_bouteilles", nullable = false)
    private Integer quantiteBouteilles;

    @Column(name = "date_mouvement", nullable = false)
    private LocalDate dateMouvement;

    /**
     * Vente a l'origine du mouvement, pour les mouvements automatiques
     * (VENTE, RETOUR_VENTE). {@code null} pour une saisie manuelle.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vente_id")
    @ToString.Exclude
    private Vente vente;

    /** Motif libre : indispensable pour justifier une casse ou un ecart d'inventaire. */
    @Column(length = 500)
    private String motif;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
