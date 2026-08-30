package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.BulletinPaie;
import com.mbsc.finapp.domain.Employe;
import com.mbsc.finapp.domain.ParametresPaie;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.StatutBulletin;
import com.mbsc.finapp.dto.drh.BulletinPaieRequest;
import com.mbsc.finapp.dto.drh.BulletinPaieResponse;
import com.mbsc.finapp.dto.drh.EntreesCalculPaie;
import com.mbsc.finapp.dto.drh.ResultatCalculPaie;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.BulletinPaieRepository;
import com.mbsc.finapp.repository.EmployeRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Bulletins de paie (module DRH_PAIE) : calcul, workflow BROUILLON -&gt; VALIDE
 * -&gt; clôture (une pièce comptable BROUILLON par bulletin, voir
 * {@code PaieComptabilisationService}), annulation.
 *
 * <p>Un bulletin déjà clôturé (pièce liée non nulle) ne peut plus être
 * modifié ni redevenir BROUILLON : sa pièce suit désormais son propre cycle
 * de vie dans {@code ComptabiliteService} (comptabiliser/annuler), écran
 * Pièces comptables — pas de logique dupliquée ici.</p>
 */
@Service
@RequiredArgsConstructor
public class BulletinPaieService {

    private static final Logger log = LoggerFactory.getLogger(BulletinPaieService.class);

    private final BulletinPaieRepository repository;
    private final EmployeRepository employeRepository;
    private final ParametresPaieService parametresPaieService;
    private final PayrollCalculationService calculationService;
    private final ConversionDeviseService conversionDevise;
    private final PaieComptabilisationService comptabilisationService;
    private final ComptabiliteService comptabiliteService;
    private final CurrentUserProvider currentUser;

    @PreAuthorize("hasAnyRole('RESP_DRH', 'DFIN', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<BulletinPaieResponse> listerParPeriode(Integer mois, Integer annee) {
        return repository.findByPeriode(mois, annee).stream().map(BulletinPaieResponse::from).toList();
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'DFIN', 'ADMIN')")
    @Transactional(readOnly = true)
    public BulletinPaieResponse consulter(Long id) {
        return BulletinPaieResponse.from(charger(id));
    }

