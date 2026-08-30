package com.mbsc.desktop.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * File d'attente de synchronisation (pattern Outbox).
 * Chaque operation realisee hors-ligne y depose une ligne ; le SyncEngine
 * la rejoue vers le backend des le retour de la connexion, de maniere idempotente.
 */
@Entity
@Table(name = "sync_outbox")
public class SyncOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UUID de l'entite concernee (cle d'idempotence). */
    @Column(nullable = false, length = 36)
    private String entiteUuid;

    /** Type logique : "TRANSACTION". */
    @Column(nullable = false, length = 40)
    private String typeEntite;

    /** Operation : "CREATE". */
    @Column(nullable = false, length = 20)
    private String operation;

    /** Payload JSON serialise a transmettre. */
    @Lob
    @Column(nullable = false)
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SyncStatus statut = SyncStatus.PENDING;

    @Column(nullable = false)
    private int tentatives = 0;

    @Column(length = 500)
    private String derniereErreur;

    @Column(nullable = false)
    private Instant dateCreation = Instant.now();

    private Instant dateEnvoi;

    public SyncOutbox() {}

    public SyncOutbox(String entiteUuid, String typeEntite, String operation, String payloadJson) {
        this.entiteUuid = entiteUuid;
        this.typeEntite = typeEntite;
        this.operation = operation;
        this.payloadJson = payloadJson;
    }

    public void incrementTentatives(String erreur) {
        this.tentatives++;
        this.derniereErreur = erreur;
    }

    public Long getId() { return id; }
    public String getEntiteUuid() { return entiteUuid; }
    public void setEntiteUuid(String entiteUuid) { this.entiteUuid = entiteUuid; }
    public String getTypeEntite() { return typeEntite; }
    public void setTypeEntite(String typeEntite) { this.typeEntite = typeEntite; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public SyncStatus getStatut() { return statut; }
    public void setStatut(SyncStatus statut) { this.statut = statut; }
    public int getTentatives() { return tentatives; }
    public void setTentatives(int tentatives) { this.tentatives = tentatives; }
    public String getDerniereErreur() { return derniereErreur; }
    public void setDerniereErreur(String derniereErreur) { this.derniereErreur = derniereErreur; }
    public Instant getDateCreation() { return dateCreation; }
    public Instant getDateEnvoi() { return dateEnvoi; }
    public void setDateEnvoi(Instant dateEnvoi) { this.dateEnvoi = dateEnvoi; }
}
