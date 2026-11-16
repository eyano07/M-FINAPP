package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.ModeRetraitPerimes;
import com.mbsc.finapp.domain.enums.RoleType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Paramètres du module Restaurant (ligne unique, id = 1) : la devise dans
 * laquelle les montants du module (carte, stock, tableaux de bord) sont
 * présentés par défaut, et la façon dont les plats périmés quittent le stock.
 *
 * <p>La devise n'affecte que l'affichage : le stockage sous-jacent ne change pas
 * (grand livre toujours en USD, {@code Article.prixVente} toujours en FC) —
 * voir {@code RestaurantService} et les écrans concernés pour la conversion
 * appliquée à la lecture. La répartition des sorties sur les lots de stock
 * n'est plus un paramètre : FIFO pour les boissons, CMP pour les provisions
 * (voir {@code LotStockService.methode}). L'arrondi non plus : francs sans
 * décimale, dollars à 2 décimales, fixé côté écran (V94).</p>
 *
 * <p>Le mode de retrait des plats périmés (V119) est, lui, un réglage de l'administrateur : à la main avec une
 * notification, ou automatiquement par la tâche planifiée (voir {@code PlatsPerimesAutomatiqueService}).</p>
 */
@Entity
@Table(name = "restaurant_parametres")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParametresRestaurant {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "devise_affichage", nullable = false, length = 3)
    @Builder.Default
    private Devise deviseAffichage = Devise.CDF;

    /** Retrait des plats périmés : MANUEL (notification, le responsable retire) ou AUTOMATIQUE. Manuel tant qu'un administrateur n'a rien décidé. */
    @Enumerated(EnumType.STRING)
    @Column(name = "retrait_perimes_mode", nullable = false, length = 12)
    @Builder.Default
    private ModeRetraitPerimes retraitPerimesMode = ModeRetraitPerimes.MANUEL;

    /** L'administrateur qui a activé le retrait automatique : les retraits automatiques sont passés à son nom. Absent en mode manuel. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "retrait_perimes_auteur_id")
    private User retraitPerimesAuteur;

    /** Dernier changement du mode de retrait. */
    @Column(name = "retrait_perimes_modifie_le")
    private Instant retraitPerimesModifieLe;

    /** Dernier jour où la tâche planifiée a prévenu qu'il y avait des plats périmés (une notification par jour au plus). */
    @Column(name = "perimes_signales_le")
    private LocalDate perimesSignalesLe;

    /**
     * Le retrait automatique peut s'exécuter : l'administrateur qui l'a activé existe encore, son compte est actif et il a
     * toujours le rôle d'administrateur. Sinon plus personne n'est habilité à répondre des écritures de la tâche, qui
     * s'abstient et prévient (voir {@code PlatsPerimesAutomatiqueService}).
     */
    public boolean retraitPerimesAuteurHabilite() {
        User u = retraitPerimesAuteur;
        return u != null && u.isActif() && u.getRoles().stream().anyMatch(r -> r.getNom() == RoleType.ADMIN);
    }
}
