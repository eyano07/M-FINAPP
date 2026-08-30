package com.mbsc.finapp.dto.sync;

import java.util.List;

/**
 * Resultat d'un batch de synchronisation.
 *
 * @param acceptedUuids uuids traites avec succes (inseres ou deja presents : idempotent)
 * @param conflictUuids uuids en conflit metier (ne seront pas reessayes tels quels)
 */
public record SyncBatchResponse(
    List<String> acceptedUuids,
    List<String> conflictUuids
) {}
