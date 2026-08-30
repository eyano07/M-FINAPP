package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.StatutProvision;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Provision pour risques et charges ou dépréciation (audit OHADA du
 * 18/08/2026, constat A-06).
 *
 * <p>Le compte de provision (classe 15/19 pour un risque, 29/39/49/59 pour
 * une dépréciation) et le compte de dotation (68x/69x) sont choisis dans le
 * plan comptable existant plutôt que déduits d'une nomenclature figée dans
 * le code — même principe que {@link Immobilisation#getCompteImmobilisation()}
 * : c'est le plan comptable réel de l'entité qui fait foi.</p>
 */
@Entity
@Table(name = "provisions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Provision {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String reference;   // PROV-2026-000001

    @Column(nullable = false, length = 300)
    private String libelle;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "compte_provision_id")
    private CompteOHADA compteProvision;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "compte_dotation_id")
    private CompteOHADA compteDotation;

    @Column(name = "montant_constitue", nullable = false, precision = 15, scale = 2)
    private BigDecimal montantConstitue;

    @Column(name = "montant_repris", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal montantRepris = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StatutProvision statut = StatutProvision.CONSTITUEE;

    @Column(name = "date_constitution", nullable = false)
    private LocalDate dateConstitution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "piece_constitution_id")
    @ToString.Exclude
    private PieceComptable pieceConstitution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    @ToString.Exclude
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "provision", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("dateReprise ASC, id ASC")
    @Builder.Default
    @ToString.Exclude
    private List<ProvisionReprise> reprises = new ArrayList<>();

    public BigDecimal soldeRestant() {
        return montantConstitue.subtract(montantRepris);
    }
}
