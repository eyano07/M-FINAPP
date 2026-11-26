package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Téléphone inscrit aux notifications push (V148) : un jeton Firebase Cloud Messaging et le compte qui l'utilise. */
@Entity
@Table(name = "appareils_push")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppareilPush {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "utilisateur_id")
    private User utilisateur;

    @Column(nullable = false, unique = true, length = 512)
    private String jeton;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String plateforme = "ANDROID";

    @Column(name = "date_creation", nullable = false)
    @Builder.Default
    private Instant dateCreation = Instant.now();

    /** Dernière inscription du jeton (à chaque ouverture de l'application). */
    @Column(name = "date_vue", nullable = false)
    @Builder.Default
    private Instant dateVue = Instant.now();
}
