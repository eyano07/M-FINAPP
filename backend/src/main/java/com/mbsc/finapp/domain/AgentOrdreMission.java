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

    /** Facultatif : absent pour une personne externe (voir {@link #nomLibre}). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employe_id")
    @ToString.Exclude
    private Employe employe;

    /** Nom complet d'une personne externe (partenaire, entrepreneur...) sans compte employé — ignoré si {@link #employe} est renseigné. */
    @Column(name = "nom_libre", length = 150)
    private String nomLibre;

    @Column(length = 60)
    private String nationalite;

    @Column(name = "numero_passeport", length = 60)
    private String numeroPasseport;

    @Column(name = "fonction_mission", length = 150)
    private String fonctionMission;

    /** "Monsieur"/"Madame" — accord grammatical du document imprimé. */
    @Column(length = 10)
    @Builder.Default
    private String civilite = "Monsieur";

    /** Nom complet effectif : celui de l'employé lié, sinon {@link #nomLibre}. */
    public String nomAffiche() {
        return employe != null ? employe.getNomComplet() : nomLibre;
    }
}
