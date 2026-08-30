package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.StatutNote;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "observations_note")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ObservationNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "note_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private NoteFrais noteFrais;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "auteur_id")
    private User auteur;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut_au_moment", length = 30)
    private StatutNote statutAuMoment;

    @Column(length = 1000)
    private String commentaire;

    @CreationTimestamp
    @Column(name = "date_action", updatable = false)
    private Instant dateAction;
}
