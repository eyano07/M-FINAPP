package com.mbsc.finapp.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Péremption des plats : date d'expiration d'une production, et portions qui l'ont dépassée sans être vendues.
 *
 * <p>La fiche technique d'un plat porte sa durée de conservation, en jours. Les portions d'une production expirent à sa
 * date de production + cette durée : elles se vendent jusqu'à cette date incluse, et, passé le lendemain, celles qui n'ont
 * pas été vendues sont périmées.</p>
 *
 * <p><b>Quelles portions restent ?</b> Un plat n'a pas de lots : seul son stock est tenu. On en reconstitue donc la
 * composition en rejouant son historique de stock (entrées par production, puis sorties) dans l'ordre où il s'est
 * produit, avec la règle que suit un restaurant :</p>
 * <ul>
 *   <li>une sortie (vente, casse, repas du personnel...) prend d'abord les portions encore bonnes <em>le jour où elle a
 *       lieu</em>, les plus anciennes en premier ; elle n'entame une production expirée que s'il ne reste rien de bon ;</li>
 *   <li>un retrait pour péremption fait l'inverse : il prend d'abord les portions périmées.</li>
 * </ul>
 * <p>Ainsi, une production expirée dont les portions n'ont pas été vendues reste périmée, quoi qu'on vende ensuite des
 * productions suivantes — elle ne « disparaît » pas dans le stock. Une entrée sans production n'a pas d'origine connue :
 * elle n'expire jamais. L'état du stock reste la seule source de vérité de la quantité : on ne déclare jamais plus de
 * portions périmées qu'il n'y en a en stock.</p>
 */
public final class PeremptionPlats {

    private PeremptionPlats() {
    }

    /**
     * Une entrée en stock telle que le calcul la voit : une production, ou — référence nulle — une entrée dont l'origine est
     * inconnue, sans date d'expiration.
     */
    public record LotProduit(String reference, LocalDate dateProduction, LocalDate dateExpiration, BigDecimal quantite) {}

    /**
     * Un mouvement du stock du plat, dans l'ordre où il s'est produit.
     *
     * @param entree        portions entrées (0 pour une sortie)
     * @param sortie        portions sorties (0 pour une entrée)
     * @param origine       pour une entrée, la production qui l'a faite
     * @param retraitPerime true pour une sortie qui retire des portions périmées
     */
    public record Mouvement(LocalDate date, BigDecimal entree, BigDecimal sortie, LotProduit origine, boolean retraitPerime) {}

    /** Ce qu'il reste d'une production, périmé ou non. */
    public record Part(LotProduit lot, BigDecimal quantite, boolean perimee) {}

    /** Date d'expiration des portions d'une production ; {@code null} quand le plat n'a pas de durée de conservation. */
    public static LocalDate dateExpiration(LocalDate dateProduction, Integer dureeJours) {
        return dureeJours == null ? null : dateProduction.plusDays(dureeJours);
    }

    /** true si les portions sont périmées {@code jour} : elles se vendent jusqu'à leur date d'expiration incluse. */
    public static boolean estPerimee(LocalDate dateExpiration, LocalDate jour) {
        return dateExpiration != null && dateExpiration.isBefore(jour);
    }

    /** Ce qui reste d'une entrée au fil du rejeu. */
    private static final class Reste {
        private final LotProduit origine;
        private BigDecimal restante;

        private Reste(LotProduit origine, BigDecimal restante) {
            this.origine = origine;
            this.restante = restante;
        }
    }

    /**
     * Rejoue l'historique du stock et dit ce qu'il reste de chaque production, jugé {@code jour}.
     *
     * @param chronologie mouvements du plat dans un entrepôt, du plus ancien au plus récent
     * @param jour        jour auquel on juge la péremption
     * @return les restes non vides, dans l'ordre des entrées
     */
    public static List<Part> rejouer(List<Mouvement> chronologie, LocalDate jour) {
        List<Reste> lots = new ArrayList<>();
        for (Mouvement m : chronologie) {
            if (m.entree().signum() > 0) {
                lots.add(new Reste(m.origine(), m.entree()));
            }
            if (m.sortie().signum() > 0) {
                consommer(lots, m.sortie(), m.date(), m.retraitPerime());
            }
        }
        List<Part> parts = new ArrayList<>();
        for (Reste r : lots) {
            if (r.restante.signum() > 0) {
                parts.add(new Part(r.origine, r.restante, estPerimee(r.origine.dateExpiration(), jour)));
            }
        }
        return parts;
    }

    /**
     * Une sortie le {@code date} : les portions encore bonnes ce jour-là d'abord, puis les périmées — l'inverse pour un
     * retrait de péremption. À qualité égale, la plus ancienne production passe en premier.
     */
    private static void consommer(List<Reste> lots, BigDecimal quantite, LocalDate date, boolean perimesDAbord) {
        List<Reste> bonnes = new ArrayList<>();
        List<Reste> perimees = new ArrayList<>();
        for (Reste r : lots) {
            if (r.restante.signum() > 0) {
                (estPerimee(r.origine.dateExpiration(), date) ? perimees : bonnes).add(r);
            }
        }
        List<Reste> ordre = new ArrayList<>(perimesDAbord ? perimees : bonnes);
        ordre.addAll(perimesDAbord ? bonnes : perimees);
        BigDecimal restant = quantite;
        for (Reste r : ordre) {
            if (restant.signum() <= 0) {
                break;
            }
            BigDecimal pris = r.restante.min(restant);
            r.restante = r.restante.subtract(pris);
            restant = restant.subtract(pris);
        }
    }

    /** Total des portions périmées parmi ces parts. */
    public static BigDecimal quantitePerimee(List<Part> parts) {
        return parts.stream().filter(Part::perimee).map(Part::quantite).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
