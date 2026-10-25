package com.mbsc.finapp.dto.instance;

/** Reponse du serveur en ligne a un appel signe du serveur local. */
public record PingResponse(int versionSchema, String mode, String appairageId, long heure) {}
