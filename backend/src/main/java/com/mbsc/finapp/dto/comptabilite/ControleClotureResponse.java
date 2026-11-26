package com.mbsc.finapp.dto.comptabilite;

/** Un contrôle préalable à la clôture d'exercice : bloquant ou simple avertissement, satisfait ou non. */
public record ControleClotureResponse(String code, String libelle, boolean bloquant, boolean satisfait, String detail) {
}
