package com.mbsc.finapp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbsc.finapp.domain.BulletinPaie;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.Employe;
import com.mbsc.finapp.domain.ParametresPaie;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.ProvisionConges;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.BulletinPaieRepository;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EmployeRepository;
import com.mbsc.finapp.repository.PieceComptableRepository;
import com.mbsc.finapp.repository.ProvisionCongesRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provision pour congés payés de fin d'exercice (audit DRH du 15/11/2026, D-05 ; SYSCOHADA : indépendance des
 * exercices, comptes 4281 et 4382).
 *
 * <p>Code du travail de la RDC : au moins un jour ouvrable de congé par mois entier de service, plus un jour ouvrable
 * par période de cinq ans d'ancienneté (paramètres {@code joursCongeParMois}, {@code joursCongeParTranche5Ans}).
 * Pour l'exercice A et chaque employé actif :</p>
 * <ul>
 *   <li>mois entiers de service dans l'exercice, depuis le plus tardif du 1er janvier A et de la date d'embauche,
 *       jusqu'à la date d'arrêté (31/12/A par défaut) ;</li>
 *   <li>jours acquis = mois × joursParMois + ⌊ancienneté / 5⌋ × joursParTranche × mois / 12 ;</li>
 *   <li>droits acquis = jours × salaire de base / jours ouvrables du mois ;</li>
 *   <li>provision = droits − indemnités de congé déjà payées dans l'exercice (bulletins VALIDE de l'année A), jamais
 *       négative, arrondie au centime ; charges sociales = provision × taux CNSS patronal total.</li>
 * </ul>
 * <p>Constitution (DFIN) : pièce au journal PAIE datée de l'arrêté — débit 6613 (national) / 6623 (expatrié), crédit
 * 4281 ; débit 6641 / 6642, crédit 4382 — et son extourne au lendemain, comme les régularisations de fin d'exercice
 * ({@link RegularisationService}). Annulation (DFIN) : extourne des deux pièces.</p>
 */
@Service
@RequiredArgsConstructor
public class ProvisionCongesService {

    private static final Logger log = LoggerFactory.getLogger(ProvisionCongesService.class);

    static final String COMPTE_DETTE_CONGES = "4281";
    static final String COMPTE_CHARGES_CONGES = "4382";

    private final EmployeRepository employeRepository;
    private final BulletinPaieRepository bulletinRepository;
    private final ProvisionCongesRepository repository;
    private final ParametresPaieService parametresPaieService;
    private final ComptabiliteService comptabilite;
    private final CompteOHADARepository compteRepository;
    private final PieceComptableRepository pieceRepository;
    private final PeriodeComptableService periodeService;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper objectMapper;

    /** Calcul d'un agent. */
    public record LigneProvision(Long employeId, String matricule, String nomComplet, boolean expatrie,
                                 LocalDate dateEmbauche, int moisService, int ancienneteAnnees,
                                 BigDecimal joursAcquis, BigDecimal salaireJournalier, BigDecimal droitsAcquis,
                                 BigDecimal congesPayes, BigDecimal provision, BigDecimal charges) {}

    /** Provision enregistrée. */
    public record ProvisionResume(Long id, int annee, LocalDate dateArrete, BigDecimal montantConges,
                                  BigDecimal montantCharges, String piece, String pieceExtourne, String statut,
                                  String creePar, Instant creeLe) {
        static ProvisionResume from(ProvisionConges p) {
            return new ProvisionResume(p.getId(), p.getAnnee(), p.getDateArrete(), p.getMontantConges(),
                p.getMontantCharges(), p.getPiece() != null ? p.getPiece().getReference() : null,
                p.getPieceExtourne() != null ? p.getPieceExtourne().getReference() : null, p.getStatut(),
                p.getCreatedBy() != null ? p.getCreatedBy().getEmail() : null, p.getCreatedAt());
        }
    }

