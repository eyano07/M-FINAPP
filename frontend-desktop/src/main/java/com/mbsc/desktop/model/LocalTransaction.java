package com.mbsc.desktop.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Transaction de caisse enregistree localement (H2).
 * Le champ uuid est la cle d'identite stable partagee avec PostgreSQL :
 * c'est sur lui que repose l'idempotence de la synchronisation.
 */
@Entity
@Table(name = "transaction_caisse")
public class LocalTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false, length = 36)
    private String uuid = UUID.randomUUID().toString();

    @Column(nullable = false, unique = true, length = 30)
    private String reference;

    /** Reference de la note de frais reglee (optionnel). */
    @Column(length = 30)
    private String noteReference;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal montant;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SensTransaction sens;

    @Column(length = 30)
    private String numeroRecu;

    /** E-mail du caissier (resolu cote serveur). */
    @Column(length = 150)
    private String caissierEmail;

    @Column(nullable = false)
    private Instant dateOperation = Instant.now();

    /** true tant que la transaction n'a pas ete confirmee par le serveur. */
    @Column(nullable = false)
    private boolean synced = false;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LocalEcriture> ecritures = new ArrayList<>();

    public LocalTransaction() {}

    public void addEcriture(LocalEcriture e) {
        e.setTransaction(this);
        this.ecritures.add(e);
    }

    // Getters / setters
    public Long getId() { return id; }
    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public String getNoteReference() { return noteReference; }
    public void setNoteReference(String noteReference) { this.noteReference = noteReference; }
    public BigDecimal getMontant() { return montant; }
    public void setMontant(BigDecimal montant) { this.montant = montant; }
    public SensTransaction getSens() { return sens; }
    public void setSens(SensTransaction sens) { this.sens = sens; }
    public String getNumeroRecu() { return numeroRecu; }
    public void setNumeroRecu(String numeroRecu) { this.numeroRecu = numeroRecu; }
    public String getCaissierEmail() { return caissierEmail; }
    public void setCaissierEmail(String caissierEmail) { this.caissierEmail = caissierEmail; }
    public Instant getDateOperation() { return dateOperation; }
    public void setDateOperation(Instant dateOperation) { this.dateOperation = dateOperation; }
    public boolean isSynced() { return synced; }
    public void setSynced(boolean synced) { this.synced = synced; }
    public List<LocalEcriture> getEcritures() { return ecritures; }
    public void setEcritures(List<LocalEcriture> ecritures) { this.ecritures = ecritures; }
}
