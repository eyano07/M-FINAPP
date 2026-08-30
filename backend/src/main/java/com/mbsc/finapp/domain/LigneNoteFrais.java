package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Ligne de depense d'une note de frais : une note peut regrouper plusieurs
 * depenses, chacune imputee a son propre compte OHADA et avec son propre
 * montant. Le montant total de la note (NoteFrais.montant) est la somme de
 * ces lignes.
 */
@Entity
@Table(name = "lignes_note_frais")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LigneNoteFrais {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "note_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private NoteFrais noteFrais;

    /** Montant HORS TAXE de la ligne. La TVA (si {@link #soumisTva}) s'ajoute par-dessus, elle n'en est jamais extraite. */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal montant;

    /** Compte OHADA d'imputation de cette ligne (optionnel : fallback 6588 au paiement). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "compte_imputation_id")
    private CompteOHADA compteImputation;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private Integer ordre = 1;

    /** true si cette depense correspond a un achat de marchandises. */
    @Column(name = "achat_marchandise", nullable = false)
    @Builder.Default
    private boolean achatMarchandise = false;

    /** Nombre d'unites achetees. Avec {@link #article} et {@link #entrepot}, alimente le stock au paiement. */
    @Column(name = "quantite_marchandise", precision = 15, scale = 3)
    private BigDecimal quantiteMarchandise;

    /** Article de marchandise concerne (obligatoire si achatMarchandise est vrai). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id")
    private Article article;

    /** Entrepot de destination de l'entree en stock (obligatoire si achatMarchandise est vrai). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entrepot_id")
    private Entrepot entrepot;

    /**
     * true si cet achat de marchandise s'accompagne d'un echange de consigne :
     * pertinent uniquement pour un article BOISSON du module Restaurant, ou
     * il declenche la reprise des bouteilles vides equivalentes au paiement
     * (voir RestaurantService.enregistrerAchatVidesDepuisNoteFraisInterne).
     * Sans effet pour tout autre type d'article.
     */
    @Column(name = "echange_consigne", nullable = false)
    @Builder.Default
    private boolean echangeConsigne = false;

    /**
     * true si l'achat est soumis a la TVA : la TVA s'ajoute alors par-dessus
     * le montant HT de la ligne (au taux {@link #tauxTvaApplique}), et est
     * comptabilisee separement sur {@link #compteTva} (TVA recuperable) au
     * lieu d'etre absorbee dans le compte de charge.
     */
    @Column(name = "soumis_tva", nullable = false)
    @Builder.Default
    private boolean soumisTva = false;

    /** Compte de TVA recuperable (ex. 4452), obligatoire si soumisTva est vrai. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "compte_tva_id")
    private CompteOHADA compteTva;

    /**
     * Taux de TVA (%) fige au moment ou la ligne est creee ou modifiee, tant
     * que la note n'est pas encore payee. Comme {@code NoteFrais.tauxEngagement}
     * pour le change : le montant TTC annonce sur la note (et donc le montant
     * effectivement paye) ne doit jamais varier au gre d'un changement de
     * taux legal decide entre la creation de la note et son reglement.
     */
    @Column(name = "taux_tva_applique", precision = 5, scale = 2)
    private BigDecimal tauxTvaApplique;

    /**
     * Montant HT total de la ligne. Pour un achat de marchandise, {@link #montant}
     * est le prix unitaire : le total facture est ce prix multiplie par la
     * quantite receptionnee. Pour une depense ordinaire (pas de quantite), le
     * montant saisi EST deja le total.
     */
    public BigDecimal montantHtTotal() {
        if (achatMarchandise && quantiteMarchandise != null) {
            return montant.multiply(quantiteMarchandise).setScale(2, java.math.RoundingMode.HALF_UP);
        }
        return montant;
    }

    /** Montant TTC de la ligne (HT total + TVA au taux fige), ce qui sera reellement decaisse pour elle. */
    public BigDecimal montantTtc() {
        BigDecimal ht = montantHtTotal();
        if (!soumisTva || tauxTvaApplique == null || tauxTvaApplique.signum() <= 0) {
            return ht;
        }
        java.math.BigDecimal tva = ht.multiply(tauxTvaApplique)
            .divide(java.math.BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
        return ht.add(tva);
    }
}
