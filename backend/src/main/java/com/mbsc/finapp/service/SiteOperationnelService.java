package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.SiteOperationnel;
import com.mbsc.finapp.dto.drh.SiteOperationnelRequest;
import com.mbsc.finapp.dto.drh.SiteOperationnelResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.repository.SiteOperationnelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Catalogue des sites opérationnels (module DRH_MISSIONS). */
@Service
@RequiredArgsConstructor
public class SiteOperationnelService {

    private final SiteOperationnelRepository repository;

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<SiteOperationnelResponse> lister() {
        return repository.findAllByOrderByNom().stream().map(SiteOperationnelResponse::from).toList();
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public SiteOperationnelResponse creer(SiteOperationnelRequest req) {
        SiteOperationnel s = SiteOperationnel.builder()
            .nom(req.nom().trim())
            .localisation(req.localisation())
            .actif(req.actif())
            .build();
        return SiteOperationnelResponse.from(repository.save(s));
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public SiteOperationnelResponse modifier(Long id, SiteOperationnelRequest req) {
        SiteOperationnel s = repository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("SiteOperationnel", id));
        s.setNom(req.nom().trim());
        s.setLocalisation(req.localisation());
        s.setActif(req.actif());
        return SiteOperationnelResponse.from(s);
    }
}
