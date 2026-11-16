package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.Employe;
import com.mbsc.finapp.dto.drh.EmployeRequest;
import com.mbsc.finapp.dto.drh.EmployeResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.CompteOHADARepository;
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
 *
 * <p>Audit DRH du 15/11/2026 : chaque agent reçoit à sa création son propre compte de rémunérations dues sous
 * 42211 (D-02) et son compte d'avances sous 4211 (D-06) ; son compte de prêt (272…) se choisit sur la fiche (D-03).
 * Voir {@code PaieComptabilisationService} et {@code AvanceSalaireService}.</p>
 */
@Service
@RequiredArgsConstructor
public class EmployeService {

    private static final Logger log = LoggerFactory.getLogger(EmployeService.class);

    private final EmployeRepository repository;
    private final CompteOHADARepository compteRepository;

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
        e.setCompteRemuneration(ouvrirCompte(e.getNomComplet()).getNumero());
        e.setCompteAvance(ouvrirCompteAvance(e.getNomComplet()).getNumero());
        e = repository.save(e);
        log.info("Employé créé [matricule={}, nom={}, compte={}, avances={}]", e.getMatricule(), e.getNomComplet(),
            e.getCompteRemuneration(), e.getCompteAvance());
        return EmployeResponse.from(e);
    }

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public EmployeResponse modifier(Long id, EmployeRequest req) {
        Employe e = charger(id);
        appliquer(e, req);
        // Le compte ne change pas ; son intitulé suit le nom de l'agent. Une fiche sans compte en reçoit un.
        if (e.getCompteRemuneration() == null) {
            e.setCompteRemuneration(ouvrirCompte(e.getNomComplet()).getNumero());
        } else {
            compteRepository.findByNumero(e.getCompteRemuneration())
                .filter(c -> c.getParent() != null && COMPTE_REMUNERATIONS.equals(c.getParent().getNumero()))
                .ifPresent(c -> c.setLibelle(libelleCompte(e.getNomComplet())));
        }
        // Même règle pour le compte d'avances (audit DRH du 15/11/2026, D-06).
        if (e.getCompteAvance() == null) {
            e.setCompteAvance(ouvrirCompteAvance(e.getNomComplet()).getNumero());
        } else {
            compteRepository.findByNumero(e.getCompteAvance())
                .filter(c -> c.getParent() != null && COMPTE_AVANCES.equals(c.getParent().getNumero()))
                .ifPresent(c -> c.setLibelle(libelleCompteAvance(e.getNomComplet())));
        }
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
        e.setComptePret(comptePret(req.comptePret()));
    }

    /** Collectif sous lequel s'ouvrent les comptes de rémunérations dues des agents. */
    static final String COMPTE_REMUNERATIONS = PaieComptabilisationService.COMPTE_NET_A_PAYER;

    /** Collectif sous lequel s'ouvrent les comptes d'avances des agents (audit DRH du 15/11/2026, D-06). */
    static final String COMPTE_AVANCES = PaieComptabilisationService.COMPTE_AVANCES;

    /** Ouvre le compte de l'agent au premier numéro libre sous 42211 (422111, 422112…). */
    private CompteOHADA ouvrirCompte(String nom) {
        return ouvrirSousCompte(COMPTE_REMUNERATIONS, "Personnel, rémunérations dues", libelleCompte(nom),
            "ouvert pour l'agent « " + nom + " » : crédité du net à payer de ses bulletins, débité par la note de "
                + "paie qui le règle.");
    }

    /** Ouvre le compte d'avances de l'agent au premier numéro libre sous 4211 (42111, 42112…). */
    private CompteOHADA ouvrirCompteAvance(String nom) {
        return ouvrirSousCompte(COMPTE_AVANCES, "Personnel, avances", libelleCompteAvance(nom),
            "ouvert pour l'agent « " + nom + " » : débité par la note de frais qui lui verse une avance, crédité "
                + "par la retenue de l'avance sur son bulletin.");
    }

    /** Sous-compte au premier numéro libre : numéro du collectif suivi du rang, sans point ni zéro. */
    private CompteOHADA ouvrirSousCompte(String numeroCollectif, String intituleCollectif, String libelle,
                                         String usage) {
        CompteOHADA collectif = compteRepository.findByNumero(numeroCollectif)
            .orElseThrow(() -> new TransitionInvalideException("Le compte " + numeroCollectif
                + " (" + intituleCollectif + ") manque au plan comptable : impossible d'ouvrir le compte de l'agent."));
        for (int rang = 1; rang <= 99_999; rang++) {
            String numero = numeroCollectif + rang;
            if (compteRepository.existsByNumero(numero)) {
                continue;
            }
            return compteRepository.save(CompteOHADA.builder()
                .numero(numero)
                .libelle(libelle)
                .type(collectif.getType())
                .classe(collectif.getClasse())
                .parent(collectif)
                .manuel(true)
                .imputable(true)
                .actif(true)
                .contenu("Sous-compte de " + numeroCollectif + " « " + collectif.getLibelle() + " », " + usage)
                .build());
        }
        throw new IllegalStateException("Plus de numéro libre sous " + numeroCollectif);
    }

    private static String libelleCompte(String nom) {
        String libelle = "Rémunérations dues : " + nom;
        return libelle.length() > 200 ? libelle.substring(0, 200) : libelle;
    }

    private static String libelleCompteAvance(String nom) {
        String libelle = "Avances : " + nom;
        return libelle.length() > 200 ? libelle.substring(0, 200) : libelle;
    }

    /** Compte de prêt saisi sur la fiche : facultatif, sinon un compte 272… existant et imputable. */
    private String comptePret(String saisi) {
        if (!StringUtils.hasText(saisi)) {
            return null;
        }
        String numero = saisi.trim();
        CompteOHADA compte = compteRepository.findByNumero(numero)
            .orElseThrow(() -> new IllegalArgumentException("Compte de prêt introuvable : " + numero + "."));
        if (!numero.startsWith(COMPTE_PRETS)) {
            throw new IllegalArgumentException("Le compte de prêt de l'agent doit être un compte " + COMPTE_PRETS
                + "… « Prêts au personnel » (compte choisi : " + numero + ").");
        }
        if (!compte.isImputable()) {
            throw new IllegalArgumentException("Le compte " + numero + " n'est pas imputable : choisissez un "
                + "sous-compte de détail.");
        }
        return numero;
    }

    /** Racine des prêts au personnel (SYSCOHADA). */
    private static final String COMPTE_PRETS = "272";

    private String genererMatricule() {
        int suivant = repository.dernierSuffixeMatricule() + 1;
        return String.format("MBSC-%03d", suivant);
    }
}
