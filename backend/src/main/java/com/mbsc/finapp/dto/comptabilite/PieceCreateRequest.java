package com.mbsc.finapp.dto.comptabilite;

import com.mbsc.finapp.domain.enums.JournalComptable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record PieceCreateRequest(
    @NotNull LocalDate datePiece,
    @NotNull JournalComptable journal,
    @Size(max = 255) String libelle,

    /**
     * true = reprise des a-nouveaux : les ecritures alimentent les colonnes
     * « soldes d'ouverture » de la balance au lieu des mouvements de la
     * periode. Absent ou null a la creation = piece ordinaire.
     */
    boolean soldeOuverture,

    @NotEmpty @Valid List<LigneEcritureRequest> lignes
) {}
