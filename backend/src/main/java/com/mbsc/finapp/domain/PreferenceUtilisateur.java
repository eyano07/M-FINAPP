package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Preference d'affichage d'un utilisateur (ex. « notes.affichage » = TABLEAU), enregistree sur son compte pour qu'elle le suive
 * d'un appareil a l'autre et survive a la deconnexion. Une ligne par utilisateur et par cle ; les cles et valeurs admises sont
 * fixees par {@code ProfilService}. Supprimee avec l'utilisateur (V130).
 */
@Entity
@Table(name = "preferences_utilisateur")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PreferenceUtilisateur {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false, length = 60)
    private String cle;

    @Column(nullable = false, length = 60)
    private String valeur;

    @UpdateTimestamp
    @Column(name = "date_maj", nullable = false)
    private Instant dateMaj;
}
