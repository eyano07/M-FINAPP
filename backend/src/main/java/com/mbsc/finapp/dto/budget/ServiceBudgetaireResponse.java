package com.mbsc.finapp.dto.budget;

import com.mbsc.finapp.domain.ServiceBudgetaire;
import com.mbsc.finapp.domain.User;

import java.time.Instant;

/**
 * Un service et son usage : un service deja present dans un budget ou sur une note de frais ne se supprime plus,
 * il se desactive (son historique reste).
 */
public record ServiceBudgetaireResponse(
    Long id,
    String nom,
    String responsable,
    String description,
    boolean actif,
    long nombreLignesBudget,
    long nombreNotes,
    Instant dateCreation,
    String creeParNom
) {
    public static ServiceBudgetaireResponse from(ServiceBudgetaire s, long lignes, long notes) {
        User u = s.getCreePar();
        String auteur = u == null ? null : ((u.getPrenom() == null ? "" : u.getPrenom()) + " " + (u.getNom() == null ? "" : u.getNom())).trim();
        return new ServiceBudgetaireResponse(s.getId(), s.getNom(), s.getResponsable(), s.getDescription(), s.isActif(),
            lignes, notes, s.getDateCreation(), auteur);
    }

    /** true si le service n'est rattache a aucune ligne de budget ni a aucune note : il peut etre supprime. */
    public boolean supprimable() {
        return nombreLignesBudget == 0 && nombreNotes == 0;
    }
}
