package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.PrioriteNote;
import com.mbsc.finapp.domain.enums.SensTransaction;
import com.mbsc.finapp.domain.enums.StatutNote;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "notes_frais")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NoteFrais {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String reference;   // NF-2026-000123

    @Column(nullable = false, length = 200)
    private String objet;

    /** Destinataire reel du paiement — pas necessairement le createur de la note (achat aupres d'un tiers, remboursement pour un autre agent...). */
    @Column(nullable = false, length = 200)
    private String beneficiaire;

    @Column(length = 2000)
    private String description;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal montant;

    /** Devise du montant (Franc congolais par defaut). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    @Builder.Default
    private Devise devise = Devise.CDF;

    /**
     * Taux FC/USD fige au moment de la transmission en tresorerie : c'est le
     * taux auquel la depense a ete engagee. Compare au taux du jour du
     * reglement, il permet de constater l'ecart de change reel en 676/776
     * plutot que de le noyer dans le compte de charge
     * (voir {@code EcartChangeService}).
     *
     * <p>{@code null} pour une note en devise de base — aucune conversion —
     * et pour les notes anterieures a cette fonctionnalite.</p>
     */
    @Column(name = "taux_engagement", precision = 15, scale = 6)
    private BigDecimal tauxEngagement;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private StatutNote statut = StatutNote.BROUILLON;

    /**
     * DECAISSEMENT (defaut historique, circuit complet BROUILLON -> SOUMISE
     * -> VERIFIEE_DFIN -> VALIDEE_DA -> TRANSMISE_CAISSE -> PAYEE) ou
     * ENCAISSEMENT (recette de caisse : emise et executee directement par le
     * caissier, sans validation DFIN/DA -- BROUILLON -> PAYEE).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SensTransaction sens = SensTransaction.DECAISSEMENT;

    /** Priorite de paiement, definie par le DA apres validation de la note. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PrioriteNote priorite;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "createur_id")
    private User createur;   // Directeur (ou Caissier en urgence)

    /**
     * Lignes de depense de la note (1 ou plusieurs). Chaque ligne porte son
     * propre montant et son propre compte d'imputation OHADA ; le montant de
     * l'entete est la somme de ces lignes (maintenu par le service).
     */
    @OneToMany(mappedBy = "noteFrais", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordre ASC, id ASC")
    @Builder.Default
    @ToString.Exclude
    private List<LigneNoteFrais> lignes = new ArrayList<>();

    @OneToMany(mappedBy = "noteFrais", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("dateAjout ASC")
    @Builder.Default
    @ToString.Exclude
    private List<PieceJointe> piecesJointes = new ArrayList<>();

    @OneToMany(mappedBy = "noteFrais", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("dateAction ASC")
    @Builder.Default
    @ToString.Exclude
    private List<ObservationNote> observations = new ArrayList<>();

    @OneToOne(mappedBy = "noteFrais", fetch = FetchType.LAZY)
    @ToString.Exclude
    private TransactionCaisse transaction;

    @OneToOne(mappedBy = "noteFrais", fetch = FetchType.LAZY)
    @ToString.Exclude
    private TransactionBancaire transactionBancaire;

    @OneToOne(mappedBy = "noteFrais", fetch = FetchType.LAZY)
    @ToString.Exclude
    private TransactionMobileMoney transactionMobileMoney;

    @CreationTimestamp
    @Column(name = "date_creation", updatable = false)
    private Instant dateCreation;

    @UpdateTimestamp
    @Column(name = "date_maj")
    private Instant dateMaj;

    public void addObservation(ObservationNote obs) {
        obs.setNoteFrais(this);
        this.observations.add(obs);
    }

    public void addPieceJointe(PieceJointe pj) {
        pj.setNoteFrais(this);
        this.piecesJointes.add(pj);
    }

    public void addLigne(LigneNoteFrais ligne) {
        ligne.setNoteFrais(this);
        this.lignes.add(ligne);
    }

    /** Remplace toutes les lignes (utilise par la modification en BROUILLON). */
    public void remplacerLignes(List<LigneNoteFrais> nouvelles) {
        this.lignes.clear();
        for (LigneNoteFrais l : nouvelles) {
            addLigne(l);
        }
    }

    /**
     * Recalcule le montant total (TTC) de l'entete a partir des lignes : ce
     * qui sera reellement decaisse, pas la seule somme des montants HT
     * saisis (voir {@link LigneNoteFrais#montantTtc()}).
     */
    public void recalculerMontant() {
        this.montant = this.lignes.stream()
            .map(LigneNoteFrais::montantTtc)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * true si la note a deja ete reglee par un canal de tresorerie
     * quelconque (caisse, banque ou mobile money). Un seul canal peut
     * payer une note donnee ; ce garde-fou est verifie par les trois
     * services de paiement avant de creer une nouvelle transaction.
     */
    public boolean estDejaReglee() {
        return transaction != null || transactionBancaire != null || transactionMobileMoney != null;
    }
}
