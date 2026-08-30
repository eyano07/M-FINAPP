package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Agent externe/temporaire figurant sur la fiche de présence manuelle
 * imprimable (module DRH_PRESENCES), délibérément indépendant d'{@link Employe}.
 */
@Entity
@Table(name = "drh_agents_presence_manuelle")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentPresenceManuelle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nom_complet", nullable = false, length = 160)
    private String nomComplet;

    @Column(length = 120)
    private String fonction;

    @Column(name = "ordre_affichage", nullable = false)
    @Builder.Default
    private Integer ordreAffichage = 0;
}
