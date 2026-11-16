package com.mbsc.finapp.security;

import org.springframework.security.core.AuthenticationException;

/**
 * Connexion refusée faute de code de double authentification valide (audit SYSCOHADA du 10/11/2026, C-23). Le
 * {@link #getCode() code} dit à l'écran s'il doit demander le code ({@code DOUBLE_AUTH_REQUISE}) ou signaler un code
 * faux ({@code CODE_DOUBLE_AUTH_INCORRECT}).
 */
public class DoubleAuthException extends AuthenticationException {

    public static final String REQUISE = "DOUBLE_AUTH_REQUISE";
    public static final String INCORRECT = "CODE_DOUBLE_AUTH_INCORRECT";

    private final String code;

    public DoubleAuthException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
