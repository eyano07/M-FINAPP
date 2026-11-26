package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Rapprochement d'une ou plusieurs lignes de relevé avec une ou plusieurs écritures du compte, de même
 * total. Porte la pièce de régularisation quand l'écriture a été créée à partir du relevé (frais, agios...).
 */
@Entity
@Table(name = "pointages_rapprochement")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PointageRapprochement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "releve_id")
    @ToString.Exclude
    private ReleveBancaire releve;

    @Column(nullable = false)
    private boolean automatique;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "piece_regularisation_id")
    @ToString.Exclude
    private PieceComptable pieceRegularisation;

    @CreationTimestamp
    @Column(name = "date_creation", updatable = false)
    private Instant dateCreation;
}
