package com.mbsc.hotellerie.commun;

/** Erreur métier avec son statut HTTP et un message destiné à l'utilisateur. */
public class Refus extends RuntimeException {

    private final int statut;

    public Refus(int statut, String message) {
        super(message);
        this.statut = statut;
    }

    public static Refus invalide(String message) {
        return new Refus(400, message);
    }

    public static Refus introuvable(String quoi) {
        return new Refus(404, quoi + " introuvable.");
    }

    public static Refus conflit(String message) {
        return new Refus(409, message);
    }

    public int statut() {
        return statut;
    }
}
