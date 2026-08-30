package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.dto.sync.SyncBatchRequest;
import com.mbsc.finapp.dto.sync.SyncBatchResponse;
import com.mbsc.finapp.dto.sync.TransactionSyncDto;
import com.mbsc.finapp.exception.ConflitSyncException;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Reception des transactions de caisse synchronisees depuis le poste hors-ligne.
 *
 * <p>Idempotence : la cle est l'{@code uuid} de la transaction. Chaque element
 * du lot est traite isolement par {@link SyncTransactionProcessor} (transaction
 * propre) : un conflit ou un echec sur l'un n'empeche pas les autres.</p>
 *
 * <p>Semantique de la reponse attendue par le client :</p>
 * <ul>
 *   <li>{@code acceptedUuids} : insere ou deja present -&gt; l'Outbox est purgee.</li>
 *   <li>{@code conflictUuids} : conflit metier -&gt; marque en conflit cote client.</li>
 *   <li>absent des deux listes : erreur transitoire -&gt; sera reessaye.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final SyncTransactionProcessor processor;
    private final CurrentUserProvider currentUser;

    public SyncBatchResponse traiterLot(SyncBatchRequest request) {
        User caissierCourant = currentUser.requireUser();
        List<String> acceptes = new ArrayList<>();
        List<String> conflits = new ArrayList<>();

        List<TransactionSyncDto> transactions =
            request.transactions() == null ? List.of() : request.transactions();

        for (TransactionSyncDto dto : transactions) {
            try {
                processor.traiter(dto, caissierCourant);
                acceptes.add(dto.uuid());   // insere OU deja present : idempotent
            } catch (ConflitSyncException e) {
                log.warn("Conflit de synchronisation [uuid={}] : {}", dto.uuid(), e.getMessage());
                conflits.add(dto.uuid());
            } catch (Exception e) {
                // Erreur transitoire : non confirmee -> reessai ulterieur cote client.
                log.error("Echec de synchronisation [uuid={}] : {}", dto.uuid(), e.getMessage());
            }
        }

        log.info("Batch de synchronisation traite : {} accepte(s), {} conflit(s) sur {} recue(s)",
            acceptes.size(), conflits.size(), transactions.size());
        return new SyncBatchResponse(acceptes, conflits);
    }
}
