package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.TauxChange;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.repository.TauxChangeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Conversion des devises vers la devise de base de la comptabilité (USD).
 *
 * <p>Principe Sage/QuickBooks : le Grand Livre est tenu dans UNE devise de
 * base. Toute opération saisie en devise étrangère est convertie au taux du
 * jour à la comptabilisation ; la devise, le montant d'origine et le taux
 * appliqué sont conservés sur chaque écriture pour l'audit.</p>
 */
@Service
@RequiredArgsConstructor
public class ConversionDeviseService {

    /**
     * Devise dans laquelle le Grand Livre est tenu.
     *
     * <p>La comptabilite de l'entreprise etant tenue en dollars, c'est l'USD
     * qui fait foi : les colonnes debit/credit du Grand Livre, le bilan, la
     * balance et le compte de resultat sont exprimes dans cette devise. Une
     * operation libellee en francs est convertie a l'enregistrement.</p>
     */
    public static final Devise DEVISE_BASE = Devise.USD;

    private final TauxChangeRepository tauxChangeRepository;

    /** Résultat d'une conversion : montant en devise de base + taux appliqué. */
    public record Conversion(BigDecimal montantBase, Devise deviseOrigine,
                             BigDecimal montantOrigine, BigDecimal tauxApplique) {

        public boolean estConvertie() {
            return deviseOrigine != DEVISE_BASE;
        }
    }

    /**
     * Taux de change en vigueur aujourd'hui (FC pour 1 USD), ou {@code null}
     * si aucun taux n'est défini.
     *
     * <p>Cette méthode retenait auparavant le taux de plus grande date d'effet
     * <em>sans filtrer les dates futures</em> : un taux saisi à l'avance —
     * usage légitime — devenait immédiatement effectif pour la caisse, la
     * banque et le mobile money, alors que les ventes et les pièces
     * automatiques (qui passent par {@link #tauxALaDate}) continuaient
     * d'appliquer le taux du jour. Deux opérations du même jour pouvaient donc
     * être converties à deux taux différents. On délègue désormais à
     * {@code tauxALaDate(aujourd'hui)}, seule règle de résolution.</p>
     */
    @Transactional(readOnly = true)
    public BigDecimal tauxCourant() {
        return tauxALaDate(LocalDate.now());
    }

    /**
     * Taux en vigueur à une date donnée, ou {@code null} si aucun taux
     * applicable.
     *
     * <p>C'est le taux à retenir pour toute opération portant une date propre
     * (vente, pièce comptable) : convertir une opération antidatée au taux du
     * jour fausserait sa contre-valeur. À défaut de taux antérieur à la date
     * demandée — cas d'une reprise d'historique — on retient le plus ancien
     * taux connu plutôt que rien.</p>
     */
    @Transactional(readOnly = true)
    public BigDecimal tauxALaDate(LocalDate date) {
        if (date == null) {
            return tauxCourant();
        }
        return tauxChangeRepository
            .findFirstByDateEffetLessThanEqualOrderByDateEffetDescCreatedAtDesc(date)
            .map(TauxChange::getTaux)
            .orElseGet(this::tauxLePlusAncien);
    }

    private BigDecimal tauxLePlusAncien() {
        List<TauxChange> historique = tauxChangeRepository.findAllByOrderByDateEffetDescCreatedAtDesc();
        return historique.isEmpty() ? null : historique.get(historique.size() - 1).getTaux();
    }

    /**
     * Convertit un montant vers la devise de base, au taux du jour.
     * @throws IllegalStateException si la conversion est requise mais qu'aucun
     *         taux de change valide n'est défini.
     */
    @Transactional(readOnly = true)
    public Conversion enDeviseBase(BigDecimal montant, Devise devise) {
        return enDeviseBase(montant, devise, tauxCourant());
    }

    /**
     * Convertit un montant vers la devise de base à un taux imposé.
     *
     * <p>Permet à l'appelant de résoudre le taux <em>une seule fois</em> pour
     * toute une opération, et de l'appliquer aussi bien aux écritures qu'à la
     * transaction : deux résolutions séparées pourraient encadrer un
     * changement de taux et produire deux valeurs différentes pour un même
     * mouvement.</p>
     */
    @Transactional(readOnly = true)
    public Conversion enDeviseBase(BigDecimal montant, Devise devise, BigDecimal taux) {
        Devise d = devise == null ? DEVISE_BASE : devise;
        if (d == DEVISE_BASE) {
            return new Conversion(montant, DEVISE_BASE, montant, null);
        }
        if (taux == null || taux.signum() <= 0) {
            throw new IllegalStateException(
                "Aucun taux de change valide n'est défini : impossible de convertir "
                + montant + " " + d + " en " + DEVISE_BASE
                + ". Demandez à un administrateur d'enregistrer le taux du jour.");
        }
        BigDecimal converti = versBase(montant, d, taux);
        return new Conversion(converti, d, montant, taux);
    }

    /**
     * Applique le taux dans le bon sens.
     *
     * <p>Le taux est TOUJOURS exprime en francs pour 1 dollar (ex. 2 200), quel
     * que soit le sens de la conversion. Passer des francs aux dollars divise
     * donc par le taux ; l'inverse multiplie. La regle est ecrite ici une seule
     * fois : une erreur d'orientation ne se voit pas a l'oeil, elle produit des
     * montants faux d'un facteur egal au taux.</p>
     */
    private BigDecimal versBase(BigDecimal montant, Devise origine, BigDecimal taux) {
        return DEVISE_BASE == Devise.USD
            ? montant.divide(taux, 2, RoundingMode.HALF_UP)              // FC -> USD
            : montant.multiply(taux).setScale(2, RoundingMode.HALF_UP);  // USD -> FC
    }

    /** Operation inverse de {@code versBase} : de la devise de base vers une devise etrangere. */
    public BigDecimal depuisBase(BigDecimal montantBase, Devise cible, BigDecimal taux) {
        if (montantBase == null || cible == null || cible == DEVISE_BASE
            || taux == null || taux.signum() <= 0) {
            return montantBase;
        }
        return DEVISE_BASE == Devise.USD
            ? montantBase.multiply(taux).setScale(2, RoundingMode.HALF_UP)  // USD -> FC
            : montantBase.divide(taux, 2, RoundingMode.HALF_UP);            // FC -> USD
    }
}
