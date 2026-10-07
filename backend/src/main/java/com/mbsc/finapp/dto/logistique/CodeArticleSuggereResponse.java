package com.mbsc.finapp.dto.logistique;

/**
 * Code proposé pour un nouvel article de la carte (voir GenerateurCodeArticle).
 *
 * @param code le code libre déduit du libellé et de la société, ou une chaîne vide
 *             tant que le libellé ne permet pas d'en composer un
 */
public record CodeArticleSuggereResponse(String code) {
}
