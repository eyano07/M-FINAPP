package com.mbsc.finapp.exception;

/**
 * Conflit metier detecte lors de la synchronisation d'une transaction de caisse
 * (ex. note de frais deja payee par une autre transaction). L'element fautif ne
 * doit pas etre reessaye tel quel ; il est signale au client via la liste des
 * uuids en conflit.
 */
public class ConflitSyncException extends RuntimeException {

    public ConflitSyncException(String message) {
        super(message);
    }
}
