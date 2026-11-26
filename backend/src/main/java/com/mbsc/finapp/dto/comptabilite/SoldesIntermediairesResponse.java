package com.mbsc.finapp.dto.comptabilite;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Soldes intermediaires de gestion (SIG) du SYSCOHADA revise : la cascade qui
 * mene du chiffre d'affaires au resultat net en isolant, a chaque etage, une
 * grandeur economique interpretable.
 *
 * <p>Chaque {@link Solde} porte son mode de calcul en clair : un SIG se lit
 * autant par sa formule que par son montant, et c'est cette formule qui permet
 * a un lecteur de rapprocher le chiffre du plan comptable.</p>
 */
public record SoldesIntermediairesResponse(
    LocalDate du,
    LocalDate au,
    List<Solde> soldes
) {
    /**
     * Un etage de la cascade.
     *
     * @param cle      identifiant stable, pour un rendu cote client
     * @param libelle  intitule OHADA
     * @param formule  composition en langage comptable (comptes concernes)
     * @param montant  valeur de la periode, en devise de base
     * @param pivot    vrai pour les soldes majeurs (VA, EBE, resultat net) que
     *                 la restitution doit mettre en avant
     */
    public record Solde(
        String cle,
        String libelle,
        String formule,
        BigDecimal montant,
        boolean pivot
    ) {}
}
