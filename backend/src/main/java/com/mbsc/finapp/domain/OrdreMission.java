package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "drh_ordres_mission")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrdreMission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Format "NNN/OM/DRH/MM-YYYY", numérotation mensuelle — voir {@code OrdreMissionService.suggererNumero}. */
    @Column(nullable = false, unique = true, length = 60)
    private String numero;

    @Column(name = "lieu_mission", nullable = false, length = 200)
    private String lieuMission;

    @Column(name = "distance_ville", precision = 10, scale = 2)
    private java.math.BigDecimal distanceVille;

    @Column(length = 80)
    private String province;

    /** Subdivision administrative RDC (province > territoire), distincte de {@link #province}. */
    @Column(length = 120)
    private String territoire;

    /** Requis uniquement pour une mission collective ({@link #isCollective()}) : "Sous la supervision de
     * Monsieur/Madame X, agent de [société]..." sur le document imprimé — voir OrdreMissionPdfService. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "superviseur_employe_id")
    @ToString.Exclude
    private Employe superviseur;

    @Column(name = "superviseur_civilite", length = 10)
    @Builder.Default
    private String superviseurCivilite = "Monsieur";

    @Column(name = "but_mission", nullable = false, length = 1000)
    private String butMission;

    @Column(name = "duree_mission", length = 100)
    private String dureeMission;

    @Column(name = "date_depart", nullable = false)
    private LocalDate dateDepart;

    @Column(name = "date_retour", nullable = false)
    private LocalDate dateRetour;

    @Column(name = "moyen_transport", length = 150)
    private String moyenTransport;

    /** Texte libre, pas un montant : porté tel quel depuis l'ancien outil. */
    @Column(name = "frais_mission", length = 200)
    private String fraisMission;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "ordreMission", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    @ToString.Exclude
    private List<AgentOrdreMission> agents = new ArrayList<>();

    public void addAgent(AgentOrdreMission agent) {
        agent.setOrdreMission(this);
        this.agents.add(agent);
    }

    public boolean isCollective() {
        return agents.size() > 1;
    }
}
