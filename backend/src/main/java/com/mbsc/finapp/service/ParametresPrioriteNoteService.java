package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ParametresPrioriteNote;
import com.mbsc.finapp.dto.notes.ParametresPrioriteNoteRequest;
import com.mbsc.finapp.dto.notes.ParametresPrioriteNoteResponse;
import com.mbsc.finapp.repository.ParametresPrioriteNoteRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Seuils de reserve de tresorerie par priorite (ligne unique), definis par
 * le DA et appliques par {@link RegleTresorerieService} aux trois canaux.
 *
 * <p>La saisie est reservee au DA (et a l'ADMIN) : c'est lui qui arbitre la
 * tresorerie, conformement a son role de definition des priorites de
 * paiement. La lecture est ouverte aux roles impliques dans le circuit
 * (DFIN, caissier, comptable) afin que le blocage eventuel soit
 * comprehensible du cote de celui qui paie.</p>
 */
@Service
@RequiredArgsConstructor
public class ParametresPrioriteNoteService {

    private static final Logger log = LoggerFactory.getLogger(ParametresPrioriteNoteService.class);

    private final ParametresPrioriteNoteRepository repository;
    private final CurrentUserProvider currentUser;

    /** Acces interne (sans controle de role) : utilise par la regle de tresorerie. */
    @Transactional
    public ParametresPrioriteNote get() {
        return repository.findById(ParametresPrioriteNote.SINGLETON_ID)
            .orElseGet(this::creerParDefaut);
    }

    @PreAuthorize("hasAnyRole('DA', 'DFIN', 'CAISSIER', 'COMPTABLE', 'ADMIN')")
    @Transactional(readOnly = true)
    public ParametresPrioriteNoteResponse consulter() {
        return ParametresPrioriteNoteResponse.from(get());
    }

    @PreAuthorize("hasAnyRole('DA', 'ADMIN')")
    @Transactional
    public ParametresPrioriteNoteResponse enregistrer(ParametresPrioriteNoteRequest req) {
        ParametresPrioriteNote p = get();
        p.setSeuilBasse(req.seuilBasse());
        p.setSeuilMoyenne(req.seuilMoyenne());
        p.setSeuilHaute(req.seuilHaute());
        p.setMajPar(currentUser.requireUser());
        p.setMajLe(Instant.now());
        p = repository.save(p);
        log.info("Seuils de priorite mis a jour [basse={}, moyenne={}, haute={}, par={}]",
            p.getSeuilBasse(), p.getSeuilMoyenne(), p.getSeuilHaute(), p.getMajPar().getEmail());
        return ParametresPrioriteNoteResponse.from(p);
    }

    private ParametresPrioriteNote creerParDefaut() {
        // 0 partout = aucune restriction, comportement historique preserve.
        return repository.save(ParametresPrioriteNote.builder()
            .id(ParametresPrioriteNote.SINGLETON_ID)
            .seuilBasse(BigDecimal.ZERO)
            .seuilMoyenne(BigDecimal.ZERO)
            .seuilHaute(BigDecimal.ZERO)
            .build());
    }
}
