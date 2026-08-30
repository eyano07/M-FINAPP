package com.mbsc.desktop.api;

/** Exception levee lors d'un echec d'appel API. */
public class ApiException extends RuntimeException {
    public ApiException(String message) {
        super(message);
    }
}
