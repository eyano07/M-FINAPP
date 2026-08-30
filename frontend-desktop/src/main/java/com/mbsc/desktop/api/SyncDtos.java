package com.mbsc.desktop.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTOs de transport pour la synchronisation avec le backend.
 * Conserves volontairement plats pour une serialisation JSON simple.
 */
public final class SyncDtos {

    private SyncDtos() {}

    /** Une ligne d'ecriture transmise. */
    public record EcritureDto(
        String numeroCompte,
        String libelle,
        BigDecimal debit,
        BigDecimal credit,
        String dateEcriture
    ) {}

    /** Payload d'une transaction de caisse a synchroniser (idempotent par uuid). */
    public record TransactionSyncDto(
        String uuid,
        String reference,
        String noteReference,
        BigDecimal montant,
        String sens,
        String numeroRecu,
        String caissierEmail,
        String dateOperation,
        List<EcritureDto> ecritures
    ) {}

    /** Requete de batch push. */
    public record SyncBatchRequest(List<TransactionSyncDto> transactions) {}

    /** Reponse du batch : uuids acceptes et en conflit. */
    public record SyncBatchResponse(
        List<String> acceptedUuids,
        List<String> conflictUuids
    ) {}
}
