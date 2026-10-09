package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Paramètres de l'IA (ligne unique) : clés OpenAI et Anthropic chiffrées, modèle choisi pour chacun et état
 * du crédit Anthropic. Voir {@code ParametresIaService} et {@code IaRouteur}.
 */
@Entity
@Table(name = "parametres_ia")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParametresIa {

    public static final long SINGLETON_ID = 1L;
    public static final String MODELE_OPENAI_DEFAUT = "o4-mini";
    public static final String MODELE_ANTHROPIC_DEFAUT = "claude-sonnet-5-5";

    @Id
    private Long id;

    @Column(name = "openai_cle_chiffree", length = 1000)
    private String openaiCleChiffree;

    /** Derniers caractères de la clé, pour l'affichage masqué. */
    @Column(name = "openai_cle_fin", length = 8)
    private String openaiCleFin;

    @Column(name = "openai_modele", nullable = false, length = 100)
    @Builder.Default
    private String openaiModele = MODELE_OPENAI_DEFAUT;

    @Column(name = "anthropic_cle_chiffree", length = 1000)
    private String anthropicCleChiffree;

    @Column(name = "anthropic_cle_fin", length = 8)
    private String anthropicCleFin;

    @Column(name = "anthropic_modele", nullable = false, length = 100)
    @Builder.Default
    private String anthropicModele = MODELE_ANTHROPIC_DEFAUT;

    /** Premier refus d'Anthropic pour crédit épuisé ; null tant que le crédit est utilisable. */
    @Column(name = "anthropic_epuise_depuis")
    private Instant anthropicEpuiseDepuis;

    /** Premier refus d'Anthropic pour clé invalide ; null si la clé est acceptée. */
    @Column(name = "anthropic_refusee_depuis")
    private Instant anthropicRefuseeDepuis;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "modifie_par_id")
    @ToString.Exclude
    private User modifiePar;

    @Column(name = "date_maj", nullable = false)
    @Builder.Default
    private Instant dateMaj = Instant.now();
}
