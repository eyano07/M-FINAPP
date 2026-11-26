package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.StatutReleve;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Relevé d'un établissement de trésorerie (banque ou mobile money) pour un mois, rapproché des écritures
 * de son compte au grand livre — voir {@code RapprochementService}. Montants dans la devise du compte.
 */
@Entity
@Table(name = "releves_bancaires")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReleveBancaire {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "etablissement_id")
    @ToString.Exclude
    private EtablissementTresorerie etablissement;

    @Column(nullable = false)
    private Integer mois;

    @Column(nullable = false)
    private Integer annee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Devise devise;

    @Column(name = "solde_ouverture", nullable = false, precision = 18, scale = 2)
    private BigDecimal soldeOuverture;

    @Column(name = "solde_cloture", nullable = false, precision = 18, scale = 2)
    private BigDecimal soldeCloture;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StatutReleve statut = StatutReleve.EN_COURS;

    /** Fichier d'origine (nom) ou « saisie manuelle ». */
    @Column(length = 255)
    private String source;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cree_par_id")
    @ToString.Exclude
    private User creePar;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "valide_par_id")
    @ToString.Exclude
    private User validePar;

    @Column(name = "date_validation")
    private Instant dateValidation;

    /** Dernière dé-validation (administrateur) : motif, gardé pour la piste d'audit. */
    @Column(name = "motif_devalidation", length = 500)
    private String motifDevalidation;

    @CreationTimestamp
    @Column(name = "date_creation", updatable = false)
    private Instant dateCreation;

    @OneToMany(mappedBy = "releve", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordre ASC")
    @Builder.Default
    @ToString.Exclude
    private List<LigneReleve> lignes = new ArrayList<>();
}
