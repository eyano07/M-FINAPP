package com.mbsc.finapp.dto.ia;

/**
 * Suggestion de compte OHADA pour une ligne de depense.
 *
 * @param genereParIa true si la suggestion provient de l'appel au modele
 *                    (Claude), false si elle a ete produite par le
 *                    repli heuristique local (mode degrade sans cle API).
 */
public record SuggestionCompteResponse(
    String compteNumero,
    String compteLibelle,
    boolean genereParIa
) {}
