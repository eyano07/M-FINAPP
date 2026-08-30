package com.mbsc.finapp.dto.admin;

/**
 * Proposition de correction sur un fichier d'import.
 *
 * <p>Jamais appliquee d'office : une substitution de compte change
 * l'imputation comptable, c'est a l'administrateur de la valider. Le champ
 * {@code raison} existe pour qu'il puisse juger sans avoir a deviner.</p>
 *
 * @param type           COMPTE (substitution d'un numero) ou COLONNE (correspondance d'en-tete)
 * @param valeurActuelle ce que contient le fichier
 * @param valeurProposee ce qu'il faudrait mettre
 * @param libelle        libelle du compte propose, pour reconnaissance immediate
 * @param raison         pourquoi cette proposition
 * @param genereParIa    true si l'IA a tranche, false si la regle etait deterministe
 */
public record SuggestionImport(
    String type,
    String valeurActuelle,
    String valeurProposee,
    String libelle,
    String raison,
    boolean genereParIa
) {}
