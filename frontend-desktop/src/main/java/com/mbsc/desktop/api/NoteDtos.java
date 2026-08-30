package com.mbsc.desktop.api;

import java.math.BigDecimal;

/**
 * DTO de lecture des notes de frais transmises a la caisse.
 * Reflete la vue resumee renvoyee par {@code GET /notes-frais} du backend.
 */
public final class NoteDtos {

    private NoteDtos() {
    }

    /**
     * Note de frais a payer par le caissier (statut TRANSMISE_CAISSE).
     * La priorite est definie par le DA et oriente l'ordre de traitement.
     *
     * <p>Depuis l'introduction des notes multi-lignes, chaque depense de la
     * note peut avoir son propre compte d'imputation : il n'y a plus de
     * compte unique au niveau de l'entete. {@code nombreLignes} permet
     * d'informer le caissier que la ventilation exacte se fait ligne par
     * ligne (visible sur l'application web), le paiement hors-ligne restant
     * une saisie manuelle simplifiee (compte a confirmer/ajuster).</p>
     */
    public record NoteAPayer(
        Long id,
        String reference,
        String objet,
        BigDecimal montant,
        String devise,
        String statut,
        String priorite,
        String createurNom,
        Integer nombreLignes
    ) {}
}
