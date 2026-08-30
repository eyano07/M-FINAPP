package com.mbsc.finapp.exception;

/**
 * Levee lorsqu'une transition de workflow demandee n'est pas autorisee
 * depuis l'etat courant (ex. valider une note deja payee).
 * Traduite en HTTP 409 (Conflict) par {@link GlobalExceptionHandler}.
 */
public class TransitionInvalideException extends RuntimeException {

    public TransitionInvalideException(String message) {
        super(message);
    }
}
