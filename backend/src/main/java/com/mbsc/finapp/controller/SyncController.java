package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.sync.SyncBatchRequest;
import com.mbsc.finapp.dto.sync.SyncBatchResponse;
import com.mbsc.finapp.service.SyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Point d'entree de synchronisation appele par le poste caissier hors-ligne.
 * Le PUSH est idempotent (cle = uuid de transaction).
 */
@RestController
@RequestMapping("/sync")
@RequiredArgsConstructor
public class SyncController {

    private final SyncService syncService;

    @PostMapping("/batch")
    @PreAuthorize("hasAnyRole('CAISSIER', 'ADMIN')")
    public SyncBatchResponse batch(@Valid @RequestBody SyncBatchRequest request) {
        return syncService.traiterLot(request);
    }
}