    /** Simulation d'un exercice : détail par agent et totaux, rien n'est écrit. */
    public record Simulation(int annee, LocalDate dateArrete, BigDecimal tauxCharges, List<LigneProvision> lignes,
                             BigDecimal totalDroits, BigDecimal totalCongesPayes, BigDecimal totalProvision,
                             BigDecimal totalCharges, ProvisionResume provisionActive) {}

    @PreAuthorize("hasAnyRole('RESP_DRH', 'DFIN', 'ADMIN')")
    @Transactional(readOnly = true)
    public Simulation simuler(int annee, LocalDate dateArrete) {
        return calculer(annee, arrete(annee, dateArrete));
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'DFIN', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<ProvisionResume> lister() {
        return repository.findAllByOrderByAnneeDescIdDesc().stream().map(ProvisionResume::from).toList();
    }

    /** Constitue la provision de l'exercice : pièce à la date d'arrêté et extourne au lendemain (DFIN). */
    @PreAuthorize("hasRole('DFIN')")
    @Transactional
    public ProvisionResume constituer(int annee, LocalDate dateArrete) {
        LocalDate date = arrete(annee, dateArrete);
        repository.findFirstByAnneeAndStatut(annee, ProvisionConges.ACTIVE).ifPresent(p -> {
            throw new TransitionInvalideException("La provision pour congés payés de " + annee + " est déjà constituée"
                + (p.getPiece() != null ? " (pièce " + p.getPiece().getReference() + ")" : "")
                + " : annulez-la d'abord pour la refaire.");
        });
        Simulation s = calculer(annee, date);
        if (s.totalProvision().signum() <= 0) {
            throw new TransitionInvalideException("Aucun droit à congé à provisionner au " + date
                + " : les congés acquis sont nuls ou déjà payés.");
        }
        User auteur = currentUser.requireUser();
        String libelle = "Provision pour congés payés " + annee + " (arrêté au " + date + ")";
        PieceComptable piece = comptabilite.creerPieceInterne(JournalComptable.PAIE, libelle, date,
            construireLignes(s.lignes(), libelle, date, false), auteur);
        LocalDate extourne = date.plusDays(1);
        String libelleExtourne = "Extourne d'ouverture " + piece.getReference() + " - " + libelle;
        PieceComptable pieceExtourne = comptabilite.creerPieceInterne(JournalComptable.PAIE, libelleExtourne, extourne,
            construireLignes(s.lignes(), libelleExtourne, extourne, true), auteur);
        pieceExtourne.setExtourneOuverture(true);
        pieceExtourne.setPieceOrigine(piece);
        pieceRepository.save(pieceExtourne);

        ProvisionConges p = repository.save(ProvisionConges.builder()
            .annee(annee)
            .dateArrete(date)
            .montantConges(s.totalProvision())
            .montantCharges(s.totalCharges())
            .piece(piece)
            .pieceExtourne(pieceExtourne)
            .statut(ProvisionConges.ACTIVE)
            .detail(enJson(s.lignes()))
            .createdBy(auteur)
            .build());
        log.info("Provision pour congés payés constituée [annee={}, conges={}, charges={}, piece={}]", annee,
            s.totalProvision(), s.totalCharges(), piece.getReference());
        return ProvisionResume.from(p);
    }

    /** Annule une provision : ses deux pièces sont extournées (la date d'arrêté doit être ouverte) (DFIN). */
    @PreAuthorize("hasRole('DFIN')")
    @Transactional
    public ProvisionResume annuler(Long id) {
        ProvisionConges p = repository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("ProvisionConges", id));
        if (!ProvisionConges.ACTIVE.equals(p.getStatut())) {
            throw new TransitionInvalideException("Cette provision pour congés payés est déjà annulée.");
        }
        periodeService.verifierDateOuverte(p.getDateArrete());
        User auteur = currentUser.requireUser();
        for (PieceComptable piece : java.util.Arrays.asList(p.getPieceExtourne(), p.getPiece())) {
            if (piece != null) {
                comptabilite.annulerInterne(piece.getId(), auteur);
            }
        }
        p.setStatut(ProvisionConges.ANNULEE);
        log.info("Provision pour congés payés annulée [id={}, annee={}]", id, p.getAnnee());
        return ProvisionResume.from(p);
    }

