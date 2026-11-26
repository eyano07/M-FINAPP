package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Jeton d'un widget de l'écran d'accueil Android (V149) : remis au téléphone à la connexion dans l'application, il ne
 * donne accès qu'au résumé du compte ({@code /widget/**}). Seule son empreinte SHA-256 est conservée.
 */
@Entity
@Table(name = "jetons_widget")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JetonWidget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "utilisateur_id")
    private User utilisateur;

    @Column(nullable = false, unique = true, length = 64)
    private String empreinte;

    @Column(name = "date_creation", nullable = false)
    @Builder.Default
    private Instant dateCreation = Instant.now();

    /** Dernière lecture du résumé par le widget. */
    @Column(name = "date_vue", nullable = false)
    @Builder.Default
    private Instant dateVue = Instant.now();
}
