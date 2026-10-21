package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Ligne d'une demande de retour de fonds : l'ancien et le nouveau montant (ou quantite, pour un achat de marchandise)
 * d'une ligne de la note, et la part rendue (TTC, devise de la note). Garde l'historique de la reduction : la ligne de
 * la note, elle, ne porte plus que la valeur apres validation.
 */
@Entity
@Table(name = "lignes_retour_fonds")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LigneRetourFonds {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "retour_id")
    @ToString.Exclude
    private RetourFondsNote retour;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "ligne_note_id")
    @ToString.Exclude
    private LigneNoteFrais ligneNote;

    /** Prix unitaire HT pour un achat de marchandise, montant HT de la ligne sinon (comme {@link LigneNoteFrais#getMontant()}). */
    @Column(name = "ancien_montant", nullable = false, precision = 15, scale = 2)
    private BigDecimal ancienMontant;

    @Column(name = "nouveau_montant", nullable = false, precision = 15, scale = 2)
    private BigDecimal nouveauMontant;

    @Column(name = "ancienne_quantite", precision = 15, scale = 3)
    private BigDecimal ancienneQuantite;

    @Column(name = "nouvelle_quantite", precision = 15, scale = 3)
    private BigDecimal nouvelleQuantite;

    /** Part rendue de cette ligne, TTC, dans la devise de la note. */
    @Column(name = "montant_retour", nullable = false, precision = 15, scale = 2)
    private BigDecimal montantRetour;
}
