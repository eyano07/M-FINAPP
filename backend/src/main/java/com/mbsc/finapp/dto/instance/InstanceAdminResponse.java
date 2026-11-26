package com.mbsc.finapp.dto.instance;

import java.time.Instant;

/** Ecran Administration › Serveur local et synchronisation (la cle n'est jamais renvoyee, seulement sa fin). */
public record InstanceAdminResponse(
    String mode,
    String adresseDistante,
    String cleFin,
    String appairageId,
    Instant cleCreeeLe,
    String serveurAssocie,
    Instant appaireLe,
    Instant dernierContact,
    Instant derniereSynchro,
    String derniereErreur,
    Instant derniereErreurLe,
    int versionSchema,
    long enAttente,
    String modifiePar,
    Instant dateMaj
) {}
