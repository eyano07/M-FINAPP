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

/** Operation mobile money (encaissement/decaissement), miroir de {@link TransactionCaisse}. */
@Entity
@Table(name = "transactions_mobile_money")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionMobileMoney {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private UUID uuid;

    @Column(nullable = false, unique = true, length = 30)
    private String reference;   // TRM-2026-000045

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
    @JoinColumn(name = "operateur_id")
    private User operateur;

    @Column(name = "numero_recu", length = 30)
    private String numeroRecu;

    @Column(length = 255)
    private String libelle;

    @Column(name = "date_operation")
    private Instant dateOperation;

    @CreationTimestamp
    @Column(name = "date_enregistrement", updatable = false)
    private Instant dateEnregistrement;

    /** Taux de change (FC pour 1 USD) en vigueur au moment de l'operation. */
    @Column(name = "taux_journalier", precision = 15, scale = 6)
    private BigDecimal tauxJournalier;

    /** Operateur mobile money utilise (porte son propre compte de tresorerie). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "etablissement_id")
    @ToString.Exclude
    private EtablissementTresorerie etablissement;

    @OneToMany(mappedBy = "transactionMobileMoney", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    private List<EcritureGrandLivre> ecritures = new ArrayList<>();

    public void addEcriture(EcritureGrandLivre e) {
        e.setTransactionMobileMoney(this);
        this.ecritures.add(e);
    }
}
