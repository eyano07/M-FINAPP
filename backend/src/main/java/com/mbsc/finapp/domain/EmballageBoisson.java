package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Conditionnement d'une boisson et stock de ses bouteilles vides.
 *
 * <p>Une boisson, un conditionnement (relation 1-1) : « le casier de Coca
 * 33 cl contient 24 bouteilles ». Le nombre de casiers n'est PAS stocke, il se
 * deduit du seul compteur {@link #bouteillesVides} : c'est ce qui reproduit
 * exactement l'arithmetique metier (80 vides = 3 casiers + 8 bouteilles ;
 * apres 31 ventes, 111 vides = 4 casiers + 15). Stocker les deux ouvrirait la
 * porte a des etats incoherents impossibles a arbitrer.</p>
 *
 * <p>Le compteur est alimente automatiquement par les ventes, diminue par les
 * achats (echange de consigne) et par la casse. Aucune ecriture comptable
 * n'est produite sur les emballages.</p>
 */
@Entity
@Table(name = "restaurant_emballages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmballageBoisson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 200)
    private String libelle;

    /** Format du contenant (33CL, 65CL...), a titre descriptif. */
    @Column(length = 20)
    private String format;

    /** Boisson conditionnee. Un seul emballage par boisson (contrainte d'unicite en base). */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "article_boisson_id", nullable = false, unique = true)
    private Article articleBoisson;

    /** Nombre de bouteilles que contient un casier (24 pour le Coca 33 cl). */
    @Column(name = "contenance_casier", nullable = false)
    private Integer contenanceCasier;

    /** Stock de bouteilles vides. Seul compteur : les casiers s'en deduisent. */
    @Column(name = "bouteilles_vides", nullable = false)
    @Builder.Default
    private Integer bouteillesVides = 0;

    @Column(nullable = false)
    @Builder.Default
    private boolean actif = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Nombre de casiers complets que representent les bouteilles vides. */
    public int casiers() {
        return contenanceCasier == null || contenanceCasier <= 0 ? 0 : bouteillesVides / contenanceCasier;
    }

    /** Bouteilles restantes une fois les casiers complets retires. */
    public int bouteillesRestantes() {
        return contenanceCasier == null || contenanceCasier <= 0 ? bouteillesVides : bouteillesVides % contenanceCasier;
    }
}
