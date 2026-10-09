package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.ModeleEntete;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Identite visuelle et administrative de l'entreprise (ligne unique en
 * base). Affichee dans l'entete, la page de connexion et les documents
 * imprimes (notes de frais, recus, ventes...).
 */
@Entity
@Table(name = "parametres_entreprise")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParametresEntreprise {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String nom;

    /** Nom complet si {@link #nom} est un sigle/une abreviation (ex. "MBSC" -> raison sociale complete). */
    @Column(name = "nom_complet", length = 255)
    private String nomComplet;

    @Column(length = 255)
    private String slogan;

    @Column(length = 255)
    private String adresse;

    @Column(length = 30)
    private String telephone;

    @Column(length = 255)
    private String email;

    /** Numero de Registre du Commerce et du Credit Mobilier. */
    @Column(length = 80)
    private String rccm;

    @Column(name = "id_nat", length = 80)
    private String idNat;

    /** Numero d'Identification Fiscale. */
    @Column(length = 40)
    private String nif;

    @Column(name = "logo_chemin_stockage", length = 500)
    private String logoCheminStockage;

    @Column(name = "logo_type_mime", length = 100)
    private String logoTypeMime;

    /** Couleur de marque (hex, ex. "#15803D") appliquee au theme Vuetify et aux accents du frontend. */
    @Column(name = "couleur_primaire", nullable = false, length = 7)
    @Builder.Default
    private String couleurPrimaire = "#15803D";

    /** Modèle de papier à en-tête de tous les documents (PDF et impressions). */
    @Enumerated(EnumType.STRING)
    @Column(name = "modele_entete", nullable = false, length = 20)
    @Builder.Default
    private ModeleEntete modeleEntete = ModeleEntete.CLASSIQUE;

    /**
     * Régime de TVA : {@code false} = entreprise non assujettie, aucune TVA facturée ni
     * récupérée (le taux appliqué vaut 0 — voir TauxTvaService). Défini par l'administrateur.
     */
    @Column(name = "assujetti_tva", nullable = false)
    @Builder.Default
    private boolean assujettiTva = true;

    @UpdateTimestamp
    @Column(name = "date_maj")
    private Instant dateMaj;
}
