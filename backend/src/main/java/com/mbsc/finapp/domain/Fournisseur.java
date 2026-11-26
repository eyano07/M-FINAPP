package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Fournisseur du répertoire (V141).
 *
 * <p>Les achats (lots de stock du restaurant et de la logistique, immobilisations) gardent le nom du fournisseur en
 * texte libre : la fiche les retrouve par ce nom. Une fiche à laquelle des achats renvoient s'archive
 * ({@code actif = false}) au lieu d'être supprimée.</p>
 */
@Entity
@Table(name = "fournisseurs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Fournisseur {

    /** Compte collectif des fournisseurs (SYSCOHADA), sous lequel chaque fiche reçoit son compte (40111, 40112…). */
    public static final String COMPTE_PAR_DEFAUT = "4011";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FRN-0001… attribué à la création. */
    @Column(unique = true, length = 20)
    private String code;

    /** Nom ou raison sociale, unique (casse et espaces ignorés). */
    @Column(nullable = false, length = 200)
    private String nom;

    /** Personne à contacter chez le fournisseur. */
    @Column(length = 150)
    private String contact;

    @Column(length = 40)
    private String telephone;

    @Column(length = 150)
    private String email;

    @Column(length = 255)
    private String adresse;

    /** Numéro d'impôt (NIF). */
    @Column(length = 40)
    private String nif;

    /** Registre du commerce et du crédit mobilier (RCCM). */
    @Column(length = 60)
    private String rccm;

    /** Compte propre du fournisseur (4011…), ouvert par l'application à la création de la fiche (V142). */
    @Column(name = "compte_comptable", nullable = false, unique = true, length = 20)
    private String compteComptable;

    @Column(nullable = false)
    @Builder.Default
    private boolean actif = true;

    @CreationTimestamp
    @Column(name = "date_creation", nullable = false, updatable = false)
    private Instant dateCreation;

    @UpdateTimestamp
    @Column(name = "date_modification")
    private Instant dateModification;
}
