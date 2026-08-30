package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.AgentPresenceManuelle;
import com.mbsc.finapp.dto.drh.AgentPresenceManuelleRequest;
import com.mbsc.finapp.dto.drh.AgentPresenceManuelleResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.repository.AgentPresenceManuelleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Liste libre d'agents pour la fiche de présence manuelle imprimable
 * (module DRH_PRESENCES), indépendante du personnel déclaré.
 */
@Service
@RequiredArgsConstructor
public class AgentPresenceManuelleService {

    private final AgentPresenceManuelleRepository repository;

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<AgentPresenceManuelleResponse> lister() {
        return repository.findAllByOrderByOrdreAffichageAscNomCompletAsc().stream()
            .map(AgentPresenceManuelleResponse::from).toList();
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public AgentPresenceManuelleResponse ajouter(AgentPresenceManuelleRequest req) {
        int ordre = (int) repository.count() + 1;
        AgentPresenceManuelle a = AgentPresenceManuelle.builder()
            .nomComplet(req.nomComplet().trim())
            .fonction(req.fonction())
            .ordreAffichage(ordre)
            .build();
        return AgentPresenceManuelleResponse.from(repository.save(a));
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public AgentPresenceManuelleResponse modifier(Long id, AgentPresenceManuelleRequest req) {
        AgentPresenceManuelle a = repository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("AgentPresenceManuelle", id));
        a.setNomComplet(req.nomComplet().trim());
        a.setFonction(req.fonction());
        return AgentPresenceManuelleResponse.from(a);
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public void retirer(Long id) {
        if (!repository.existsById(id)) {
            throw RessourceIntrouvableException.of("AgentPresenceManuelle", id);
        }
        repository.deleteById(id);
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public void viderListe() {
        repository.deleteAll();
    }
}
