package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.SituationFamiliale;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Employé MBSC (module DRH_PERSONNEL).
 *
 * <p>{@code salaireBaseUsd} porte le SALAIRE BRUT (R) au sens du formulaire
 * DEBOURS MBSC 2026, pas un salaire de base au sens strict — voir
 * {@code PayrollCalculationService} pour la formule complète qui en dérive
 * les indemnités, cotisations et net à payer.</p>
 *
 * <p>{@code conforme} pilote le document généré à la clôture d'un bulletin :
 * bulletin de paie complet si vrai, simple reçu de paiement si faux (agent
 * non déclaré/non standard). {@code superviseur} conditionne l'éligibilité
 * aux rotations de sites (module DRH_MISSIONS). {@code expatrie} route les
 * écritures comptables de paie vers les comptes "personnel non national"
 * (6621/6642) au lieu de "national" (6611/6641).</p>
 */
@Entity
@Table(name = "drh_employes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Employe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String matricule;

    @Column(name = "nom_complet", nullable = false, length = 150)
    private String nomComplet;

    /** Numéro CNSS + lettre de catégorie, texte libre (ex. "11986724408 S"). */
    @Column(length = 30)
    private String categorie;

    @Column(length = 100)
    private String affectation;

    @Column(length = 150)
    private String email;

    @Column(length = 30)
    private String telephone;

    @Column(name = "date_embauche")
    private LocalDate dateEmbauche;

    @Column(name = "salaire_base_usd", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal salaireBaseUsd = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "situation_familiale", length = 20)
    private SituationFamiliale situationFamiliale;

    @Column(name = "nombre_enfants", nullable = false)
    @Builder.Default
    private Integer nombreEnfants = 0;

    @Column(length = 100)
    private String diplome;

    @Column(name = "anciennete_annees", nullable = false)
    @Builder.Default
    private Integer ancienneteAnnees = 0;

    @Column(name = "rendement_pct", nullable = false, precision = 6, scale = 2)
    @Builder.Default
    private BigDecimal rendementPct = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private boolean conforme = true;

    @Column(nullable = false)
    @Builder.Default
    private boolean superviseur = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean expatrie = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean actif = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
