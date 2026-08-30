package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.Provision;
import com.mbsc.finapp.domain.ProvisionReprise;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.StatutProvision;
import com.mbsc.finapp.dto.provision.ProvisionRequest;
import com.mbsc.finapp.dto.provision.ProvisionResponse;
import com.mbsc.finapp.dto.provision.RepriseRequest;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.ProvisionRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Provisions pour risques et charges et dépréciations (audit OHADA du
 * 18/08/2026, constat A-06).
 *
 * <p><b>Constitution</b> — débit du compte de dotation (68x/69x), crédit du
 * compte de provision (15x/19x/29x…).<br>
 * <b>Reprise</b> — l'inverse : débit du compte de provision, crédit du compte
 * de reprise (79x/77x).</p>
 *
 * <p>Les deux sens passent par {@code creerPieceInterne}, qui garantit
 * l'équilibre, le respect de la date de clôture et le refus des comptes de
 * regroupement — ce service n'a donc aucun de ces contrôles à réimplémenter.</p>
 */
@Service
@RequiredArgsConstructor
public class ProvisionService {

    private static final Logger log = LoggerFactory.getLogger(ProvisionService.class);

    private final ProvisionRepository provisionRepository;
    private final CompteOHADARepository compteRepository;
    private final ComptabiliteService comptabilite;
    private final ReferenceGenerator referenceGenerator;
    private final CurrentUserProvider currentUser;

    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<ProvisionResponse> lister() {
        return provisionRepository.listerAvecComptes().stream()
            .map(p -> ProvisionResponse.from(p, false))
            .toList();
    }

    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public ProvisionResponse consulter(Long id) {
        return ProvisionResponse.from(charger(id), true);
    }

    @PreAuthorize("hasAnyRole('DFIN', 'ADMIN')")
    @Transactional
    public ProvisionResponse constituer(ProvisionRequest req) {
        User auteur = currentUser.requireUser();
        CompteOHADA compteProvision = compteParNumero(req.compteProvisionNumero());
        CompteOHADA compteDotation = compteParNumero(req.compteDotationNumero());

        Provision provision = Provision.builder()
            .reference(referenceGenerator.pourProvision())
            .libelle(req.libelle())
            .compteProvision(compteProvision)
            .compteDotation(compteDotation)
            .montantConstitue(req.montant())
            .montantRepris(BigDecimal.ZERO)
            .statut(StatutProvision.CONSTITUEE)
            .dateConstitution(req.dateConstitution())
            .createdBy(auteur)
            .build();

        String libelle = "Dotation provision " + provision.getReference() + " - " + req.libelle();
        PieceComptable piece = comptabilite.creerPieceInterne(
            JournalComptable.OPERATIONS_DIVERSES, libelle, req.dateConstitution(),
            List.of(
                ligne(compteDotation, req.montant(), BigDecimal.ZERO, libelle, req.dateConstitution()),
                ligne(compteProvision, BigDecimal.ZERO, req.montant(), libelle, req.dateConstitution())
            ), auteur);
        provision.setPieceConstitution(piece);

        Provision saved = provisionRepository.save(provision);
        log.info("Provision constituee [ref={}, montant={}, compte={}]",
            saved.getReference(), req.montant(), compteProvision.getNumero());
        return ProvisionResponse.from(saved, false);
    }

    @PreAuthorize("hasAnyRole('DFIN', 'ADMIN')")
    @Transactional
    public ProvisionResponse reprendre(Long id, RepriseRequest req) {
        Provision provision = charger(id);
        User auteur = currentUser.requireUser();

        BigDecimal restant = provision.soldeRestant();
        if (restant.signum() <= 0) {
            throw new TransitionInvalideException(
                "La provision " + provision.getReference() + " est déjà intégralement reprise.");
        }
        if (req.montant().compareTo(restant) > 0) {
            throw new IllegalArgumentException(
                "Reprise de " + req.montant() + " supérieure au solde restant de la provision ("
                + restant + ").");
        }
        if (req.dateReprise().isBefore(provision.getDateConstitution())) {
            throw new IllegalArgumentException(
                "La reprise ne peut pas être antérieure à la constitution ("
                + provision.getDateConstitution() + ").");
        }

        CompteOHADA compteReprise = compteParNumero(req.compteRepriseNumero());
        String libelle = "Reprise provision " + provision.getReference()
            + (req.motif() == null || req.motif().isBlank() ? "" : " - " + req.motif());

        PieceComptable piece = comptabilite.creerPieceInterne(
            JournalComptable.OPERATIONS_DIVERSES, libelle, req.dateReprise(),
            List.of(
                ligne(provision.getCompteProvision(), req.montant(), BigDecimal.ZERO, libelle, req.dateReprise()),
                ligne(compteReprise, BigDecimal.ZERO, req.montant(), libelle, req.dateReprise())
            ), auteur);

        ProvisionReprise reprise = ProvisionReprise.builder()
            .provision(provision)
            .montant(req.montant())
            .motif(req.motif())
            .dateReprise(req.dateReprise())
            .compteReprise(compteReprise)
            .piece(piece)
            .createdBy(auteur)
            .build();
        provision.getReprises().add(reprise);

        provision.setMontantRepris(provision.getMontantRepris().add(req.montant()));
        provision.setStatut(provision.soldeRestant().signum() <= 0
            ? StatutProvision.REPRISE_TOTALE
            : StatutProvision.REPRISE_PARTIELLE);

        log.info("Provision reprise [ref={}, montant={}, restant={}]",
            provision.getReference(), req.montant(), provision.soldeRestant());
        return ProvisionResponse.from(provisionRepository.save(provision), true);
    }

    private Provision charger(Long id) {
        return provisionRepository.findWithReprisesById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Provision", id));
    }

    private CompteOHADA compteParNumero(String numero) {
        return compteRepository.findByNumero(numero)
            .orElseThrow(() -> RessourceIntrouvableException.of("CompteOHADA", numero));
    }

    private EcritureGrandLivre ligne(CompteOHADA compte, BigDecimal debit, BigDecimal credit,
                                     String libelle, LocalDate date) {
        return EcritureGrandLivre.builder()
            .compte(compte).debit(debit).credit(credit).libelle(libelle).dateEcriture(date).build();
    }
}
