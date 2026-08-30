package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.StatutPiece;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "pieces_comptables")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PieceComptable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String reference;

    @Column(name = "date_piece", nullable = false)
    private LocalDate datePiece;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private JournalComptable journal;

    @Column(length = 255)
    private String libelle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatutPiece statut;

    @Column(name = "total_debit", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal totalDebit = BigDecimal.ZERO;

    @Column(name = "total_credit", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal totalCredit = BigDecimal.ZERO;

    /**
     * true = reprise des a-nouveaux (« solde d'ouverture »). Les ecritures de
     * la piece alimentent alors les colonnes « soldes d'ouverture » de la
     * balance et sont exclues des mouvements de la periode, meme si la piece
     * est datee a l'interieur de celle-ci (cas normal : 1er janvier).
     */
    @Column(name = "solde_ouverture", nullable = false)
    @Builder.Default
    private boolean soldeOuverture = false;

    /**
     * true = ecriture de reevaluation latente des soldes en devise etrangere
     * (ecarts de conversion 478/479, cf. audit du 18/08/2026, A-01). Une telle
     * piece n'est jamais definitive : elle doit etre reversee au debut de la
     * periode suivante avant toute nouvelle reevaluation, faute de quoi les
     * comptes de tiers concernes resteraient distordus indefiniment.
     */
    @Column(name = "reevaluation_devise", nullable = false)
    @Builder.Default
    private boolean reevaluationDevise = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    @ToString.Exclude
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Pièce d'origine en cas d'extourne. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "piece_origine_id")
    @ToString.Exclude
    private PieceComptable pieceOrigine;

    @OneToMany(mappedBy = "piece", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    private List<EcritureGrandLivre> lignes = new ArrayList<>();

    public void addLigne(EcritureGrandLivre ligne) {
        ligne.setPiece(this);
        this.lignes.add(ligne);
    }
}
