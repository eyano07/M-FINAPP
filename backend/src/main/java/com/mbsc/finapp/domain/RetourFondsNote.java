package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.StatutRetourFonds;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Demande de retour de fonds sur une note de frais deja payee : l'emetteur reduit sa note (montant d'une ligne, ou
 * quantite d'un achat de marchandise), le caissier valide quand il recoit l'argent en especes, ou refuse. Voir
 * {@code RetourFondsService} (V127).
 */
@Entity
@Table(name = "retours_fonds_note")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetourFondsNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "note_id")
    @ToString.Exclude
    private NoteFrais note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StatutRetourFonds statut = StatutRetourFonds.EN_ATTENTE;

    @Column(nullable = false, length = 500)
    private String motif;

    /** Montant rendu, TTC, dans la devise de la note. */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal montant;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "demandeur_id")
    @ToString.Exclude
    private User demandeur;

    @CreationTimestamp
    @Column(name = "date_demande", nullable = false, updatable = false)
    private Instant dateDemande;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "caissier_id")
    @ToString.Exclude
    private User caissier;

    @Column(name = "date_decision")
    private Instant dateDecision;

    @Column(name = "motif_refus", length = 500)
    private String motifRefus;

    /** Encaissement du retour en caisse, renseigne a la validation. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    @ToString.Exclude
    private TransactionCaisse transaction;

    @OneToMany(mappedBy = "retour", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @Builder.Default
    @ToString.Exclude
    private List<LigneRetourFonds> lignes = new ArrayList<>();

    public void addLigne(LigneRetourFonds ligne) {
        ligne.setRetour(this);
        this.lignes.add(ligne);
    }
}
