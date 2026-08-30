package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Employe;
import com.mbsc.finapp.domain.SortieTravailleur;
import com.mbsc.finapp.dto.drh.SortieTravailleurRequest;
import com.mbsc.finapp.dto.drh.SortieTravailleurResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.repository.EmployeRepository;
import com.mbsc.finapp.repository.SortieTravailleurRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.YearMonth;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/** Sorties ponctuelles pendant la journée de travail (module DRH_PRESENCES). */
@Service
@RequiredArgsConstructor
public class SortieTravailleurService {

    private static final Logger log = LoggerFactory.getLogger(SortieTravailleurService.class);

    private final SortieTravailleurRepository repository;
    private final EmployeRepository employeRepository;

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<SortieTravailleurResponse> listerPeriode(Integer mois, Integer annee) {
        if (mois == null || annee == null) {
            return repository.findAllOrdered().stream().map(SortieTravailleurResponse::from).toList();
        }
        YearMonth periode = YearMonth.of(annee, mois);
        return repository.findByPeriode(periode.atDay(1), periode.atEndOfMonth()).stream()
            .map(SortieTravailleurResponse::from).toList();
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public SortieTravailleurResponse creer(SortieTravailleurRequest req) {
        valider(req);
        Employe employe = employeRepository.findById(req.employeId())
            .orElseThrow(() -> RessourceIntrouvableException.of("Employe", req.employeId()));
        SortieTravailleur s = SortieTravailleur.builder()
            .employe(employe)
            .dateSortie(req.dateSortie())
            .heureSortie(req.heureSortie())
            .heureRetour(req.heureRetour())
            .motif(req.motif())
            .notes(req.notes())
            .build();
        s = repository.save(s);
        log.info("Sortie enregistrée [employe={}, date={}]", employe.getMatricule(), req.dateSortie());
        return SortieTravailleurResponse.from(s);
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public SortieTravailleurResponse modifier(Long id, SortieTravailleurRequest req) {
        valider(req);
        SortieTravailleur s = repository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("SortieTravailleur", id));
        s.setDateSortie(req.dateSortie());
        s.setHeureSortie(req.heureSortie());
        s.setHeureRetour(req.heureRetour());
        s.setMotif(req.motif());
        s.setNotes(req.notes());
        return SortieTravailleurResponse.from(s);
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public void supprimer(Long id) {
        if (!repository.existsById(id)) {
            throw RessourceIntrouvableException.of("SortieTravailleur", id);
        }
        repository.deleteById(id);
    }

    private void valider(SortieTravailleurRequest req) {
        if (req.heureRetour() != null && req.heureRetour().isBefore(req.heureSortie())) {
            throw new ResponseStatusException(BAD_REQUEST,
                "L'heure de retour (" + req.heureRetour() + ") ne peut pas être antérieure à l'heure de sortie ("
                + req.heureSortie() + ").");
        }
    }
}
