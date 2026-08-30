package com.mbsc.finapp.exception;

/**
 * Levee lorsqu'une entite demandee n'existe pas en base.
 * Traduite en HTTP 404 par {@link GlobalExceptionHandler}.
 */
public class RessourceIntrouvableException extends RuntimeException {

    public RessourceIntrouvableException(String message) {
        super(message);
    }

    /**
     * Fabrique un message normalise du type
     * {@code "NoteFrais introuvable (id=42)"}.
     */
    public static RessourceIntrouvableException of(String typeEntite, Object identifiant) {
        return new RessourceIntrouvableException(
            "%s introuvable (id=%s)".formatted(typeEntite, identifiant));
    }
}
