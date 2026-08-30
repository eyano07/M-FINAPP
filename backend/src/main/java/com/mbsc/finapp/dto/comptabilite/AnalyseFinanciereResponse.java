package com.mbsc.finapp.dto.comptabilite;

import java.time.LocalDate;
import java.util.List;

/**
 * Interprétation en langage naturel des états financiers d'une période
 * (Bilan, Compte de résultat, Balance de vérification), produite par l'agent
 * IA d'analyse financière — voir {@code AnalyseFinanciereIaService}.
 *
 * @param genereParIa true si l'analyse provient de l'appel au modèle
 *                    (Claude), false si elle a été produite par le
 *                    repli heuristique local (mode dégradé sans clé API).
 */
public record AnalyseFinanciereResponse(
    LocalDate du,
    LocalDate au,
    String synthese,
    List<String> pointsForts,
    List<String> pointsAttention,
    List<String> recommandations,
    boolean genereParIa
) {
}
