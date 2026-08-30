package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.TypeNotification;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Notification adressee a un utilisateur, generee automatiquement par les
 * transitions du workflow des notes de frais (soumission, verification,
 * validation, rejet, transmission, paiement, annulation). Diffusee en temps
 * reel par {@code NotificationService} via SSE, et persistee pour rester
 * consultable apres reconnexion.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "destinataire_id")
    private User destinataire;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private TypeNotification type;

    @Column(nullable = false, length = 200)
    private String titre;

    @Column(nullable = false, length = 500)
    private String message;

    /** Route frontend vers laquelle naviguer au clic (ex. /notes-frais/42). */
    @Column(length = 200)
    private String lien;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "note_frais_id")
    private NoteFrais noteFrais;

    @Column(nullable = false)
    @Builder.Default
    private boolean lue = false;

    @CreationTimestamp
    @Column(name = "date_creation", updatable = false)
    private Instant dateCreation;
}
