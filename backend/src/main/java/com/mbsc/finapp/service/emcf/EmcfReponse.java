package com.mbsc.finapp.service.emcf;

import java.time.Instant;

/** Réponse fiscale du dispositif : à imprimer sur la facture. */
public record EmcfReponse(String uid, String signature, String numeroDef, Instant dateFiscale, String codeQr, String brut) {}
