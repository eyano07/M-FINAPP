package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.CanalRemboursement;
import com.mbsc.finapp.domain.enums.StatutRetourVente;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Retour d'une vente deja payee, avec remboursement du client (V134) : le caissier demande (lignes et quantites rendues,
 * motif), le responsable du bar, de la cuisine ou du restaurant approuve ou refuse, puis le caissier rembourse. Le
 * remboursement extourne la part rendue de la vente (piece miroir), remet en stock la marchandise intacte et reprend le
 * vide d'une bouteille consignee — voir {@code RetourVenteService}.
 */
@Entity
@Table(name = "retours_vente")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetourVente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String reference;   // RV-2026-000001

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "vente_id")
    @ToString.Exclude
    private Vente vente;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StatutRetourVente statut = StatutRetourVente.DEMANDE;

    @Column(nullable = false, length = 500)
    private String motif;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "demandeur_id")
    @ToString.Exclude
    private User demandeur;

    @Column(name = "date_demande", nullable = false)
    @Builder.Default
    private Instant dateDemande = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approbateur_id")
    @ToString.Exclude
    private User approbateur;

    @Column(name = "date_decision")
    private Instant dateDecision;

    @Column(name = "commentaire_decision", length = 500)
    private String commentaireDecision;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "caissier_id")
    @ToString.Exclude
    private User caissier;

    @Column(name = "date_remboursement")
    private LocalDate dateRemboursement;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CanalRemboursement canal;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "etablissement_id")
    @ToString.Exclude
    private EtablissementTresorerie etablissement;

    /** Part rendue, dans la devise de la vente. */
    @Column(name = "montant_ht", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal montantHt = BigDecimal.ZERO;

    @Column(name = "montant_tva", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal montantTva = BigDecimal.ZERO;

    @Column(name = "montant_ttc", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal montantTtc = BigDecimal.ZERO;

    /** Piece du remboursement (journal VENTES, miroir de la piece de vente pour la part rendue). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "piece_id")
    @ToString.Exclude
    private PieceComptable piece;

    /** Reflet analytique inverse de la part rendue (classe 9). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "piece_analytique_id")
    @ToString.Exclude
    private PieceComptable pieceAnalytique;

    /** Deux caissiers ou deux responsables qui agissent en meme temps ne decident pas deux fois. */
    @Version
    @Column(nullable = false)
    @Builder.Default
    private long version = 0L;

    @OneToMany(mappedBy = "retour", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @Builder.Default
    @ToString.Exclude
    private List<LigneRetourVente> lignes = new ArrayList<>();

    public void addLigne(LigneRetourVente ligne) {
        ligne.setRetour(this);
        this.lignes.add(ligne);
    }
}
