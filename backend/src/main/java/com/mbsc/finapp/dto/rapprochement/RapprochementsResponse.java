package com.mbsc.finapp.dto.rapprochement;

import com.mbsc.finapp.domain.enums.StatutReleve;

import java.math.BigDecimal;
import java.util.List;

/** Tableau d'une année : établissements (banques, mobile money) et leurs relevés mois par mois. */
public record RapprochementsResponse(int annee, List<ReleveDetailResponse.Etablissement> etablissements,
                                     List<Resume> releves) {
    public record Resume(Long id, Long etablissementId, int mois, StatutReleve statut, BigDecimal soldeCloture,
                         int nombreLignes, int nombreLignesNonPointees) {}
}
