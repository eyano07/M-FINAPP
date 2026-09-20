package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Une ligne de la fiche technique d'un plat : la quantite d'une provision
 * necessaire pour UNE portion.
 *
 * <p>La fiche n'a pas d'entite d'en-tete : un plat n'en a qu'une seule, et
 * l'ensemble de ses lignes la constitue. Elle sert de modele a la saisie d'une
 * {@link Production}, qui reste libre de s'en ecarter (perte, substitution) —
 * c'est la production, jamais la fiche, qui fait foi sur le stock.</p>
 */
@Entity
@Table(name = "lignes_recette")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LigneRecette {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Le plat produit (TypeArticle.PLAT). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plat_id", nullable = false)
    @ToString.Exclude
    private Article plat;

    /** L'ingredient consomme (TypeArticle.PROVISION). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provision_id", nullable = false)
    @ToString.Exclude
    private Article provision;

    /** Quantite pour une seule portion, dans l'unite de mesure de la provision. */
    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal quantite;
}
