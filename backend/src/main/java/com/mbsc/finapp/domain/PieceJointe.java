package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "pieces_jointes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PieceJointe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "note_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private NoteFrais noteFrais;

    @Column(name = "nom_fichier", nullable = false, length = 255)
    private String nomFichier;

    @Column(name = "type_mime", length = 100)
    private String typeMime;

    @Column(name = "taille")
    private Long taille;

    @Column(name = "chemin_stockage", nullable = false, length = 500)
    private String cheminStockage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ajoute_par_id")
    @ToString.Exclude
    private User ajoutePar;

    @CreationTimestamp
    @Column(name = "date_ajout", updatable = false)
    private Instant dateAjout;
}