    // ---------------------------------------------------------------------

    Simulation calculer(int annee, LocalDate dateArrete) {
        ParametresPaie params = parametresPaieService.get();
        BigDecimal tauxCharges = tauxCharges(params);
        List<LigneProvision> lignes = new ArrayList<>();
        for (Employe e : employeRepository.findByActifTrueOrderByNomComplet()) {
            BigDecimal congesPayes = bulletinRepository.findValidesParEmployeEtAnnee(e.getId(), annee).stream()
                .map(BulletinPaie::getConge).map(v -> v == null ? BigDecimal.ZERO : v)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            LigneProvision l = calculerLigne(e, annee, dateArrete, params, congesPayes, tauxCharges);
            if (l != null) {
                lignes.add(l);
            }
        }
        BigDecimal droits = somme(lignes, LigneProvision::droitsAcquis);
        BigDecimal payes = somme(lignes, LigneProvision::congesPayes);
        BigDecimal provision = somme(lignes, LigneProvision::provision);
        BigDecimal charges = somme(lignes, LigneProvision::charges);
        ProvisionResume active = repository.findFirstByAnneeAndStatut(annee, ProvisionConges.ACTIVE)
            .map(ProvisionResume::from).orElse(null);
        return new Simulation(annee, dateArrete, tauxCharges, lignes, droits, payes, provision, charges, active);
    }

    /**
     * Calcul d'un agent (fonction pure). Nul si l'agent n'a aucun mois entier de service dans l'exercice à la date
     * d'arrêté (embauché après l'arrêté, ou moins d'un mois de service).
     */
    static LigneProvision calculerLigne(Employe e, int annee, LocalDate dateArrete, ParametresPaie params,
                                        BigDecimal congesPayes, BigDecimal tauxCharges) {
        LocalDate debutExercice = LocalDate.of(annee, 1, 1);
        LocalDate embauche = e.getDateEmbauche();
        LocalDate debut = embauche != null && embauche.isAfter(debutExercice) ? embauche : debutExercice;
        if (debut.isAfter(dateArrete)) {
            return null;
        }
        int mois = (int) Period.between(debut, dateArrete.plusDays(1)).toTotalMonths();
        if (mois <= 0) {
            return null;
        }
        int anciennete = embauche != null
            ? Math.max(0, Period.between(embauche, dateArrete).getYears())
            : (e.getAncienneteAnnees() != null ? e.getAncienneteAnnees() : 0);
        BigDecimal parMois = nz(params.getJoursCongeParMois());
        BigDecimal parTranche = nz(params.getJoursCongeParTranche5Ans());
        BigDecimal jours = parMois.multiply(BigDecimal.valueOf(mois))
            .add(BigDecimal.valueOf(anciennete / 5L).multiply(parTranche).multiply(BigDecimal.valueOf(mois))
                .divide(BigDecimal.valueOf(12), 6, RoundingMode.HALF_UP))
            .setScale(2, RoundingMode.HALF_UP);
        int joursOuvrables = params.getJoursOuvrablesStandard() != null && params.getJoursOuvrablesStandard() > 0
            ? params.getJoursOuvrablesStandard() : 26;
        BigDecimal salaire = nz(e.getSalaireBaseUsd());
        BigDecimal salaireJournalier = salaire.divide(BigDecimal.valueOf(joursOuvrables), 2, RoundingMode.HALF_UP);
        BigDecimal droits = jours.multiply(salaire)
            .divide(BigDecimal.valueOf(joursOuvrables), 2, RoundingMode.HALF_UP);
        BigDecimal payes = nz(congesPayes).setScale(2, RoundingMode.HALF_UP);
        BigDecimal provision = droits.subtract(payes).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal charges = provision.multiply(nz(tauxCharges)).setScale(2, RoundingMode.HALF_UP);
        return new LigneProvision(e.getId(), e.getMatricule(), e.getNomComplet(), e.isExpatrie(), embauche, mois,
            anciennete, jours, salaireJournalier, droits, payes, provision, charges);
    }

