package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.BulletinPaie;
import com.mbsc.finapp.domain.Employe;
import com.mbsc.finapp.domain.ParametresPaie;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.StatutBulletin;
import com.mbsc.finapp.dto.drh.BulletinPaieRequest;
import com.mbsc.finapp.dto.drh.BulletinPaieResponse;
import com.mbsc.finapp.dto.drh.EntreesCalculPaie;
import com.mbsc.finapp.dto.drh.ResultatCalculPaie;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.BulletinPaieRepository;
import com.mbsc.finapp.repository.EmployeRepository;
import com.mbsc.finapp.repository.UserRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Bulletins de paie (module DRH_PAIE) : calcul, workflow BROUILLON -&gt; VALIDE
 * -&gt; clôture du mois, annulation. Le règlement et la comptabilisation de la
 * paie passent ensuite par la note de paie du mois et les notes fiscales (voir
 * {@code PaieNoteService}).
 *
 * <p>Un bulletin clôturé ({@code dateCloture} non nulle) ne peut plus être
 * modifié ni redevenir BROUILLON, sauf réouverture du mois tant qu'aucune note
 * de paie active ne le couvre ({@link #rouvrirPeriode}).</p>
 */
@Service
@RequiredArgsConstructor
public class BulletinPaieService {

    private static final Logger log = LoggerFactory.getLogger(BulletinPaieService.class);

    private final BulletinPaieRepository repository;
    private final EmployeRepository employeRepository;
    private final UserRepository userRepository;
    private final ParametresPaieService parametresPaieService;
    private final PayrollCalculationService calculationService;
    private final ConversionDeviseService conversionDevise;
    private final CurrentUserProvider currentUser;

    @PreAuthorize("hasAnyRole('RESP_DRH', 'DFIN', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<BulletinPaieResponse> listerParPeriode(Integer mois, Integer annee) {
        String drh = nomDrh();
        return repository.findByPeriode(mois, annee).stream().map(b -> BulletinPaieResponse.from(b, drh)).toList();
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'DFIN', 'ADMIN')")
    @Transactional(readOnly = true)
    public BulletinPaieResponse consulter(Long id) {
        return BulletinPaieResponse.from(charger(id), nomDrh());
    }

    /** Calcule sans persister : aperçu utilisé par le formulaire avant enregistrement. */
    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional(readOnly = true)
    public ResultatCalculPaie simuler(BulletinPaieRequest req) {
        Employe employe = chargerEmploye(req.employeId());
        return calculer(req, enfants(employe), req.mois(), req.annee(), req.datePaiement());
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public BulletinPaieResponse creer(BulletinPaieRequest req) {
        Employe employe = chargerEmploye(req.employeId());
        if (!employe.isActif()) {
            throw new TransitionInvalideException(employe.getNomComplet()
                + " est désactivé : réactivez sa fiche avant de lui établir un bulletin.");
        }
        repository.findByEmployeIdAndMoisAndAnnee(employe.getId(), req.mois(), req.annee()).ifPresent(existant -> {
            throw new TransitionInvalideException(
                "Un bulletin existe déjà pour " + employe.getNomComplet() + " sur " + req.mois() + "/" + req.annee()
                + " (référence interne #" + existant.getId() + ") : modifiez-le au lieu d'en créer un nouveau.");
        });

        ResultatCalculPaie resultat = calculer(req, enfants(employe), req.mois(), req.annee(), req.datePaiement());
        BulletinPaie b = BulletinPaie.builder()
            .employe(employe)
            .mois(req.mois())
            .annee(req.annee())
            .nombreEnfants(enfants(employe))
            .datePaiement(req.datePaiement() != null ? req.datePaiement() : LocalDate.of(req.annee(), req.mois(), 28))
            .dateImpression(req.dateImpression())
            .createdBy(currentUser.requireUser())
            .build();
        appliquerSaisies(b, req);
        appliquerResultat(b, resultat);
        b = repository.save(b);
        log.info("Bulletin de paie créé [employe={}, periode={}/{}]", employe.getMatricule(), req.mois(), req.annee());
        return BulletinPaieResponse.from(b, nomDrh());
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public BulletinPaieResponse modifier(Long id, BulletinPaieRequest req) {
        BulletinPaie b = charger(id);
        exigerModifiable(b);
        if (b.getStatut() != StatutBulletin.BROUILLON) {
            throw new TransitionInvalideException("Seul un bulletin BROUILLON peut être modifié (état actuel : "
                + b.getStatut() + ") : repassez-le d'abord en brouillon.");
        }
        // Employé, mois et année d'un bulletin ne changent pas ; le nombre d'enfants reste celui figé à
        // sa création, pour qu'un recalcul donne le même résultat que le bulletin d'origine.
        LocalDate datePaiement = req.datePaiement() != null ? req.datePaiement() : b.getDatePaiement();
        ResultatCalculPaie resultat = calculer(req, b.getNombreEnfants() != null ? b.getNombreEnfants() : 0,
            b.getMois(), b.getAnnee(), datePaiement);
        appliquerSaisies(b, req);
        b.setDatePaiement(datePaiement);
        b.setDateImpression(req.dateImpression());
        appliquerResultat(b, resultat);
        log.info("Bulletin de paie modifié [id={}]", id);
        return BulletinPaieResponse.from(b, nomDrh());
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public BulletinPaieResponse valider(Long id) {
        BulletinPaie b = charger(id);
        if (b.getStatut() != StatutBulletin.BROUILLON) {
            throw new TransitionInvalideException(
                "Seul un bulletin BROUILLON peut être validé (état actuel : " + b.getStatut() + ")");
        }
        if (b.getSalaireNet() == null || b.getSalaireNet().signum() <= 0) {
            throw new TransitionInvalideException("Le net à payer de ce bulletin est nul ou négatif : les retenues "
                + "(avance, prêt) dépassent la rémunération. Corrigez-les avant de valider.");
        }
        b.setStatut(StatutBulletin.VALIDE);
        log.info("Bulletin de paie validé [id={}]", id);
        return BulletinPaieResponse.from(b, nomDrh());
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
        return BulletinPaieResponse.from(b, nomDrh());
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public BulletinPaieResponse annuler(Long id) {
        BulletinPaie b = charger(id);
        exigerModifiable(b);
        b.setStatut(StatutBulletin.ANNULE);
        log.info("Bulletin de paie annulé [id={}]", id);
        return BulletinPaieResponse.from(b, nomDrh());
    }

    /**
     * Supprime définitivement un bulletin annulé. Réservé à l'état ANNULE
     * (jamais clôturé, voir {@link #annuler}, donc jamais de pièce comptable
     * liée) : un bulletin clôturé/comptabilisé n'est jamais détruit, seule sa
     * pièce peut être extournée depuis Pièces comptables.
     */
    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public void supprimer(Long id) {
        BulletinPaie b = charger(id);
        if (b.getStatut() != StatutBulletin.ANNULE) {
            throw new TransitionInvalideException(
                "Seul un bulletin annulé peut être supprimé définitivement (état actuel : " + b.getStatut() + ").");
        }
        if (b.getPieceComptable() != null) {
            throw new TransitionInvalideException(
                "Ce bulletin est lié à la pièce comptable " + b.getPieceComptable().getReference()
                + " : suppression impossible.");
        }
        log.info("Bulletin de paie supprimé définitivement [id={}, employe={}, periode={}/{}]",
            id, b.getEmploye().getMatricule(), b.getMois(), b.getAnnee());
        repository.delete(b);
    }

    /**
     * Clôture la paie d'un mois : verrouille les bulletins VALIDE (marque {@code dateCloture}). Refusée tant
     * qu'un bulletin du mois est en brouillon. Aucune écriture ici : la paie est comptabilisée au paiement
     * de la note de paie du mois (voir {@code PaieNoteService}).
     *
     * @return les bulletins effectivement clôturés
     */
    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public List<BulletinPaieResponse> cloturerPeriode(Integer mois, Integer annee) {
        List<BulletinPaie> bulletins = repository.findByPeriode(mois, annee);
        long brouillons = bulletins.stream().filter(b -> b.getStatut() == StatutBulletin.BROUILLON).count();
        if (brouillons > 0) {
            throw new TransitionInvalideException(brouillons + " bulletin(s) de " + mois + "/" + annee
                + " sont encore en brouillon : validez-les ou annulez-les avant de clôturer la paie.");
        }
        List<BulletinPaie> aClotures = bulletins.stream()
            .filter(b -> b.getStatut() == StatutBulletin.VALIDE && b.getDateCloture() == null)
            .toList();
        if (aClotures.isEmpty()) {
            throw new TransitionInvalideException(
                "Aucun bulletin VALIDE à clôturer pour " + mois + "/" + annee
                + " (validez d'abord chaque bulletin, ou la période est déjà clôturée).");
        }
        Instant maintenant = Instant.now();
        aClotures.forEach(b -> b.setDateCloture(maintenant));
        log.info("Paie clôturée [periode={}/{}, bulletins={}]", mois, annee, aClotures.size());
        String drh = nomDrh();
        return aClotures.stream().map(b -> BulletinPaieResponse.from(b, drh)).toList();
    }

    /**
     * Rouvre la paie d'un mois clôturé pour corriger des bulletins : possible tant qu'aucune note de paie
     * active ne couvre ses bulletins et qu'aucun n'est comptabilisé.
     */
    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public List<BulletinPaieResponse> rouvrirPeriode(Integer mois, Integer annee) {
        List<BulletinPaie> clotures = repository.findByPeriode(mois, annee).stream()
            .filter(b -> b.getDateCloture() != null)
            .toList();
        if (clotures.isEmpty()) {
            throw new TransitionInvalideException("La paie de " + mois + "/" + annee + " n'est pas clôturée.");
        }
        for (BulletinPaie b : clotures) {
            if (b.getPieceComptable() != null) {
                throw new TransitionInvalideException("La paie de " + mois + "/" + annee + " est comptabilisée (pièce "
                    + b.getPieceComptable().getReference() + ") : elle ne peut plus être rouverte.");
            }
            if (b.getNoteFraisPaie() != null) {
                throw new TransitionInvalideException("La note de paie " + b.getNoteFraisPaie().getReference()
                    + " couvre ce mois : annulez-la avant de rouvrir la paie.");
            }
        }
        clotures.forEach(b -> b.setDateCloture(null));
        log.info("Paie rouverte [periode={}/{}, bulletins={}]", mois, annee, clotures.size());
        String drh = nomDrh();
        return clotures.stream().map(b -> BulletinPaieResponse.from(b, drh)).toList();
    }

    // ---------------------------------------------------------------------

    private ResultatCalculPaie calculer(BulletinPaieRequest req, int nombreEnfants, int mois, int annee,
                                        LocalDate datePaiementSaisie) {
        ParametresPaie params = parametresPaieService.get();
        LocalDate datePaiement = datePaiementSaisie != null ? datePaiementSaisie : LocalDate.of(annee, mois, 28);
        java.math.BigDecimal tauxFC = conversionDevise.tauxALaDate(datePaiement);
        if (tauxFC == null || tauxFC.signum() <= 0) {
            // Sans taux, l'IPR (barème en francs congolais) vaudrait 0 sans que rien ne le signale.
            throw new TransitionInvalideException("Aucun taux de change USD/CDF n'est défini au " + datePaiement
                + " : saisissez-le (Administration > Taux de change) avant de calculer la paie.");
        }
        EntreesCalculPaie entrees = new EntreesCalculPaie(
            req.salaireBaseUsd(), req.presencePct(), req.conge(), req.heuresSupplementaires(),
            req.allocationFamiliale(), req.primeDiplome(), req.primeAnciennete(), req.primeRendement(),
            req.avanceSalaire(), req.pret(), nombreEnfants);
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
        if (b.getDateCloture() == null) {
            return;
        }
        String precision = b.getPieceComptable() != null
            ? "comptabilisé, pièce " + b.getPieceComptable().getReference()
            : b.getNoteFraisPaie() != null
                ? "couvert par la note de paie " + b.getNoteFraisPaie().getReference()
                    + " — annulez-la puis rouvrez le mois pour le corriger"
                : "rouvrez le mois pour le corriger";
        throw new TransitionInvalideException("Ce bulletin est déjà clôturé (" + precision + ").");
    }

    private static int enfants(Employe e) {
        return e.getNombreEnfants() != null ? e.getNombreEnfants() : 0;
    }

    /**
     * Nom complet du titulaire actuel du role RESP_DRH, pour la signature du
     * bulletin imprime. Calcule une seule fois par appel de service (pas par
     * bulletin) : c'est une information sur l'entite, pas sur chaque
     * bulletin individuellement. Plusieurs titulaires actifs : le premier
     * trouve fait foi, faute d'un unique "DRH en titre" modelise ailleurs.
     */
    private String nomDrh() {
        return userRepository.findByRoles_NomAndActifTrue(RoleType.RESP_DRH).stream()
            .findFirst()
            .map(u -> (nz(u.getPrenom()) + " " + nz(u.getNom())).trim())
            .filter(s -> !s.isEmpty())
            .orElse(null);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
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
