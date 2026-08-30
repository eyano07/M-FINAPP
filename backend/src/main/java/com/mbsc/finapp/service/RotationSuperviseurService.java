package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Employe;
import com.mbsc.finapp.domain.RotationSuperviseur;
import com.mbsc.finapp.domain.SiteOperationnel;
import com.mbsc.finapp.dto.drh.RotationSuperviseurRequest;
import com.mbsc.finapp.dto.drh.RotationSuperviseurResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.repository.EmployeRepository;
import com.mbsc.finapp.repository.RotationSuperviseurRepository;
import com.mbsc.finapp.repository.SiteOperationnelRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

/**
 * Rotations de superviseurs sur sites opérationnels (module DRH_MISSIONS) :
 * 14 jours de prestation (dimanches inclus) + 7 jours de repos, une fois par
 * mois. Règles de validation portées à l'identique de l'ancien outil
 * PayMBSC ({@code SupervisorSiteService.validate}).
 */
@Service
@RequiredArgsConstructor
public class RotationSuperviseurService {

    private static final Logger log = LoggerFactory.getLogger(RotationSuperviseurService.class);

    private final RotationSuperviseurRepository repository;
    private final EmployeRepository employeRepository;
    private final SiteOperationnelRepository siteRepository;

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<RotationSuperviseurResponse> lister() {
        return repository.findAllOrdered().stream().map(RotationSuperviseurResponse::from).toList();
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<RotationSuperviseurResponse> listerParPeriode(int annee, int mois) {
        return repository.findByAnneeAndMois(annee, mois).stream().map(RotationSuperviseurResponse::from).toList();
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public RotationSuperviseurResponse creer(RotationSuperviseurRequest req) {
        Employe employe = employeRepository.findById(req.employeId())
            .orElseThrow(() -> RessourceIntrouvableException.of("Employe", req.employeId()));
        SiteOperationnel site = siteRepository.findById(req.siteId())
            .orElseThrow(() -> RessourceIntrouvableException.of("SiteOperationnel", req.siteId()));

        RotationSuperviseur r = RotationSuperviseur.builder()
            .employe(employe)
            .site(site)
            .annee(req.annee())
            .mois(req.mois())
            .numeroCycle(1)
            .prestationDebut(req.prestationDebut())
            .notes(req.notes())
            .build();
        appliquerDates(r, req);
        valider(r, null);

        r = repository.save(r);
        log.info("Rotation créée [employe={}, site={}, periode={}/{}]", employe.getMatricule(), site.getNom(),
            req.mois(), req.annee());
        return RotationSuperviseurResponse.from(r);
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public RotationSuperviseurResponse modifier(Long id, RotationSuperviseurRequest req) {
        RotationSuperviseur r = charger(id);
        Employe employe = employeRepository.findById(req.employeId())
            .orElseThrow(() -> RessourceIntrouvableException.of("Employe", req.employeId()));
        SiteOperationnel site = siteRepository.findById(req.siteId())
            .orElseThrow(() -> RessourceIntrouvableException.of("SiteOperationnel", req.siteId()));
        r.setEmploye(employe);
        r.setSite(site);
        r.setAnnee(req.annee());
        r.setMois(req.mois());
        r.setPrestationDebut(req.prestationDebut());
        r.setNotes(req.notes());
        appliquerDates(r, req);
        valider(r, id);
        return RotationSuperviseurResponse.from(r);
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public void supprimer(Long id) {
        if (!repository.existsById(id)) {
            throw RessourceIntrouvableException.of("RotationSuperviseur", id);
        }
        repository.deleteById(id);
    }

    /** Pré-remplit prestationFin/reposDebut/reposFin depuis prestationDebut si non fournis. */
    private void appliquerDates(RotationSuperviseur r, RotationSuperviseurRequest req) {
        LocalDate prestFin = req.prestationFin() != null ? req.prestationFin()
            : RotationSuperviseur.finPrestation(req.prestationDebut());
        LocalDate reposDebut = req.reposDebut() != null ? req.reposDebut() : prestFin.plusDays(1);
        LocalDate reposFin = req.reposFin() != null ? req.reposFin() : RotationSuperviseur.finRepos(reposDebut);
        r.setPrestationFin(prestFin);
        r.setReposDebut(reposDebut);
        r.setReposFin(reposFin);
    }

    private void valider(RotationSuperviseur r, Long excludeId) {
        if (!r.getEmploye().isSuperviseur()) {
            throw erreur("Seul un employé marqué superviseur peut être planifié sur une rotation de site.");
        }
        int joursPrestation = RotationSuperviseur.joursInclusifs(r.getPrestationDebut(), r.getPrestationFin());
        if (joursPrestation != RotationSuperviseur.JOURS_PRESTATION) {
            throw erreur("La prestation doit durer exactement " + RotationSuperviseur.JOURS_PRESTATION
                + " jours (calculé : " + joursPrestation + ").");
        }
        int joursRepos = RotationSuperviseur.joursInclusifs(r.getReposDebut(), r.getReposFin());
        if (joursRepos != RotationSuperviseur.JOURS_REPOS) {
            throw erreur("Le repos doit durer exactement " + RotationSuperviseur.JOURS_REPOS
                + " jours (calculé : " + joursRepos + ").");
        }
        if (!r.getReposDebut().isAfter(r.getPrestationFin())) {
            throw erreur("Le repos doit commencer après la fin de la prestation.");
        }

        repository.findByEmployeIdAndSiteIdAndAnneeAndMoisAndNumeroCycle(
                r.getEmploye().getId(), r.getSite().getId(), r.getAnnee(), r.getMois(), r.getNumeroCycle())
            .filter(existante -> !existante.getId().equals(excludeId))
            .ifPresent(existante -> {
                throw erreur("Une rotation existe déjà pour cet employé sur ce site pour "
                    + r.getMois() + "/" + r.getAnnee() + ".");
            });

        List<RotationSuperviseur> chevauchements = repository.findPrestationOverlapsOnSite(
            r.getSite().getId(), r.getPrestationDebut(), r.getPrestationFin(), excludeId);
        if (!chevauchements.isEmpty()) {
            RotationSuperviseur conflit = chevauchements.get(0);
            throw erreur("Chevauchement avec la prestation de " + conflit.getEmploye().getNomComplet()
                + " sur le site " + r.getSite().getNom() + " (du " + conflit.getPrestationDebut()
                + " au " + conflit.getPrestationFin() + ") : deux agents ne peuvent pas prester en même temps"
                + " sur le même site.");
        }
    }

    private ResponseStatusException erreur(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private RotationSuperviseur charger(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("RotationSuperviseur", id));
    }
}
