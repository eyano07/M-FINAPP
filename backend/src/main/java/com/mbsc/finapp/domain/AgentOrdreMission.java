package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "drh_agents_ordre_mission")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentOrdreMission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ordre_mission_id", nullable = false)
    @ToString.Exclude
    private OrdreMission ordreMission;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employe_id", nullable = false)
    @ToString.Exclude
    private Employe employe;

    @Column(name = "fonction_mission", length = 150)
    private String fonctionMission;

    /** "Monsieur"/"Madame" — accord grammatical du document imprimé. */
    @Column(length = 10)
    @Builder.Default
    private String civilite = "Monsieur";
}
