package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Employe;
import com.mbsc.finapp.domain.Presence;
import com.mbsc.finapp.domain.enums.StatutPresence;
import com.mbsc.finapp.dto.drh.PresenceRequest;
import com.mbsc.finapp.dto.drh.PresenceResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.repository.EmployeRepository;
import com.mbsc.finapp.repository.PresenceRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Pointage quotidien (module DRH_PRESENCES). La grille employés × jours est
 * assemblée côté frontend à partir de {@link #listerPeriode} (les seuls
 * jours déjà enregistrés) et de {@link #statutParDefaut} (jour non encore
 * saisi) — cette API ne matérialise jamais les jours par défaut en base.
 */
@Service
@RequiredArgsConstructor
public class PresenceService {

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);
    private static final int JOURS_OUVRABLES_STANDARD = 26;

    private final PresenceRepository repository;
    private final EmployeRepository employeRepository;

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<PresenceResponse> listerPeriode(Integer mois, Integer annee) {
        YearMonth periode = YearMonth.of(annee, mois);
        return repository.findByDateBetween(periode.atDay(1), periode.atEndOfMonth()).stream()
            .map(PresenceResponse::from).toList();
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public PresenceResponse enregistrer(PresenceRequest req) {
        Employe employe = employeRepository.findById(req.employeId())
            .orElseThrow(() -> RessourceIntrouvableException.of("Employe", req.employeId()));
        Presence p = repository.findByEmployeIdAndDate(req.employeId(), req.date())
            .orElseGet(() -> Presence.builder().employe(employe).date(req.date()).build());
        p.setStatut(req.statut());
        p.setMotif(req.statut().requiertMotif() && StringUtils.hasText(req.motif()) ? req.motif() : null);
        p = repository.save(p);
        return PresenceResponse.from(p);
    }

    /** Marque tous les employés actifs présents sur tout le mois (week-ends fériés), motifs effacés. */
    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public List<PresenceResponse> marquerTousPresents(Integer mois, Integer annee) {
        YearMonth periode = YearMonth.of(annee, mois);
        List<Employe> employes = employeRepository.findByActifTrueOrderByNomComplet();
        List<Presence> resultat = new java.util.ArrayList<>();
        for (Employe e : employes) {
            for (LocalDate curseur = periode.atDay(1); !curseur.isAfter(periode.atEndOfMonth()); curseur = curseur.plusDays(1)) {
                final LocalDate jour = curseur;
                Presence p = repository.findByEmployeIdAndDate(e.getId(), jour)
                    .orElseGet(() -> Presence.builder().employe(e).date(jour).build());
                p.setStatut(statutParDefaut(jour));
                p.setMotif(null);
                resultat.add(repository.save(p));
            }
        }
        log.info("Présences réinitialisées à Présent [periode={}/{}, employes={}]", mois, annee, employes.size());
        return resultat.stream().map(PresenceResponse::from).toList();
    }

    /** Statut par défaut d'un jour non encore saisi : férié le week-end, sinon présent. */
    public StatutPresence statutParDefaut(LocalDate date) {
        DayOfWeek jour = date.getDayOfWeek();
        return (jour == DayOfWeek.SATURDAY || jour == DayOfWeek.SUNDAY) ? StatutPresence.FERIE : StatutPresence.PRESENT;
    }

    /** Jours prestés (PRESENT + MISSION) d'un employé sur une période. */
    @Transactional(readOnly = true)
    public long joursPrestes(Long employeId, LocalDate debut, LocalDate fin) {
        return repository.findByEmployeAndDateBetween(employeId, debut, fin).stream()
            .filter(p -> p.getStatut().estPresence())
            .count();
    }

    /** Pourcentage de présence sur la base de {@link #JOURS_OUVRABLES_STANDARD} jours (plafonné à 100). */
    @Transactional(readOnly = true)
    public double pourcentagePresence(Long employeId, LocalDate debut, LocalDate fin) {
        return Math.min(100.0, joursPrestes(employeId, debut, fin) * 100.0 / JOURS_OUVRABLES_STANDARD);
    }
}
