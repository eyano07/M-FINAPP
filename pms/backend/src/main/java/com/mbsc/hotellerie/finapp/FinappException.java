package com.mbsc.hotellerie.finapp;

/**
 * Réponse d'erreur de M-FINAPP (statut et message lisible), ou M-FINAPP injoignable (statut 0). Le message est celui
 * de M-FINAPP : il est montré tel quel à l'utilisateur (règle comptable refusée, droits insuffisants…).
 */
public class FinappException extends RuntimeException {

    private final int statut;
    /** Code d'erreur de M-FINAPP (ex. MOT_DE_PASSE_A_CHANGER, DOUBLE_AUTH_A_ACTIVER), null s'il n'y en a pas. */
    private final String code;

    public FinappException(int statut, String message) {
        this(statut, message, null);
    }

    public FinappException(int statut, String message, String code) {
        super(message);
        this.statut = statut;
        this.code = code;
    }

    public String code() {
        return code;
    }

    public int statut() {
        return statut;
    }

    /** M-FINAPP n'a pas répondu (arrêté, réseau coupé) : l'opération peut être reprise plus tard. */
    public boolean injoignable() {
        return statut == 0 || statut == 502 || statut == 503 || statut == 504;
    }
}
