package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.StatutProduction;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Transformation de provisions en portions d'un plat.
 *
 * <p>Elle genere DEUX mouvements de stock, donc deux pieces du journal STOCK :
 * la sortie des ingredients (D compte de charge de la provision / C son compte
 * de stock, au CMP) puis l'entree des portions (D compte de stock du plat /
 * C son compte de variation 736 — la production stockee SYSCOHADA).</p>
 *
 * <p>Le cout unitaire n'est jamais saisi : il se deduit du cout reel des
 * ingredients effectivement sortis, divise par le nombre de portions. C'est ce
 * qui donne au plat un CMP non nul, sans quoi sa vente afficherait une marge de
 * 100 % (voir {@code TypeArticle.PLAT}).</p>
 */
@Entity
@Table(name = "productions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Production {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String reference;   // PRD-2026-000012

    @Column(name = "date_production", nullable = false)
    private LocalDate dateProduction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plat_id", nullable = false)
    @ToString.Exclude
    private Article plat;

    /** Entrepot ou les ingredients sont pris et ou les portions entrent. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entrepot_id", nullable = false)
    @ToString.Exclude
    private Entrepot entrepot;

    /** Nombre de portions produites. */
    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal quantite;

    /** Cout reel des ingredients consommes, au CMP du moment. */
    @Column(name = "cout_total", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal coutTotal = BigDecimal.ZERO;

    /**
     * Cout de revient d'une portion. Tenu a 6 decimales comme le CMP interne
     * de StockService : arrondir ici a 2 ferait deriver la valeur entree en
     * stock du cout reellement sorti des que les portions ne divisent pas rond.
     */
    @Column(name = "cout_unitaire", nullable = false, precision = 15, scale = 6)
    @Builder.Default
    private BigDecimal coutUnitaire = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StatutProduction statut = StatutProduction.VALIDEE;

    /** Sortie de stock des ingredients. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mouvement_sortie_id")
    @ToString.Exclude
    private MouvementStock mouvementSortie;

    /** Entree en stock des portions produites. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mouvement_entree_id")
    @ToString.Exclude
    private MouvementStock mouvementEntree;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    @ToString.Exclude
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "production", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @Builder.Default
    @ToString.Exclude
    private List<LigneProduction> lignes = new ArrayList<>();

    public void addLigne(LigneProduction ligne) {
        ligne.setProduction(this);
        this.lignes.add(ligne);
    }
}
