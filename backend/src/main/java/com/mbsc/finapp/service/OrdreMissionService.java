package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.AgentOrdreMission;
import com.mbsc.finapp.domain.Employe;
import com.mbsc.finapp.domain.OrdreMission;
import com.mbsc.finapp.dto.drh.AgentOrdreMissionInput;
import com.mbsc.finapp.dto.drh.OrdreMissionRequest;
import com.mbsc.finapp.dto.drh.OrdreMissionResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.repository.EmployeRepository;
import com.mbsc.finapp.repository.OrdreMissionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ordres de mission (module DRH_MISSIONS), individuels ou collectifs
 * (plus d'un agent — voir {@code OrdreMission#isCollective}).
 */
@Service
@RequiredArgsConstructor
public class OrdreMissionService {

    private static final Logger log = LoggerFactory.getLogger(OrdreMissionService.class);

    /** Les 26 provinces de la RDC — extraites verbatim de MissionOrderController.PROVINCES (ancien outil). */
    public static final List<String> PROVINCES = List.of(
        "Bas-Uele", "Équateur", "Haut-Katanga", "Haut-Lomami", "Haut-Uele",
        "Ituri", "Kasaï", "Kasaï-Central", "Kasaï-Oriental", "Kinshasa",
        "Kongo-Central", "Kwango", "Kwilu", "Lomami", "Lualaba",
        "Mai-Ndombe", "Maniema", "Mongala", "Nord-Kivu", "Nord-Ubangi",
        "Sankuru", "Sud-Kivu", "Sud-Ubangi", "Tanganyika", "Tshopo", "Tshuapa");

    public static final List<String> MOYENS_TRANSPORT = List.of(
        "Véhicule de la société", "Transport en commun", "Avion", "Location de véhicule");

    private final OrdreMissionRepository repository;
    private final EmployeRepository employeRepository;
    private final OrdreMissionPdfService pdfService;

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<OrdreMissionResponse> lister() {
        return repository.findAllOrderByDateDepartDesc().stream().map(OrdreMissionResponse::from).toList();
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional(readOnly = true)
    public OrdreMissionResponse consulter(Long id) {
        return OrdreMissionResponse.from(charger(id));
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public OrdreMissionResponse creer(OrdreMissionRequest req) {
        valider(req);
        OrdreMission o = OrdreMission.builder()
            .numero(suggererNumero(req.dateDepart()))
            .build();
        appliquer(o, req);
        o = repository.save(o);
        log.info("Ordre de mission créé [numero={}, lieu={}]", o.getNumero(), o.getLieuMission());
        return OrdreMissionResponse.from(o);
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public OrdreMissionResponse modifier(Long id, OrdreMissionRequest req) {
        valider(req);
        OrdreMission o = charger(id);
        appliquer(o, req);
        log.info("Ordre de mission modifié [numero={}]", o.getNumero());
        return OrdreMissionResponse.from(o);
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public void supprimer(Long id) {
        if (!repository.existsById(id)) {
            throw RessourceIntrouvableException.of("OrdreMission", id);
        }
        repository.deleteById(id);
    }

    /** PDF sur papier entête (voir {@code OrdreMissionPdfService}) — chargé dans la même transaction pour que
     * les collections lazy (agents, employé) restent accessibles pendant le rendu. */
    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional(readOnly = true)
    public byte[] genererPdf(Long id) {
        return pdfService.genererPdf(charger(id));
    }

    /** Numérotation mensuelle atomique "NNN/OM/DRH/MM-YYYY", jamais réutilisée même après suppression. */
    @Transactional
    public String suggererNumero(java.time.LocalDate dateDepart) {
        int numero = repository.prochainNumeroSequence(dateDepart.getYear(), dateDepart.getMonthValue());
        return String.format(Locale.ROOT, "%03d/OM/DRH/%02d-%d", numero, dateDepart.getMonthValue(), dateDepart.getYear());
    }

    private void valider(OrdreMissionRequest req) {
        if (req.dateRetour().isBefore(req.dateDepart())) {
            throw erreur("La date de retour (" + req.dateRetour() + ") ne peut pas être antérieure à la date de "
                + "départ (" + req.dateDepart() + ").");
        }
        if (req.agents().size() > 1 && req.superviseurEmployeId() == null) {
            throw erreur("Le superviseur de la mission est obligatoire pour un ordre de mission collectif "
                + "(plus d'un agent).");
        }
        Set<Long> vus = new HashSet<>();
        for (AgentOrdreMissionInput a : req.agents()) {
            boolean employeRenseigne = a.employeId() != null;
            boolean nomLibreRenseigne = StringUtils.hasText(a.nomLibre());
            if (!employeRenseigne && !nomLibreRenseigne) {
                throw erreur("Chaque agent doit être soit un employé enregistré, soit une personne externe "
                    + "identifiée par un nom.");
            }
            if (employeRenseigne && !vus.add(a.employeId())) {
                throw erreur("Un même employé ne peut pas être ajouté deux fois à cet ordre de mission.");
            }
        }
    }

    private void appliquer(OrdreMission o, OrdreMissionRequest req) {
        o.setLieuMission(req.lieuMission());
        o.setDistanceVille(req.distanceVille());
        o.setProvince(req.province());
        o.setTerritoire(req.territoire());
        o.setButMission(req.butMission());
        o.setDureeMission(req.dureeMission());
        o.setDateDepart(req.dateDepart());
        o.setDateRetour(req.dateRetour());
        o.setMoyenTransport(req.moyenTransport());
        o.setFraisMission(req.fraisMission());

        // Superviseur pertinent uniquement en mission collective (voir valider()) : une mission individuelle
        // n'en a pas, meme si le champ a ete renseigne par erreur cote formulaire.
        if (req.agents().size() > 1 && req.superviseurEmployeId() != null) {
            o.setSuperviseur(employeRepository.findById(req.superviseurEmployeId())
                .orElseThrow(() -> RessourceIntrouvableException.of("Employe", req.superviseurEmployeId())));
            o.setSuperviseurCivilite(StringUtils.hasText(req.superviseurCivilite()) ? req.superviseurCivilite() : "Monsieur");
        } else {
            o.setSuperviseur(null);
            o.setSuperviseurCivilite(null);
        }

        o.getAgents().clear();
        for (AgentOrdreMissionInput a : req.agents()) {
            Employe employe = a.employeId() != null
                ? employeRepository.findById(a.employeId())
                    .orElseThrow(() -> RessourceIntrouvableException.of("Employe", a.employeId()))
                : null;
            o.addAgent(AgentOrdreMission.builder()
                .employe(employe)
                .nomLibre(employe == null ? a.nomLibre() : null)
                .nationalite(a.nationalite())
                .numeroPasseport(a.numeroPasseport())
                .fonctionMission(a.fonctionMission())
                .civilite(StringUtils.hasText(a.civilite()) ? a.civilite() : "Monsieur")
                .build());
        }
    }

    private ResponseStatusException erreur(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private OrdreMission charger(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("OrdreMission", id));
    }
}
