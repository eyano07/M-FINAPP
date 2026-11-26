package com.mbsc.finapp.dto.notes;

/**
 * Ce que l'utilisateur courant voit dans la liste des notes de frais.
 *
 * @param restreinte  vrai si son role lui cache certaines notes : l'ecran
 *                    n'affiche l'explication que dans ce cas
 * @param explication une ou deux phrases en francais, destinees a l'utilisateur
 */
public record RegleVisibiliteResponse(boolean restreinte, String explication) {
}
