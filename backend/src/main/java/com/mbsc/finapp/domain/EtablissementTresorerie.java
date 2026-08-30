package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.TypeEtablissement;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Banque ou operateur mobile money par lequel transitent les operations
 * de tresorerie.
 *
 * <p>Chaque etablissement possede son propre sous-compte OHADA
 * (banques sous 521, mobile money sous 582) : le solde par etablissement
 * se calcule donc directement depuis le grand livre, sans compteur
 * denormalise a maintenir.</p>
 */
@Entity
@Table(name = "etablissements_tresorerie")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EtablissementTresorerie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String nom;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TypeEtablissement type;

    /** Sous-compte OHADA dedie : porte le solde propre a l'etablissement. */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "compte_id", nullable = false, unique = true)
    private CompteOHADA compte;

    @Column(nullable = false)
    @Builder.Default
    private boolean actif = true;

    @CreationTimestamp
    @Column(name = "date_creation", updatable = false)
    private Instant dateCreation;
}