    /** Calcule sans persister : aperçu utilisé par le formulaire avant enregistrement. */
    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional(readOnly = true)
    public ResultatCalculPaie simuler(BulletinPaieRequest req) {
        Employe employe = chargerEmploye(req.employeId());
        return calculer(req, employe);
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public BulletinPaieResponse creer(BulletinPaieRequest req) {
        Employe employe = chargerEmploye(req.employeId());
        repository.findByEmployeIdAndMoisAndAnnee(employe.getId(), req.mois(), req.annee()).ifPresent(existant -> {
            throw new TransitionInvalideException(
                "Un bulletin existe déjà pour " + employe.getNomComplet() + " sur " + req.mois() + "/" + req.annee()
                + " (référence interne #" + existant.getId() + ") : modifiez-le au lieu d'en créer un nouveau.");
        });

        ResultatCalculPaie resultat = calculer(req, employe);
        BulletinPaie b = BulletinPaie.builder()
            .employe(employe)
            .mois(req.mois())
            .annee(req.annee())
            .nombreEnfants(employe.getNombreEnfants() != null ? employe.getNombreEnfants() : 0)
            .datePaiement(req.datePaiement() != null ? req.datePaiement() : LocalDate.of(req.annee(), req.mois(), 28))
            .createdBy(currentUser.requireUser())
            .build();
        appliquerSaisies(b, req);
        appliquerResultat(b, resultat);
        b = repository.save(b);
        log.info("Bulletin de paie créé [employe={}, periode={}/{}]", employe.getMatricule(), req.mois(), req.annee());
        return BulletinPaieResponse.from(b);
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public BulletinPaieResponse modifier(Long id, BulletinPaieRequest req) {
        BulletinPaie b = charger(id);
        exigerModifiable(b);
        ResultatCalculPaie resultat = calculer(req, b.getEmploye());
        appliquerSaisies(b, req);
        b.setDatePaiement(req.datePaiement() != null ? req.datePaiement() : b.getDatePaiement());
        appliquerResultat(b, resultat);
        log.info("Bulletin de paie modifié [id={}]", id);
        return BulletinPaieResponse.from(b);
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public BulletinPaieResponse valider(Long id) {
        BulletinPaie b = charger(id);
        if (b.getStatut() != StatutBulletin.BROUILLON) {
            throw new TransitionInvalideException(
                "Seul un bulletin BROUILLON peut être validé (état actuel : " + b.getStatut() + ")");
        }
        b.setStatut(StatutBulletin.VALIDE);
        log.info("Bulletin de paie validé [id={}]", id);
        return BulletinPaieResponse.from(b);
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public BulletinPaieResponse devalider(Long id) {
        BulletinPaie b = charger(id);
        exigerModifiable(b);
        if (b.getStatut() != StatutBulletin.VALIDE) {
            throw new TransitionInvalideException(
                "Seul un bulletin VALIDE peut repasser en brouillon (état actuel : " + b.getStatut() + ")");
        }
        b.setStatut(StatutBulletin.BROUILLON);
        log.info("Bulletin de paie repassé en brouillon [id={}]", id);
        return BulletinPaieResponse.from(b);
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public BulletinPaieResponse annuler(Long id) {
        BulletinPaie b = charger(id);
        exigerModifiable(b);
        b.setStatut(StatutBulletin.ANNULE);
        log.info("Bulletin de paie annulé [id={}]", id);
        return BulletinPaieResponse.from(b);
    }

    /**
     * Clôture la paie d'une période : pour chaque bulletin VALIDE de
     * {@code (mois, annee)}, construit ses écritures et poste sa propre
     * pièce BROUILLON (une pièce par employé, jamais agrégée — le DFIN les
     * comptabilise ensuite une à une dans l'écran Pièces comptables
     * existant, sans nouvel écran de validation).
     *
     * @return les bulletins effectivement clôturés
     */
    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public List<BulletinPaieResponse> cloturerPeriode(Integer mois, Integer annee) {
        List<BulletinPaie> aClotures = repository.findByPeriodeAndStatut(mois, annee, StatutBulletin.VALIDE);
        if (aClotures.isEmpty()) {
            throw new TransitionInvalideException(
                "Aucun bulletin VALIDE à clôturer pour " + mois + "/" + annee
                + " (validez d'abord chaque bulletin avant de clôturer la période).");
        }
        User auteur = currentUser.requireUser();
        for (BulletinPaie b : aClotures) {
            PieceComptable piece = comptabiliteService.creerPieceInterneBrouillon(
                JournalComptable.OPERATIONS_DIVERSES,
                "Paie " + mois + "/" + annee + " — " + b.getEmploye().getNomComplet(),
                b.getDatePaiement(),
                comptabilisationService.construireLignes(b),
                auteur);
            b.setPieceComptable(piece);
        }
        log.info("Paie clôturée [periode={}/{}, bulletins={}]", mois, annee, aClotures.size());
        return aClotures.stream().map(BulletinPaieResponse::from).toList();
    }

    // ---------------------------------------------------------------------

    private ResultatCalculPaie calculer(BulletinPaieRequest req, Employe employe) {
        ParametresPaie params = parametresPaieService.get();
        LocalDate datePaiement = req.datePaiement() != null ? req.datePaiement() : LocalDate.of(req.annee(), req.mois(), 28);
        java.math.BigDecimal tauxFC = conversionDevise.tauxALaDate(datePaiement);
        EntreesCalculPaie entrees = new EntreesCalculPaie(
            req.salaireBaseUsd(), req.presencePct(), req.conge(), req.heuresSupplementaires(),
            req.allocationFamiliale(), req.primeDiplome(), req.primeAnciennete(), req.primeRendement(),
            req.avanceSalaire(), req.pret(),
            employe.getNombreEnfants() != null ? employe.getNombreEnfants() : 0);
        return calculationService.calculer(entrees, params, tauxFC);
    }

    private void appliquerSaisies(BulletinPaie b, BulletinPaieRequest req) {
        b.setSalaireBaseUsd(req.salaireBaseUsd());
        b.setPresencePct(req.presencePct());
        b.setConge(nz(req.conge()));
        b.setHeuresSupplementaires(nz(req.heuresSupplementaires()));
        b.setAllocationFamiliale(nz(req.allocationFamiliale()));
        b.setPrimeDiplome(nz(req.primeDiplome()));
        b.setPrimeAnciennete(nz(req.primeAnciennete()));
        b.setPrimeRendement(nz(req.primeRendement()));
        b.setAvanceSalaire(nz(req.avanceSalaire()));
        b.setPret(nz(req.pret()));
    }

    private void appliquerResultat(BulletinPaie b, ResultatCalculPaie r) {
        b.setSalaireBrut(r.salaireBrut());
        b.setIndemniteLogement(r.indemniteLogement());
        b.setIndemniteTransport(r.indemniteTransport());
        b.setBaseImposableInpp(r.baseImposableInpp());
        b.setBaseImposableInss(r.baseImposableInss());
        b.setBaseImposableIpr(r.baseImposableIpr());
        b.setCnssOuvriere(r.cnssOuvriere());
        b.setCnssPatronale(r.cnssPatronale());
        b.setOnem(r.onem());
        b.setTotalInss(r.totalInss());
        b.setInpp(r.inpp());
        b.setIpr(r.ipr());
        b.setSalaireNet(r.salaireNet());
        b.setTauxChangeApplique(r.tauxChangeApplique());
        b.setNetFc(r.netFc());
    }

    private void exigerModifiable(BulletinPaie b) {
        if (b.getPieceComptable() != null) {
            throw new TransitionInvalideException(
                "Ce bulletin est déjà clôturé (pièce " + b.getPieceComptable().getReference() + ") : "
                + "annulez la pièce depuis Pièces comptables pour le rouvrir.");
        }
    }

    private BulletinPaie charger(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("BulletinPaie", id));
    }

    private Employe chargerEmploye(Long id) {
        return employeRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Employe", id));
    }

    private static java.math.BigDecimal nz(java.math.BigDecimal v) {
        return v == null ? java.math.BigDecimal.ZERO : v;
    }
}
