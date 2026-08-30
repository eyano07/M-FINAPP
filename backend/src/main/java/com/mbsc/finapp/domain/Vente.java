package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.ModeReglement;
import com.mbsc.finapp.domain.enums.StatutVente;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Vente de marchandises et/ou de services.
 *
 * <p>Une vente validee genere jusqu'a deux pieces comptables : la piece
 * du journal VENTES (recette et TVA collectee), toujours ; et, si elle
 * comporte des marchandises, la piece du journal STOCK produite par le
 * mouvement de sortie (cout des ventes au CMUP).</p>
 */
@Entity
@Table(name = "ventes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Vente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String reference;   // VTE-2026-000045

    @Column(name = "date_vente", nullable = false)
    private LocalDate dateVente;

    /** Client du repertoire, ou null si seul un nom libre a ete saisi. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    @ToString.Exclude
    private Client client;

    /** Nom libre, utilise quand la vente n'est rattachee a aucune fiche client. */
    @Column(name = "client_nom", length = 200)
    private String clientNom;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StatutVente statut = StatutVente.BROUILLON;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode_reglement", nullable = false, length = 20)
    private ModeReglement modeReglement;

    /**
     * Devise de saisie : les prix unitaires et les totaux de cette vente sont
     * exprimes dans cette devise, pas dans la devise de base.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    @Builder.Default
    private Devise devise = Devise.CDF;

    /**
     * Taux de change (FC pour 1 USD) fige a la saisie. Il sert a convertir la
     * vente vers la devise de base a la validation, et a reafficher une vente
     * en FC dans l'autre devise sans dependre du taux courant.
     */
    @Column(name = "taux_journalier", precision = 15, scale = 6)
    private BigDecimal tauxJournalier;

    /** Banque ou operateur encaisseur, obligatoire pour un reglement BANQUE / MOBILE_MONEY. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "etablissement_id")
    @ToString.Exclude
    private EtablissementTresorerie etablissement;

    /** Entrepot de sortie, obligatoire des qu'une ligne porte une marchandise. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entrepot_id")
    @ToString.Exclude
    private Entrepot entrepot;

    @Column(name = "total_ht", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal totalHt = BigDecimal.ZERO;

    @Column(name = "total_tva", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal totalTva = BigDecimal.ZERO;

    @Column(name = "total_ttc", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal totalTtc = BigDecimal.ZERO;

    /** Taux de TVA fige a la validation (piste d'audit). */
    @Column(name = "taux_tva_applique", precision = 5, scale = 2)
    private BigDecimal tauxTvaApplique;

    /** Piece du journal VENTES (recette + TVA). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "piece_id")
    @ToString.Exclude
    private PieceComptable piece;

    /** Mouvement de sortie de stock (cout des ventes), null pour une vente de services seuls. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mouvement_id")
    @ToString.Exclude
    private MouvementStock mouvement;

    /**
     * Piece de reglement de la creance client (vente CREDIT uniquement).
     * {@code null} tant que la creance reste ouverte. Rempli en une seule
     * fois a l'encaissement total — comme le paiement d'une note de frais,
     * pas de reglement partiel multi-echeances.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "piece_reglement_id")
    @ToString.Exclude
    private PieceComptable pieceReglement;

    @Column(name = "date_reglement")
    private LocalDate dateReglement;

    /** Taux applique a l'encaissement (audit + calcul de l'ecart de change realise). */
    @Column(name = "taux_reglement", precision = 15, scale = 6)
    private BigDecimal tauxReglement;

    /**
     * Cumul des ecarts de reevaluation latents (478/479) encore non reverses,
     * portes sur cette creance par une cloture annuelle (voir
     * {@code ClotureAnnuelleService}). Le solde reellement du au moment du
     * reglement est le montant initialement facture PLUS ce cumul — sans
     * quoi une reevaluation posee sur une piece distincte de la vente
     * laisserait un residu bloque sur le compte client apres reglement.
     */
    @Column(name = "ecart_latent_cumule", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal ecartLatentCumule = BigDecimal.ZERO;

    /**
     * Piece de reevaluation qui porte le cumul ci-dessus, ou {@code null} si
     * la creance n'a jamais ete revaluee. Individuelle a cette vente (pas
     * partagee entre plusieurs creances revaluees a la meme cloture) : c'est
     * ce qui permet a la cloture suivante de ne reverser que les
     * reevaluations dont la creance est encore ouverte, sans toucher a
     * celles d'une vente entre-temps reglee (voir {@code ClotureAnnuelleService}).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "piece_reevaluation_id")
    @ToString.Exclude
    private PieceComptable pieceReevaluation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    @ToString.Exclude
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "vente", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordre ASC, id ASC")
    @Builder.Default
    @ToString.Exclude
    private List<LigneVente> lignes = new ArrayList<>();

    public void addLigne(LigneVente ligne) {
        ligne.setVente(this);
        this.lignes.add(ligne);
    }

    /** Libelle du client, quelle que soit la facon dont il a ete renseigne. */
    public String designationClient() {
        if (client != null) {
            return client.getNom();
        }
        return clientNom == null || clientNom.isBlank() ? "Client comptoir" : clientNom;
    }

    /** true si la creance est soldee (ou si la vente n'en genere pas). */
    public boolean estReglee() {
        return modeReglement != ModeReglement.CREDIT || pieceReglement != null;
    }
}
