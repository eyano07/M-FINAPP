package com.mbsc.finapp.dto.comptabilite;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * État de la clôture comptable : verrou de période, clôture définitive d'exercice, dérogation à la validation par
 * une autre personne, demandes à double validation et historique des changements (audit SYSCOHADA, C-03 et C-06).
 */
public record EtatClotureResponse(
    LocalDate dateCloture,
    LocalDate dateClotureExercice,
    boolean autoValidationAutorisee,
    List<Demande> demandes,
    List<Historique> historique
) {
    public record Demande(Long id, String type, LocalDate dateDemandee, String motif, String statut,
                          String demandePar, Long demandeParId, LocalDateTime demandeLe, String decidePar,
                          LocalDateTime decideLe, String commentaire) {
    }

    public record Historique(String type, LocalDate ancienneDate, LocalDate nouvelleDate, String motif, String auteur,
                             LocalDateTime le) {
    }
}
