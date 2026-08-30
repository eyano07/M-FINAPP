package com.mbsc.finapp.domain;

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

    @Column(name = "logo_chemin_stockage", length = 500)
    private String logoCheminStockage;

    @Column(name = "logo_type_mime", length = 100)
    private String logoTypeMime;

    @UpdateTimestamp
    @Column(name = "date_maj")
    private Instant dateMaj;
}
