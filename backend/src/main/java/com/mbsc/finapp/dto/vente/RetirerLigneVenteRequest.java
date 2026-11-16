package com.mbsc.finapp.dto.vente;

import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** Retour d'un article commandé avant paiement : quantité rendue ; absente, toute la ligne est retirée. */
public record RetirerLigneVenteRequest(@Positive BigDecimal quantite) {}
