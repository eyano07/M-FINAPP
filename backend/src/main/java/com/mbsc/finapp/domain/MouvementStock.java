package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.StatutMouvement;
import com.mbsc.finapp.domain.enums.TypeMouvementStock;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "mouvements_stock")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MouvementStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TypeMouvementStock type;

    @Column(name = "date_mouvement", nullable = false)
    private LocalDate dateMouvement;

    @Column(length = 255)
    private String libelle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatutMouvement statut;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "compte_contrepartie_id")
    private CompteOHADA compteContrepartie;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "piece_id")
    @ToString.Exclude
    private PieceComptable piece;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    @ToString.Exclude
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "mouvement", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    private List<LigneMouvementStock> lignes = new ArrayList<>();

    public void addLigne(LigneMouvementStock ligne) {
        ligne.setMouvement(this);
        this.lignes.add(ligne);
    }
}
