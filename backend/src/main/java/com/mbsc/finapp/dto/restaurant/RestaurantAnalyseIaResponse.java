package com.mbsc.finapp.dto.restaurant;

import java.time.LocalDate;
import java.util.List;

/**
 * Interpretation en langage naturel du tableau de bord Restaurant sur une
 * periode — voir {@code RestaurantAnalyseIaService}. Meme forme que
 * {@code AnalyseFinanciereResponse} (comptabilite), dupliquee ici plutot que
 * partagee : les deux domaines restent independants, le module Restaurant
 * n'a pas a dependre du module Comptabilite pour son propre DTO.
 *
 * @param genereParIa true si l'analyse provient de l'appel au modele,
 *                    false si elle a ete produite par le repli heuristique
 *                    local (mode degrade sans cle API).
 */
public record RestaurantAnalyseIaResponse(
    LocalDate du,
    LocalDate au,
    String synthese,
    List<String> pointsForts,
    List<String> pointsAttention,
    List<String> recommandations,
    boolean genereParIa
) {}
