package com.mbsc.finapp.exception;

/**
 * Levée lorsque le caissier tente de payer une note de frais en violant
 * la règle de priorité de paiement (HAUTE > MOYENNE > BASSE) ou lorsque
 * le solde disponible est insuffisant compte tenu des notes prioritaires
 * encore en attente.
 *
 * <p>Traduite en HTTP 422 (Unprocessable Entity) par {@link GlobalExceptionHandler}.</p>
 */
public class ReglePrioriteException extends RuntimeException {

    public ReglePrioriteException(String message) {
        super(message);
    }
}
