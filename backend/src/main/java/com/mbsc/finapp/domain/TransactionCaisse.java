package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.SensTransaction;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "transactions_caisse")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionCaisse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Identite stable inter-bases (H2 <-> PostgreSQL), cle d'idempotence de synchro. */
    @Column(nullable = false, unique = true, updatable = false)
    private UUID uuid;

    @Column(nullable = false, unique = true, length = 30)
    private String reference;   // TRX-2026-000045

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "note_id")
    @ToString.Exclude
    private NoteFrais noteFrais;   // optionnel

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal montant;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SensTransaction sens;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "caissier_id")
    private User caissier;

    @Column(name = "numero_recu", length = 30)
    private String numeroRecu;

    @Column(length = 255)
    private String libelle;

    @Column(name = "date_operation")
    private Instant dateOperation;   // horodatage cote caissier

    @CreationTimestamp
    @Column(name = "date_enregistrement", updatable = false)
    private Instant dateEnregistrement;

    /** Taux de change (FC pour 1 USD) en vigueur au moment de l'operation. */
    @Column(name = "taux_journalier", precision = 15, scale = 6)
    private BigDecimal tauxJournalier;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    private List<EcritureGrandLivre> ecritures = new ArrayList<>();

    public void addEcriture(EcritureGrandLivre e) {
        e.setTransaction(this);
        this.ecritures.add(e);
    }
}
