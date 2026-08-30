package com.mbsc.finapp.dto.sync;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Lot de transactions a synchroniser (PUSH depuis le poste caissier).
 */
public record SyncBatchRequest(

    @NotNull
    @Valid
    List<TransactionSyncDto> transactions
) {}
