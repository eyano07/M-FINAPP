package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Employe;
import com.mbsc.finapp.dto.drh.EmployeRequest;
import com.mbsc.finapp.dto.drh.EmployeResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.repository.EmployeRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

/**
 * Personnel MBSC (module DRH_PERSONNEL). Le matricule (format hérité
 * "MBSC-NNN", conservé pour continuité avec les dossiers physiques déjà en
 * circulation) est toujours généré côté serveur, jamais saisi.
 *
 * <p>Un employé n'est jamais supprimé : {@link #desactiver} bascule
 * {@code actif=false}, seul moyen de le retirer des listes courantes sans
 * casser l'historique (bulletins, présences, missions) qui le référence.</p>
 */
@Service
@RequiredArgsConstructor
public class EmployeService {

    private static final Logger log = LoggerFactory.getLogger(EmployeService.class);

    private final EmployeRepository repository;

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<EmployeResponse> lister() {
        return repository.findByActifTrueOrderByNomComplet().stream().map(EmployeResponse::from).toList();
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<EmployeResponse> rechercher(String recherche) {
        if (!StringUtils.hasText(recherche)) {
            return lister();
        }
        return repository.rechercher(recherche.trim()).stream().map(EmployeResponse::from).toList();
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional(readOnly = true)
    public EmployeResponse consulter(Long id) {
        return EmployeResponse.from(charger(id));
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public EmployeResponse creer(EmployeRequest req) {
        Employe e = Employe.builder()
            .matricule(genererMatricule())
            .build();
        appliquer(e, req);
        e = repository.save(e);
        log.info("Employé créé [matricule={}, nom={}]", e.getMatricule(), e.getNomComplet());
        return EmployeResponse.from(e);
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public EmployeResponse modifier(Long id, EmployeRequest req) {
        Employe e = charger(id);
        appliquer(e, req);
        log.info("Employé modifié [matricule={}]", e.getMatricule());
        return EmployeResponse.from(e);
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public EmployeResponse desactiver(Long id) {
        Employe e = charger(id);
        e.setActif(false);
        log.info("Employé désactivé [matricule={}]", e.getMatricule());
        return EmployeResponse.from(e);
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public EmployeResponse reactiver(Long id) {
        Employe e = charger(id);
        e.setActif(true);
        log.info("Employé réactivé [matricule={}]", e.getMatricule());
        return EmployeResponse.from(e);
    }

    private Employe charger(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Employe", id));
    }

    private void appliquer(Employe e, EmployeRequest req) {
        e.setNomComplet(req.nomComplet());
        e.setCategorie(req.categorie());
        e.setPoste(req.poste());
        e.setAffectation(req.affectation());
        e.setEmail(req.email());
        e.setTelephone(req.telephone());
        e.setDateEmbauche(req.dateEmbauche());
        e.setDateNaissance(req.dateNaissance());
        e.setSalaireBaseUsd(req.salaireBaseUsd());
        e.setSituationFamiliale(req.situationFamiliale());
        e.setNombreEnfants(req.nombreEnfants() != null ? req.nombreEnfants() : 0);
        e.setDiplome(req.diplome());
        e.setAncienneteAnnees(req.ancienneteAnnees() != null ? req.ancienneteAnnees() : 0);
        e.setRendementPct(req.rendementPct() != null ? req.rendementPct() : BigDecimal.ZERO);
        e.setConforme(req.conforme());
        e.setSuperviseur(req.superviseur());
        e.setExpatrie(req.expatrie());
    }

    private String genererMatricule() {
        int suivant = repository.dernierSuffixeMatricule() + 1;
        return String.format("MBSC-%03d", suivant);
    }
}
