package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.TypeCompte;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "comptes_ohada")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompteOHADA {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String numero;   // ex: 6011, 401, 571

    @Column(nullable = false, length = 200)
    private String libelle;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TypeCompte type;

    @Column(name = "classe")
    private Integer classe;  // 1..8 classes OHADA

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private CompteOHADA parent;

    /** true = compte ajouté manuellement (peut être supprimé). */
    @Column(nullable = false)
    @Builder.Default
    private boolean manuel = false;

    /**
     * true = compte de saisie ; false = compte de regroupement (classe,
     * sous-classe) sur lequel toute imputation directe est refusée,
     * comme dans QuickBooks (comptes parents) et Sage.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean imputable = true;

    /** Désactivation à la QuickBooks (« make inactive ») au lieu d'une suppression. */
    @Column(nullable = false)
    @Builder.Default
    private boolean actif = true;

    // ------------------------------------------------------------------
    // Rubriques du référentiel SYSCOHADA commenté (migration V23).
    // Renseignées sur les comptes principaux du plan officiel ; nulles sur
    // les subdivisions et sur les comptes propres à l'entreprise.
    // ------------------------------------------------------------------

    /** Ce que le compte enregistre. */
    @Column(columnDefinition = "TEXT")
    private String contenu;

    /** Précisions d'application du référentiel. */
    @Column(columnDefinition = "TEXT")
    private String commentaires;

    /** Règles de débit / crédit du compte. */
    @Column(columnDefinition = "TEXT")
    private String fonctionnement;

    /** Ce que le compte ne doit pas enregistrer, et vers quel compte se reporter. */
    @Column(columnDefinition = "TEXT")
    private String exclusions;

    /** Pièces justificatives permettant de contrôler le compte. */
    @Column(columnDefinition = "TEXT")
    private String controle;

    /** true si le compte porte au moins une rubrique du référentiel commenté. */
    public boolean estCommente() {
        return contenu != null || commentaires != null || fonctionnement != null
            || exclusions != null || controle != null;
    }
}
