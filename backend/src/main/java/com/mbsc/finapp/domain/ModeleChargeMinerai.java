package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Frais accessoire standard d'un minerais, rejoue a chaque reception.
 *
 * <p>Un pont bascule, une autorisation de transport ou un peage se repetent a
 * l'identique sur chaque camion d'un meme projet : les ressaisir un par un
 * etait fastidieux et source d'oublis. Cocher « appliquer a tous les camions »
 * enregistre donc le frais comme modele du minerais, et chaque reception
 * suivante le rejoue automatiquement.</p>
 *
 * <p>Le modele ne fixe qu'un <b>montant par defaut</b>. La ligne creee sur le
 * camion ({@link ChargeCamionMinerai}) lui est ensuite totalement independante :
 * elle se modifie ou se supprime camion par camion, sans toucher au modele ni
 * aux autres chargements. Un per diem plus eleve sur une route difficile se
 * corrige donc sur le seul camion concerne.</p>
 */
@Entity
@Table(name = "modeles_charge_minerai")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModeleChargeMinerai {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Le minerais concerne : le modele ne vaut que pour ses chargements. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    /** Nature du frais : « Pont bascule », « Peage routier »... */
    @Column(nullable = false, length = 200)
    private String libelle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compte_charge_id", nullable = false)
    private CompteOHADA compteCharge;

    /** Montant par defaut, corrigeable camion par camion apres application. */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal montant;

    @CreationTimestamp
    @Column(name = "date_creation", updatable = false)
    private Instant dateCreation;

    @UpdateTimestamp
    @Column(name = "date_maj")
    private Instant dateMaj;
}
