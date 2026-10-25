package com.mbsc.finapp.dto.instance;

/**
 * Resultat du test de connexion du serveur local vers le serveur en ligne.
 *
 * @param ok              joignable, cle acceptee et versions identiques
 * @param message         explication lisible (cause du refus le cas echeant)
 * @param versionDistante version du schema du serveur en ligne (null si injoignable)
 * @param versionLocale   version du schema de ce serveur
 * @param modeDistant     mode du serveur en ligne (EN_LIGNE ou MIROIR)
 * @param ecartHorlogeMs  ecart d'horloge mesure (serveur en ligne - serveur local)
 */
public record TestConnexionResponse(
    boolean ok,
    String message,
    Integer versionDistante,
    int versionLocale,
    String modeDistant,
    Long ecartHorlogeMs
) {}
