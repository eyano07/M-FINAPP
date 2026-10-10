package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Paramètres du dispositif de facturation normalisée DGI (e-MCF), ligne unique. */
@Entity
@Table(name = "parametres_emcf")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParametresEmcf {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(nullable = false)
    private boolean actif;

    /** SIMULATION (dispositif factice local), TEST ou PRODUCTION (e-MCF réel). */
    @Column(nullable = false, length = 12)
    @Builder.Default
    private String mode = "SIMULATION";

    @Column(name = "url_base", length = 300)
    private String urlBase;

    @Column(name = "jeton_chiffre", length = 1000)
    private String jetonChiffre;

    @Column(name = "jeton_fin", length = 8)
    private String jetonFin;

    @Column(name = "numero_def", length = 60)
    private String numeroDef;

    @Column(name = "delai_ms", nullable = false)
    @Builder.Default
    private int delaiMs = 10000;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "modifie_par_id")
    @ToString.Exclude
    private User modifiePar;

    @Column(name = "date_maj", nullable = false)
    @Builder.Default
    private Instant dateMaj = Instant.now();
}
