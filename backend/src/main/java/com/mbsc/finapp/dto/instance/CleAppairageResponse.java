package com.mbsc.finapp.dto.instance;

/** Cle d'appairage generee sur le serveur en ligne : affichee UNE seule fois, a recopier sur le serveur local. */
public record CleAppairageResponse(String cle, String appairageId) {}