    /** Taux des charges sociales sur congés : CNSS patronale totale (pensions + prestations familiales + risques). */
    static BigDecimal tauxCharges(ParametresPaie p) {
        BigDecimal branches = nz(p.getTauxCnssPensionPatronale()).add(nz(p.getTauxCnssPf())).add(nz(p.getTauxCnssRp()));
        return branches.signum() > 0 ? branches : nz(p.getTauxCnssPatronale());
    }

    /**
     * Lignes de la pièce : débit 6613 / 6623 et 6641 / 6642 par catégorie de personnel, crédit 4281 et 4382 ; sens
     * inversés pour l'extourne.
     */
    List<EcritureGrandLivre> construireLignes(List<LigneProvision> lignes, String libelle, LocalDate date,
                                              boolean extourne) {
        Map<String, BigDecimal> debits = new LinkedHashMap<>();
        BigDecimal conges = BigDecimal.ZERO;
        BigDecimal charges = BigDecimal.ZERO;
        for (LigneProvision l : lignes) {
            debits.merge(l.expatrie() ? "6623" : "6613", l.provision(), BigDecimal::add);
            debits.merge(l.expatrie() ? "6642" : "6641", l.charges(), BigDecimal::add);
            conges = conges.add(l.provision());
            charges = charges.add(l.charges());
        }
        Map<String, BigDecimal> credits = new LinkedHashMap<>();
        credits.put(COMPTE_DETTE_CONGES, conges);
        credits.put(COMPTE_CHARGES_CONGES, charges);
        List<EcritureGrandLivre> ecritures = new ArrayList<>();
        debits.forEach((numero, m) -> {
            if (m.signum() > 0) ecritures.add(ligne(numero, extourne ? null : m, extourne ? m : null, libelle, date));
        });
        credits.forEach((numero, m) -> {
            if (m.signum() > 0) ecritures.add(ligne(numero, extourne ? m : null, extourne ? null : m, libelle, date));
        });
        return ecritures;
    }

    private EcritureGrandLivre ligne(String numero, BigDecimal debit, BigDecimal credit, String libelle,
                                     LocalDate date) {
        CompteOHADA compte = compteRepository.findByNumero(numero)
            .orElseThrow(() -> new TransitionInvalideException("Le compte " + numero + " manque au plan comptable : "
                + "la provision pour congés payés ne peut pas être écrite (voir la migration V147)."));
        return EcritureGrandLivre.builder()
            .compte(compte)
            .debit(debit != null ? debit : BigDecimal.ZERO)
            .credit(credit != null ? credit : BigDecimal.ZERO)
            .libelle(libelle)
            .dateEcriture(date)
            .devise(ConversionDeviseService.DEVISE_BASE.name())
            .build();
    }

    private static LocalDate arrete(int annee, LocalDate dateArrete) {
        if (annee < 2000 || annee > 2100) {
            throw new IllegalArgumentException("Année invalide : " + annee + ".");
        }
        LocalDate date = dateArrete != null ? dateArrete : LocalDate.of(annee, 12, 31);
        if (date.getYear() != annee) {
            throw new IllegalArgumentException("La date d'arrêté (" + date + ") doit appartenir à l'exercice " + annee
                + ".");
        }
        return date;
    }

    private String enJson(List<LigneProvision> lignes) {
        try {
            return objectMapper.writeValueAsString(lignes);
        } catch (JsonProcessingException e) {
            log.warn("Détail de la provision pour congés non sérialisé : {}", e.getMessage());
            return null;
        }
    }

    private static BigDecimal somme(List<LigneProvision> lignes, java.util.function.Function<LigneProvision, BigDecimal> f) {
        return lignes.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
