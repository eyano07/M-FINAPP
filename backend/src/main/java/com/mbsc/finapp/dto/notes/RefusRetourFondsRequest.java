package com.mbsc.finapp.dto.notes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Corps de POST /caisse/retours-fonds/{id}/refuser : le caissier n'a pas recu l'argent ; le motif est transmis a l'emetteur. */
public record RefusRetourFondsRequest(@NotBlank @Size(max = 500) String motif) {}
