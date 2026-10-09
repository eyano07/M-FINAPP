package com.mbsc.finapp.dto.rapprochement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Relevé lu par l'IA dans un fichier (PDF, image, Excel, CSV), à relire avant enregistrement : rien n'est
 * enregistré à ce stade.
 */
public record ExtractionReleveResponse(
    String source,
    String devise,
    LocalDate periodeDebut,
    LocalDate periodeFin,
    BigDecimal soldeOuverture,
    BigDecimal soldeCloture,
    List<LigneReleveRequest> lignes,
    List<String> avertissements
) {}
