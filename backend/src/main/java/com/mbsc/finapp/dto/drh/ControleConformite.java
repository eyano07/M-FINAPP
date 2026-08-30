package com.mbsc.finapp.dto.drh;

import java.math.BigDecimal;
import java.util.List;

/**
 * Contrôle de conformité d'un bulletin au droit congolais — <b>purement
 * consultatif</b>.
 *
 * <p>Aucun montant du bulletin n'est modifié ni bloqué : {@code avertissements}
 * signale les écarts constatés (SMIG, allocation familiale minimale, plafond
 * des retenues, exonération transport) et les autres champs fournissent les
 * références légales calculées, que le responsable paie compare lui-même à sa
 * saisie. Choix explicite de l'utilisateur : la saisie manuelle reste
 * souveraine, le système éclaire sans contraindre.</p>
 *
 * <p>Les montants de référence sont exprimés en USD (devise de saisie des
 * bulletins), convertis depuis les montants légaux en FC au taux du bulletin.</p>
 *
 * @param avertissements                 messages d'écart, vide si tout est conforme
 * @param smigMensuelUsd                 SMIG mensuel (Décret n° 25/22)
 * @param allocationFamilialeMinimumUsd  minimum légal pour le nombre d'enfants du bulletin
 * @param plafondRetenuesUsd             plafond légal des retenues (1/10ᵉ du salaire)
 * @param tauxHoraireUsd                 taux horaire de référence (Art. 119)
 * @param heureSup30Usd                  heure supplémentaire majorée de 30 % (6 premières, Art. 120)
 * @param heureSup60Usd                  heure supplémentaire majorée de 60 % (suivantes, Art. 120)
 * @param heureSup100Usd                 heure de repos hebdomadaire ou jour férié, majorée de 100 % (Art. 120)
 */
public record ControleConformite(
    List<String> avertissements,
    BigDecimal smigMensuelUsd,
    BigDecimal allocationFamilialeMinimumUsd,
    BigDecimal plafondRetenuesUsd,
    BigDecimal tauxHoraireUsd,
    BigDecimal heureSup30Usd,
    BigDecimal heureSup60Usd,
    BigDecimal heureSup100Usd
) {
    /** true si au moins un écart a été détecté (pilote l'affichage de l'encart côté frontend). */
    public boolean aDesAvertissements() {
        return avertissements != null && !avertissements.isEmpty();
    }
}
