package com.mbsc.desktop.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mbsc.desktop.api.ApiClient;
import com.mbsc.desktop.api.SyncDtos;
import com.mbsc.desktop.config.AppConfig;
import com.mbsc.desktop.local.OutboxDao;
import com.mbsc.desktop.local.TransactionDao;
import com.mbsc.desktop.model.SyncOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Moteur de synchronisation (PUSH idempotent base sur l'Outbox).
 *
 * Strategie :
 *  1. Le caissier travaille toujours sur H2 (source de verite hors-ligne).
 *  2. Chaque paiement depose une entree dans l'Outbox (PENDING).
 *  3. Au retour de la connexion, on rejoue l'Outbox par lots vers /sync/batch.
 *  4. Le backend fait un upsert par UUID -> idempotent, les renvois sont surs.
 *  5. Les UUID acceptes passent en SENT ; les transactions locales sont marquees synced.
 */
public class SyncEngine {

    private static final Logger log = LoggerFactory.getLogger(SyncEngine.class);

    private final ApiClient apiClient;
    private final OutboxDao outboxDao;
    private final TransactionDao transactionDao;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Consumer<String> onLog = msg -> {};
    private Runnable onSyncComplete = () -> {};

    public SyncEngine(ApiClient apiClient, OutboxDao outboxDao, TransactionDao transactionDao) {
        this.apiClient = apiClient;
        this.outboxDao = outboxDao;
        this.transactionDao = transactionDao;
    }

    public void setOnLog(Consumer<String> onLog) { this.onLog = onLog; }
    public void setOnSyncComplete(Runnable r) { this.onSyncComplete = r; }

    /** Journalise a la fois dans SLF4J et vers l'UI (callback). */
    private void report(String message) {
        log.info(message);
        onLog.accept(message);
    }

    /**
     * Declenche un cycle de synchronisation. Non bloquant en cas de cycle deja en cours.
     * A appeler depuis un thread de fond (ex: au passage ONLINE du NetworkMonitor).
     */
    public void synchronize() {
        if (!running.compareAndSet(false, true)) {
            log.debug("Cycle de synchronisation deja en cours, ignore.");
            return; // un cycle est deja en cours
        }
        try {
            if (!apiClient.isAuthenticated()) {
                report("Synchronisation differee : caissier non authentifie.");
                return;
            }
            pushOutbox();
        } catch (Exception e) {
            log.error("Erreur durant la synchronisation", e);
            onLog.accept("Erreur de synchronisation : " + e.getMessage());
        } finally {
            running.set(false);
            onSyncComplete.run();
        }
    }

    private void pushOutbox() throws Exception {
        List<SyncOutbox> envoyables = outboxDao.findEnvoyables(AppConfig.SYNC_BATCH_SIZE);
        if (envoyables.isEmpty()) {
            report("Rien a synchroniser.");
            return;
        }

        // Construit le lot a partir des payloads JSON stockes
        List<SyncDtos.TransactionSyncDto> dtos = new ArrayList<>();
        for (SyncOutbox entry : envoyables) {
            SyncDtos.TransactionSyncDto dto = apiClient.mapper()
                .readValue(entry.getPayloadJson(),
                    new TypeReference<SyncDtos.TransactionSyncDto>() {});
            dtos.add(dto);
        }

        report("Envoi de " + dtos.size() + " transaction(s)...");
        SyncDtos.SyncBatchResponse response =
            apiClient.pushBatch(new SyncDtos.SyncBatchRequest(dtos));

        var accepted = response.acceptedUuids() == null ? List.<String>of() : response.acceptedUuids();
        var conflicts = response.conflictUuids() == null ? List.<String>of() : response.conflictUuids();

        for (SyncOutbox entry : envoyables) {
            String uuid = entry.getEntiteUuid();
            if (accepted.contains(uuid)) {
                outboxDao.markSent(entry.getId());
                transactionDao.markSynced(uuid);
            } else if (conflicts.contains(uuid)) {
                log.warn("Conflit de synchronisation sur la transaction {}", uuid);
                outboxDao.markConflict(entry.getId(), "Conflit signale par le serveur");
            } else {
                log.warn("Transaction {} non confirmee par le serveur (sera reessayee)", uuid);
                outboxDao.markFailed(entry.getId(), "Non confirme par le serveur");
            }
        }

        report("Synchronisation terminee : " + accepted.size()
            + " accepte(s), " + conflicts.size() + " conflit(s).");
    }
}
