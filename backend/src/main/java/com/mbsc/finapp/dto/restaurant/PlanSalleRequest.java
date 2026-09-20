package com.mbsc.finapp.dto.restaurant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Etat complet des tables d'une salle, envoye en une fois par le bouton "Enregistrer" de l'editeur de plan. */
public record PlanSalleRequest(
    @NotNull @Valid List<TableRequest> tables
) {}
