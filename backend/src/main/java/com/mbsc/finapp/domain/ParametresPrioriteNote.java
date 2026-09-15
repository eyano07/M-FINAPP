package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.PrioriteNote;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Reserve de tresorerie minimale exigee par priorite de note de frais
 * (ligne unique, id = 1), definie par le Directeur Administratif.
 *
 * <p>Regle appliquee par {@code RegleTresorerieService.validerReglePriorite}
 * aux trois canaux (caisse, banque, mobile money) : une note ne peut etre
 * payee que si le solde du canal <b>apres paiement</b> reste superieur ou
 * egal au seuil de sa priorite. Une note BASSE ne peut donc pas vider la
 * tresorerie sous le matelas garde pour les urgences, alors qu'un seuil
 * HAUTE volontairement bas (voire nul) laisse passer les paiements
 * critiques.</p>
 *
 * <p><b>Un seuil a zero desactive le controle</b> pour cette priorite :
 * c'est la valeur par defaut a l'installation (voir
 * V76__seuils_priorite_note.sql), afin que l'arrivee de cette
 * fonctionnalite ne bloque aucun paiement tant que le DA n'a rien saisi.</p>
 */
@Entity
@Table(name = "parametres_priorite_note")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParametresPrioriteNote {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    /** Solde minimum a preserver pour autoriser le paiement d'une note BASSE. */
    @Column(name = "seuil_basse", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal seuilBasse = BigDecimal.ZERO;

    /** Solde minimum a preserver pour autoriser le paiement d'une note MOYENNE. */
    @Column(name = "seuil_moyenne", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal seuilMoyenne = BigDecimal.ZERO;

    /** Solde minimum a preserver pour autoriser le paiement d'une note HAUTE. */
    @Column(name = "seuil_haute", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal seuilHaute = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "maj_par_id")
    @ToString.Exclude
    private User majPar;

    @Column(name = "maj_le")
    private Instant majLe;

    /**
     * Seuil applicable a une priorite donnee. Une priorite nulle (note dont
     * le DA n'a pas encore fixe la priorite) est traitee comme BASSE : c'est
     * le seuil le plus protecteur, on ne laisse pas une note non classee
     * echapper au controle.
     */
    public BigDecimal seuilPour(PrioriteNote priorite) {
        if (priorite == PrioriteNote.HAUTE) return nz(seuilHaute);
        if (priorite == PrioriteNote.MOYENNE) return nz(seuilMoyenne);
        return nz(seuilBasse);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
