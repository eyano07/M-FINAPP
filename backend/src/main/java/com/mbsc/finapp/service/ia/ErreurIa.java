package com.mbsc.finapp.service.ia;

/**
 * Échec d'un appel à un fournisseur d'IA, classé pour décider du repli : {@link Genre#CREDIT_EPUISE} et
 * {@link Genre#CLE_REFUSEE} sont durables (le fournisseur est écarté jusqu'à nouvel ordre), les autres
 * ({@link Genre#TEMPORAIRE}, {@link Genre#AUTRE}) ne concernent que l'appel en cours.
 */
public class ErreurIa extends RuntimeException {

    public enum Genre {
        /** Facturation : crédit épuisé, paiement requis. */
        CREDIT_EPUISE,
        /** Clé invalide, révoquée ou sans droit. */
        CLE_REFUSEE,
        /** Surcharge, limite de débit, délai dépassé, réseau : réessayer plus tard. */
        TEMPORAIRE,
        /** Requête refusée pour une autre raison (modèle inconnu, format...). */
        AUTRE
    }

    private final Genre genre;

    public ErreurIa(Genre genre, String message, Throwable cause) {
        super(message, cause);
        this.genre = genre;
    }

    public Genre genre() {
        return genre;
    }
}
