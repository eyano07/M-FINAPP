package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.CategorieImmobilisation;
import com.mbsc.finapp.domain.enums.ModeAmortissement;
import com.mbsc.finapp.domain.enums.StatutImmobilisation;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Bien immobilise : valeur d'entree, plan d'amortissement et affectation.
 *
 * <p>Le triplet de comptes est porte par le bien lui-meme plutot que deduit a
 * la volee : un bien peut devoir etre impute sur un sous-compte particulier,
 * et surtout son plan d'amortissement doit rester rattache aux memes comptes
 * pendant toute sa duree de vie, meme si les valeurs par defaut de sa
 * categorie changent ensuite.</p>
 */
@Entity
@Table(name = "immobilisations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Immobilisation {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String reference;   // IM-2026-000001

    @Column(nullable = false, length = 200)
    private String libelle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CategorieImmobilisation categorie;

    @Column(length = 1000)
    private String description;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "compte_immobilisation_id")
    private CompteOHADA compteImmobilisation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "compte_amortissement_id")
    private CompteOHADA compteAmortissement;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "compte_dotation_id")
    private CompteOHADA compteDotation;

    @Column(name = "date_acquisition", nullable = false)
    private LocalDate dateAcquisition;

    /** Point de depart de l'amortissement (prorata temporis au mois). */
    @Column(name = "date_mise_service", nullable = false)
    private LocalDate dateMiseService;

    @Column(name = "valeur_acquisition", nullable = false, precision = 15, scale = 2)
    private BigDecimal valeurAcquisition;

    /** Valeur non amortissable conservee en fin de plan (0 par defaut). */
    @Column(name = "valeur_residuelle", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal valeurResiduelle = BigDecimal.ZERO;

    @Column(name = "duree_mois", nullable = false)
    private Integer dureeMois;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode_amortissement", nullable = false, length = 20)
    @Builder.Default
    private ModeAmortissement modeAmortissement = ModeAmortissement.LINEAIRE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StatutImmobilisation statut = StatutImmobilisation.EN_SERVICE;

    @Column(length = 255)
    private String localisation;

    /** Detenteur du bien, pour l'inventaire physique. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "responsable_id")
    private User responsable;

    @Column(length = 200)
    private String fournisseur;

    @Column(name = "numero_serie", length = 100)
    private String numeroSerie;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "piece_acquisition_id")
    private PieceComptable pieceAcquisition;

    @Column(name = "date_sortie")
    private LocalDate dateSortie;

    @Column(name = "valeur_cession", precision = 15, scale = 2)
    private BigDecimal valeurCession;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "piece_sortie_id")
    private PieceComptable pieceSortie;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "immobilisation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("periode ASC")
    @Builder.Default
    @ToString.Exclude
    private List<LigneAmortissement> planAmortissement = new ArrayList<>();

    public void addLigne(LigneAmortissement l) {
        l.setImmobilisation(this);
        planAmortissement.add(l);
    }

    /** Montant total a repartir sur la duree du plan. */
    public BigDecimal baseAmortissable() {
        return valeurAcquisition.subtract(valeurResiduelle);
    }

    /** Amortissements deja passes en comptabilite. */
    public BigDecimal cumulComptabilise() {
        return planAmortissement.stream()
            .filter(LigneAmortissement::isComptabilise)
            .map(LigneAmortissement::getDotation)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Valeur nette comptable a date : valeur brute moins amortissements passes. */
    public BigDecimal valeurNetteComptable() {
        return valeurAcquisition.subtract(cumulComptabilise());
    }

    public boolean estSorti() {
        return statut == StatutImmobilisation.CEDE || statut == StatutImmobilisation.REBUT;
    }
}
