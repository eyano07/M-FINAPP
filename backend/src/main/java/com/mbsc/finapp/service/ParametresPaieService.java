package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ParametresPaie;
import com.mbsc.finapp.dto.drh.ParametresPaieRequest;
import com.mbsc.finapp.dto.drh.ParametresPaieResponse;
import com.mbsc.finapp.repository.ParametresPaieRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Paramètres de paie globaux (ligne unique). Les valeurs par défaut
 * reproduisent exactement celles de l'outil de paie précédent (PayMBSC),
 * à l'exception du plafond/plancher/réduction familiale de l'IPR qui
 * n'existaient pas côté code (barème simplifié) mais figuraient déjà dans le
 * classeur de référence — voir {@code PayrollCalculationService}.
 */
@Service
@RequiredArgsConstructor
public class ParametresPaieService {

    private static final Logger log = LoggerFactory.getLogger(ParametresPaieService.class);

    private final ParametresPaieRepository repository;

    @Transactional
    public ParametresPaie get() {
        return repository.findById(ParametresPaie.SINGLETON_ID)
            .orElseGet(this::creerParDefaut);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('RESP_DRH', 'DFIN', 'ADMIN')")
    public ParametresPaieResponse consulter() {
        return ParametresPaieResponse.from(get());
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public ParametresPaieResponse enregistrer(ParametresPaieRequest req) {
        ParametresPaie p = get();
        p.setTauxLogement(req.tauxLogement());
        p.setTauxTransport(req.tauxTransport());
        p.setTauxCnssOuvriere(req.tauxCnssOuvriere());
        p.setTauxCnssPatronale(req.tauxCnssPatronale());
        p.setTauxOnem(req.tauxOnem());
        p.setTauxInpp(req.tauxInpp());
        p.setReductionIprParEnfant(req.reductionIprParEnfant());
        p.setPlafondEnfantsIpr(req.plafondEnfantsIpr());
        p.setCalculEnfantsActif(req.calculEnfantsActif());
        p.setPlancherIprFc(req.plancherIprFc());
        p.setJoursOuvrablesStandard(req.joursOuvrablesStandard());
        p.setCnssDeductibleIpr(req.cnssDeductibleIpr());
        p.setComptabiliserPaie(req.comptabiliserPaie());
        p.setSmigJournalierFc(req.smigJournalierFc());
        p.setDiviseurAllocationFamiliale(req.diviseurAllocationFamiliale());
        p.setPlafondRetenuePct(req.plafondRetenuePct());
        p.setPlafondTransportExonereFcJour(req.plafondTransportExonereFcJour());
        p.setHeuresLegalesHebdo(req.heuresLegalesHebdo());
        p.setTauxMajorationHs1(req.tauxMajorationHs1());
        p.setTauxMajorationHs2(req.tauxMajorationHs2());
        p.setTauxMajorationHsFerie(req.tauxMajorationHsFerie());
        p.setDirecteurDrh(req.directeurDrh());
        p.setFonctionDirecteur(req.fonctionDirecteur());
        p.setVilleSignature(req.villeSignature());
        p = repository.save(p);
        log.info("Paramètres de paie mis à jour");
        return ParametresPaieResponse.from(p);
    }

    private ParametresPaie creerParDefaut() {
        ParametresPaie p = ParametresPaie.builder()
            .id(ParametresPaie.SINGLETON_ID)
            .tauxLogement(new BigDecimal("0.30"))
            .tauxTransport(new BigDecimal("0.10"))
            .tauxCnssOuvriere(new BigDecimal("0.05"))
            .tauxCnssPatronale(new BigDecimal("0.13"))
            .tauxOnem(new BigDecimal("0.005"))
            .tauxInpp(new BigDecimal("0.03"))
            .reductionIprParEnfant(new BigDecimal("0.02"))
            .plafondEnfantsIpr(9)
            .plancherIprFc(new BigDecimal("2000.00"))
            .joursOuvrablesStandard(26)
            .directeurDrh("")
            .fonctionDirecteur("Directeur des Ressources Humaines")
            .villeSignature("Lubumbashi")
            .build();
        return repository.save(p);
    }
}
