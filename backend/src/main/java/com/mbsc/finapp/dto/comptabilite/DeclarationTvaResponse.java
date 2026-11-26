package com.mbsc.finapp.dto.comptabilite;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Resultat de l'arrete de TVA d'une periode : la piece qui solde la TVA
 * collectee contre la TVA recuperable et porte le net en 4441 (TVA due) ou
 * 4449 (credit a reporter).
 *
 * @param pieceReference reference de la piece generee, {@code null} si la
 *                       periode ne presentait aucun solde a arreter
 * @param soldeNet       positif = TVA due a l'Etat, negatif = credit reportable
 */
public record DeclarationTvaResponse(
    LocalDate du,
    LocalDate au,
    BigDecimal tvaCollectee,
    BigDecimal tvaRecuperable,
    BigDecimal soldeNet,
    String compteDeSolde,
    String pieceReference,
    String message
) {}
