package com.mbsc.finapp.dto.emcf;

import java.math.BigDecimal;
import java.util.List;

/** Parametres de la facture normalisee pour l'ecran Administration (jamais le jeton, seulement sa fin). */
public record EmcfEtatResponse(
    boolean actif,
    String mode,
    String urlBase,
    boolean jetonConfigure,
    String jetonFin,
    String numeroDef,
    int delaiMs,
    boolean utilisable,
    long enAttente,
    long rejetees,
    List<Groupe> groupes
) {
    public record Groupe(String code, String libelle, BigDecimal taux, boolean actif, int ordre) {}
}
