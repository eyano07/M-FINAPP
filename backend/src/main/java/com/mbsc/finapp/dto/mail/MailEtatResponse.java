package com.mbsc.finapp.dto.mail;

import java.time.Instant;

/** Etat de la messagerie pour l'ecran Administration (jamais le mot de passe, seulement sa fin). */
public record MailEtatResponse(
    boolean actif,
    String hote,
    int port,
    String securite,
    String utilisateur,
    boolean motDePasseConfigure,
    String motDePasseFin,
    String expediteur,
    String urlPublique,
    /** La configuration vient des variables d'environnement (rien d'enregistre dans l'ecran). */
    boolean viaEnvironnement,
    boolean pret,
    String derniereErreur,
    Instant derniereErreurLe
) {}
