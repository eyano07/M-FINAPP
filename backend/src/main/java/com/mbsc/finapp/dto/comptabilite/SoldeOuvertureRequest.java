package com.mbsc.finapp.dto.comptabilite;

import jakarta.validation.constraints.Size;

/** Bascule du marqueur « solde d'ouverture » ; motif inscrit au journal des événements comptables (C-09). */
public record SoldeOuvertureRequest(boolean soldeOuverture, @Size(max = 1000) String motif) {}
