package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Registre des factures normalisées : une ligne par facture de vente (ou avoir) transmise au dispositif DGI,
 * avec la réponse fiscale (UID, signature, date fiscale, contenu du code QR) et l'état de la transmission.
 */
@Entity
@Table(name = "factures_normalisees")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FactureNormalisee {

    public enum Type { VENTE, AVOIR }

    /** EN_ATTENTE : à (re)transmettre ; CERTIFIEE : signée par le dispositif ; REJETEE : refus définitif ; ANNULEE : jamais certifiée puis annulée. */
    public enum Statut { EN_ATTENTE, CERTIFIEE, REJETEE, ANNULEE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "vente_id")
    @ToString.Exclude
    private Vente vente;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Type type;

    /** Facture d'origine d'un avoir. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "origine_id")
    @ToString.Exclude
    private FactureNormalisee origine;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Statut statut;

    /** Mode du dispositif au moment de la certification (SIMULATION n'a aucune valeur fiscale). */
    @Column(length = 12)
    private String mode;

    @Column(length = 120)
    private String uid;

    @Column(length = 500)
    private String signature;

    @Column(name = "numero_def", length = 60)
    private String numeroDef;

    @Column(name = "date_fiscale")
    private Instant dateFiscale;

    @Column(name = "code_qr", columnDefinition = "text")
    private String codeQr;

    @Column(columnDefinition = "text")
    private String requete;

    @Column(columnDefinition = "text")
    private String reponse;

    @Column(nullable = false)
    private int tentatives;

    @Column(name = "derniere_erreur", length = 500)
    private String derniereErreur;

    @Column(name = "prochaine_tentative")
    private Instant prochaineTentative;

    @Column(name = "date_creation", nullable = false, updatable = false)
    @Builder.Default
    private Instant dateCreation = Instant.now();

    @Column(name = "date_maj", nullable = false)
    @Builder.Default
    private Instant dateMaj = Instant.now();
}
