package com.mbsc.finapp.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Calcul pur d'un retour de fonds sur une note de frais deja payee (voir {@link RetourFondsService}) : verifie la
 * reduction demandee ligne par ligne, produit ce qui est rendu (TTC, devise de la note), puis sa ventilation en devise de
 * base — HT au compte de la ligne, TVA a son compte de TVA —, miroir exact de
 * {@link RegleTresorerieService#construireLignesDebitDepuisNote} pour la seule part rendue. Sans Spring ni base de
 * donnees : testable tel quel.
 *
 * <p>Regles : on ne fait que <b>reduire</b> (jamais augmenter), jusqu'a zero ; un achat de marchandise se reduit en
 * <b>quantite</b> (au meme prix unitaire), toute autre ligne en <b>montant</b> ; les frais d'approche ne se reduisent pas
 * (leur cout est deja incorpore a la valeur des marchandises en stock).</p>
 */
public final class RetourFondsCalcul {

    private static final BigDecimal CENT = BigDecimal.valueOf(100);
    private static final int ECHELLE_MONTANT = 2;
    private static final int ECHELLE_QUANTITE = 3;

    private RetourFondsCalcul() {
    }

    /**
     * Ligne de la note telle que le calcul la voit.
     *
     * @param montant    prix unitaire HT pour un achat de marchandise, montant HT de la ligne sinon
     * @param tvaSeparee la TVA de la ligne a ete portee a son propre compte au paiement (ligne soumise a la TVA et dotee
     *                   d'un compte de TVA) ; sinon tout le montant a ete porte au compte de la ligne
     */
    public record LigneNote(Long id, BigDecimal montant, BigDecimal quantite, boolean achatMarchandise, boolean fraisApproche,
                            boolean soumisTva, BigDecimal tauxTva, boolean tvaSeparee) {
    }

    /** Nouvelle valeur demandee pour une ligne : quantite (achat de marchandise) ou montant (toute autre ligne). */
    public record Demande(Long ligneId, BigDecimal nouvelleQuantite, BigDecimal nouveauMontant) {
    }

    /** Reduction retenue pour une ligne ; {@code montantRetour} = TTC avant - TTC apres, devise de la note. */
    public record Reduction(Long ligneId, BigDecimal ancienMontant, BigDecimal nouveauMontant,
                            BigDecimal ancienneQuantite, BigDecimal nouvelleQuantite, BigDecimal montantRetour) {

        /** Quantite rendue (achat de marchandise), zero sinon. */
        public BigDecimal quantiteRendue() {
            return ancienneQuantite == null || nouvelleQuantite == null ? BigDecimal.ZERO : ancienneQuantite.subtract(nouvelleQuantite);
        }
    }

    /** Part d'une ligne dans l'encaissement du retour, en devise de base : HT au compte de la ligne, TVA a son compte de TVA. */
    public record PartCredit(Long ligneId, BigDecimal ht, BigDecimal tva) {
    }

    /**
     * Montant TTC d'une ligne (HT total + TVA au taux fige) : la regle de {@code LigneNoteFrais#montantTtc()}, recopiee
     * pour pouvoir calculer une ligne avant et apres sa reduction sans toucher a l'entite.
     */
    public static BigDecimal ttc(BigDecimal montant, BigDecimal quantite, boolean achatMarchandise, boolean soumisTva,
                                 BigDecimal tauxTva) {
        BigDecimal ht = achatMarchandise && quantite != null
            ? montant.multiply(quantite).setScale(ECHELLE_MONTANT, RoundingMode.HALF_UP)
            : montant;
        if (!soumisTva || tauxTva == null || tauxTva.signum() <= 0) {
            return ht;
        }
        BigDecimal tva = ht.multiply(tauxTva).divide(CENT, ECHELLE_MONTANT, RoundingMode.HALF_UP);
        return ht.add(tva);
    }

    /**
     * Verifie la demande et calcule la reduction de chaque ligne citee. Une ligne citee sans changement est ignoree ; il
     * faut au moins une ligne reellement reduite.
     *
     * @throws IllegalArgumentException demande invalide (message destine a l'utilisateur)
     */
    public static List<Reduction> reductions(List<LigneNote> lignes, List<Demande> demandes) {
        if (demandes == null || demandes.isEmpty()) {
            throw new IllegalArgumentException("Indiquez au moins une ligne à réduire.");
        }
        Map<Long, LigneNote> parId = new LinkedHashMap<>();
        for (LigneNote l : lignes) {
            parId.put(l.id(), l);
        }
        Set<Long> vues = new HashSet<>();
        List<Reduction> resultat = new ArrayList<>();
        for (Demande d : demandes) {
            LigneNote l = d.ligneId() == null ? null : parId.get(d.ligneId());
            if (l == null) {
                throw new IllegalArgumentException("Ligne inconnue sur cette note : " + d.ligneId() + ".");
            }
            if (!vues.add(l.id())) {
                throw new IllegalArgumentException("La même ligne est citée deux fois dans la demande.");
            }
            BigDecimal nouveauMontant = l.montant();
            BigDecimal nouvelleQuantite = l.quantite();
            if (l.fraisApproche()) {
                boolean change = (d.nouveauMontant() != null && d.nouveauMontant().compareTo(l.montant()) != 0)
                    || d.nouvelleQuantite() != null;
                if (change) {
                    throw new IllegalArgumentException("Les frais d'approche (transport, manutention) ne se réduisent pas : "
                        + "leur coût est déjà incorporé à la valeur des marchandises en stock.");
                }
                continue;
            }
            if (l.achatMarchandise()) {
                if (d.nouveauMontant() != null && d.nouveauMontant().compareTo(l.montant()) != 0) {
                    throw new IllegalArgumentException("Pour un achat de marchandise, on réduit la quantité, pas le prix unitaire.");
                }
                if (d.nouvelleQuantite() == null) {
                    throw new IllegalArgumentException("Indiquez la nouvelle quantité de la ligne d'achat.");
                }
                nouvelleQuantite = d.nouvelleQuantite().setScale(ECHELLE_QUANTITE, RoundingMode.HALF_UP);
                if (nouvelleQuantite.signum() < 0) {
                    throw new IllegalArgumentException("Une quantité ne peut pas être négative.");
                }
                if (l.quantite() == null || nouvelleQuantite.compareTo(l.quantite()) > 0) {
                    throw new IllegalArgumentException("On ne peut que réduire : la quantité passerait de "
                        + (l.quantite() == null ? "0" : l.quantite().stripTrailingZeros().toPlainString()) + " à "
                        + nouvelleQuantite.stripTrailingZeros().toPlainString() + ".");
                }
            } else {
                if (d.nouvelleQuantite() != null) {
                    throw new IllegalArgumentException("Cette ligne n'est pas un achat de marchandise : réduisez son montant.");
                }
                if (d.nouveauMontant() == null) {
                    throw new IllegalArgumentException("Indiquez le nouveau montant de la ligne.");
                }
                nouveauMontant = d.nouveauMontant().setScale(ECHELLE_MONTANT, RoundingMode.HALF_UP);
                if (nouveauMontant.signum() < 0) {
                    throw new IllegalArgumentException("Un montant ne peut pas être négatif.");
                }
                if (nouveauMontant.compareTo(l.montant()) > 0) {
                    throw new IllegalArgumentException("On ne peut que réduire : le montant passerait de "
                        + l.montant().toPlainString() + " à " + nouveauMontant.toPlainString() + ".");
                }
            }
            BigDecimal avant = ttc(l.montant(), l.quantite(), l.achatMarchandise(), l.soumisTva(), l.tauxTva());
            BigDecimal apres = ttc(nouveauMontant, nouvelleQuantite, l.achatMarchandise(), l.soumisTva(), l.tauxTva());
            BigDecimal rendu = avant.subtract(apres);
            if (rendu.signum() <= 0) {
                continue;   // ligne citee sans changement reel
            }
            resultat.add(new Reduction(l.id(), l.montant(), nouveauMontant,
                l.achatMarchandise() ? l.quantite() : null, l.achatMarchandise() ? nouvelleQuantite : null, rendu));
        }
        if (resultat.isEmpty()) {
            throw new IllegalArgumentException("Aucune ligne n'est réduite : saisissez une valeur plus basse que l'actuelle.");
        }
        return resultat;
    }

    /** Montant total rendu (TTC, devise de la note). */
    public static BigDecimal total(List<Reduction> reductions) {
        return reductions.stream().map(Reduction::montantRetour).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Ventilation de l'encaissement du retour en devise de base, ligne par ligne : chaque part rendue est convertie par
     * {@code versBase} (au taux du paiement d'origine), sauf la derniere, qui absorbe l'ecart d'arrondi pour que la somme
     * egale exactement {@code totalBase} ; la TVA est ensuite extraite au taux fige de la ligne (base x taux / (100 + taux))
     * — exactement comme au paiement, pour que le retour annule ce qui avait ete debite.
     */
    public static List<PartCredit> ventiler(List<Reduction> reductions, Map<Long, LigneNote> lignes, BigDecimal totalBase,
                                            Function<BigDecimal, BigDecimal> versBase) {
        List<PartCredit> parts = new ArrayList<>();
        BigDecimal somme = BigDecimal.ZERO;
        for (int i = 0; i < reductions.size(); i++) {
            Reduction r = reductions.get(i);
            LigneNote l = lignes.get(r.ligneId());
            BigDecimal base = (i == reductions.size() - 1)
                ? totalBase.subtract(somme)
                : versBase.apply(r.montantRetour());
            somme = somme.add(base);
            BigDecimal taux = l.tauxTva();
            if (l.soumisTva() && l.tvaSeparee() && taux != null && taux.signum() > 0) {
                BigDecimal tva = base.multiply(taux).divide(CENT.add(taux), ECHELLE_MONTANT, RoundingMode.HALF_UP);
                parts.add(new PartCredit(r.ligneId(), base.subtract(tva), tva));
            } else {
                parts.add(new PartCredit(r.ligneId(), base, BigDecimal.ZERO));
            }
        }
        return parts;
    }
}
