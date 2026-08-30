package com.mbsc.finapp.dto.admin;

import java.math.BigDecimal;
import java.util.List;

/**
 * Compte rendu d'un import de journal.
 *
 * @param simulation      true si rien n'a ete ecrit en base (pre-visualisation)
 * @param lignesLues      nombre de lignes de donnees trouvees dans le fichier
 * @param piecesDetectees nombre de pieces distinctes (regroupement par reference)
 * @param piecesImportees nombre de pieces reellement creees (0 en simulation)
 * @param totalDebit      cumul des debits du fichier
 * @param totalCredit     cumul des credits du fichier
 * @param erreurs         anomalies bloquantes, avec le numero de ligne du fichier
 * @param avertissements  anomalies non bloquantes
 * @param references      references des pieces creees (ou qui le seraient)
 */
public record ImportJournalResponse(
    boolean simulation,
    int lignesLues,
    int piecesDetectees,
    int piecesImportees,
    BigDecimal totalDebit,
    BigDecimal totalCredit,
    List<String> erreurs,
    List<String> avertissements,
    List<String> references,
    List<SuggestionImport> suggestions
) {}
