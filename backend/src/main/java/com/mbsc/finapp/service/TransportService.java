package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.*;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.StatutTrajet;
import com.mbsc.finapp.dto.transport.*;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.*;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Module Transport / Flotte : véhicules, trajets, dépenses et suivi des coûts.
 * Une dépense peut être comptabilisée (pièce OHADA : Débit charge / Crédit contrepartie).
 */
@Service
@RequiredArgsConstructor
public class TransportService {

    private static final Logger log = LoggerFactory.getLogger(TransportService.class);

    /**
     * Contrepartie par défaut d'une dépense véhicule : dette fournisseur.
     *
     * <p>C'était auparavant le compte de caisse 571. Comptabiliser une dépense
     * créditait donc directement la caisse, sans créer de transaction de
     * caisse, sans contrôle du solde disponible, et sous le journal
     * « Opérations diverses » — donc sans jamais apparaître dans le journal de
     * caisse. Le solde du compte 571 baissait pendant que le journal du
     * caissier restait muet : son comptage cessait silencieusement de
     * correspondre, et la caisse pouvait même devenir négative.</p>
     *
     * <p>La dépense constate désormais une dette ; son règlement effectif se
     * saisit dans le module de trésorerie concerné, qui applique ses propres
     * contrôles. Le compte de trésorerie est refusé ici, voir
     * {@code refuserComptesTresorerie}.</p>
     */
    private static final String COMPTE_CONTREPARTIE_DEFAUT = "4011";
    /**
     * Compte de charge par défaut si l'utilisateur n'en fournit pas.
     * Le référentiel SYSCOHADA ne comporte pas de compte 611 : les frais de
     * déplacement relèvent de 6181 « Voyages et déplacements ».
     */
    private static final String COMPTE_CHARGE_DEFAUT = "6181";

    private final VehiculeRepository vehiculeRepository;
    private final TrajetRepository trajetRepository;
    private final DepenseVehiculeRepository depenseRepository;
    private final CompteOHADARepository compteRepository;
    private final UserRepository userRepository;
    private final ComptabiliteService comptabilite;
    private final ReferenceGenerator referenceGenerator;
    private final CurrentUserProvider currentUser;

    // ---------------------------------------------------------------------
    // Véhicules
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<VehiculeResponse> listerVehicules() {
        return vehiculeRepository.findAllByOrderByImmatriculationAsc().stream()
            .map(VehiculeResponse::from).toList();
    }

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'ADMIN')")
    @Transactional
    public VehiculeResponse creerVehicule(VehiculeRequest req) {
        if (vehiculeRepository.existsByImmatriculation(req.immatriculation())) {
            throw new IllegalArgumentException(
                "Un véhicule avec l'immatriculation " + req.immatriculation() + " existe déjà");
        }
        Vehicule v = new Vehicule();
        appliquerVehicule(v, req);
        return VehiculeResponse.from(vehiculeRepository.save(v));
    }

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'ADMIN')")
    @Transactional
    public VehiculeResponse modifierVehicule(Long id, VehiculeRequest req) {
        Vehicule v = vehiculeRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Vehicule", id));
        if (!v.getImmatriculation().equals(req.immatriculation())
                && vehiculeRepository.existsByImmatriculation(req.immatriculation())) {
            throw new IllegalArgumentException(
                "Un véhicule avec l'immatriculation " + req.immatriculation() + " existe déjà");
        }
        appliquerVehicule(v, req);
        return VehiculeResponse.from(vehiculeRepository.save(v));
    }

    private void appliquerVehicule(Vehicule v, VehiculeRequest req) {
        v.setImmatriculation(req.immatriculation());
        v.setMarque(req.marque());
        v.setModele(req.modele());
        v.setType(req.type());
        v.setDateAcquisition(req.dateAcquisition());
        v.setActif(req.actif() == null || req.actif());
    }

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public CoutVehiculeResponse coutsVehicule(Long vehiculeId) {
        Vehicule v = vehiculeRepository.findById(vehiculeId)
            .orElseThrow(() -> RessourceIntrouvableException.of("Vehicule", vehiculeId));
        BigDecimal totalDepenses = depenseRepository.totalDepensesVehicule(vehiculeId);
        if (totalDepenses == null) totalDepenses = BigDecimal.ZERO;
        BigDecimal totalDistance = trajetRepository.findAllWithDetails().stream()
            .filter(t -> t.getVehicule() != null && vehiculeId.equals(t.getVehicule().getId()))
            .map(Trajet::getDistanceKm)
            .filter(d -> d != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal coutParKm = totalDistance.signum() == 0
            ? BigDecimal.ZERO
            : totalDepenses.divide(totalDistance, 2, RoundingMode.HALF_UP);
        return new CoutVehiculeResponse(v.getId(), v.getImmatriculation(), totalDepenses, totalDistance, coutParKm);
    }

    // ---------------------------------------------------------------------
    // Trajets
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<TrajetResponse> listerTrajets() {
        return trajetRepository.findAllWithDetails().stream().map(TrajetResponse::from).toList();
    }

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'ADMIN')")
    @Transactional
    public TrajetResponse creerTrajet(TrajetRequest req) {
        Trajet t = Trajet.builder()
            .reference(referenceGenerator.pourTrajet())
            .statut(req.statut() != null ? req.statut() : StatutTrajet.PLANIFIE)
            .build();
        appliquerTrajet(t, req);
        return TrajetResponse.from(trajetRepository.save(t));
    }

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'ADMIN')")
    @Transactional
    public TrajetResponse modifierTrajet(Long id, TrajetRequest req) {
        Trajet t = trajetRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Trajet", id));
        if (req.statut() != null) {
            t.setStatut(req.statut());
        }
        appliquerTrajet(t, req);
        return TrajetResponse.from(trajetRepository.save(t));
    }

    private void appliquerTrajet(Trajet t, TrajetRequest req) {
        Vehicule v = vehiculeRepository.findById(req.vehiculeId())
            .orElseThrow(() -> RessourceIntrouvableException.of("Vehicule", req.vehiculeId()));
        t.setVehicule(v);
        t.setConducteur(chargerUserOuNull(req.conducteurId()));
        t.setDateDepart(req.dateDepart());
        t.setDateArrivee(req.dateArrivee());
        t.setOrigine(req.origine());
        t.setDestination(req.destination());
        t.setDistanceKm(req.distanceKm() == null ? BigDecimal.ZERO : req.distanceKm());
    }

    // ---------------------------------------------------------------------
    // Dépenses véhicule
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<DepenseResponse> listerDepenses() {
        return depenseRepository.findAllWithDetails().stream().map(DepenseResponse::from).toList();
    }

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'ADMIN')")
    @Transactional
    public DepenseResponse creerDepense(DepenseRequest req) {
        User auteur = currentUser.requireUser();
        Vehicule v = vehiculeRepository.findById(req.vehiculeId())
            .orElseThrow(() -> RessourceIntrouvableException.of("Vehicule", req.vehiculeId()));
        Trajet trajet = req.trajetId() == null ? null : trajetRepository.findById(req.trajetId())
            .orElseThrow(() -> RessourceIntrouvableException.of("Trajet", req.trajetId()));

        DepenseVehicule d = DepenseVehicule.builder()
            .reference(referenceGenerator.pourDepense())
            .vehicule(v)
            .trajet(trajet)
            .type(req.type())
            .montant(req.montant())
            .dateDepense(req.dateDepense())
            .compteCharge(resoudreCompteCharge(req.compteChargeNumero()))
            .createdBy(auteur)
            .build();
        return DepenseResponse.from(depenseRepository.save(d));
    }

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'ADMIN')")
    @Transactional
    public DepenseResponse comptabiliser(Long id) {
        DepenseVehicule d = depenseRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("DepenseVehicule", id));
        if (d.getPiece() != null) {
            throw new TransitionInvalideException("Cette dépense est déjà comptabilisée");
        }
        CompteOHADA charge = d.getCompteCharge() != null
            ? d.getCompteCharge() : exigerCompte(COMPTE_CHARGE_DEFAUT);
        refuserComptesTresorerie(charge);
        CompteOHADA contrepartie = exigerCompte(COMPTE_CONTREPARTIE_DEFAUT);

        String libelle = "Dépense véhicule " + d.getVehicule().getImmatriculation()
            + " (" + d.getType() + ") " + d.getReference();
        List<EcritureGrandLivre> lignes = new ArrayList<>();
        lignes.add(ecriture(charge, d.getMontant(), BigDecimal.ZERO, libelle, d.getDateDepense()));
        lignes.add(ecriture(contrepartie, BigDecimal.ZERO, d.getMontant(), libelle, d.getDateDepense()));

        PieceComptable piece = comptabilite.creerPieceInterne(
            JournalComptable.OPERATIONS_DIVERSES, libelle, d.getDateDepense(), lignes, d.getCreatedBy());
        d.setPiece(piece);
        log.info("Dépense véhicule comptabilisée [ref={}, piece={}]", d.getReference(), piece.getReference());
        return DepenseResponse.from(d);
    }

    /**
     * Annule la comptabilisation d'une dépense : la pièce d'origine est
     * extournée (contre-passation) et la dépense redevient non comptabilisée.
     */
    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'ADMIN')")
    @Transactional
    public DepenseResponse annulerComptabilisation(Long id) {
        DepenseVehicule d = depenseRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("DepenseVehicule", id));
        if (d.getPiece() == null) {
            throw new TransitionInvalideException("Cette dépense n'est pas comptabilisée");
        }
        comptabilite.annulerInterne(d.getPiece().getId(), currentUser.requireUser());
        d.setPiece(null);
        log.info("Comptabilisation de la dépense {} annulée (extourne)", d.getReference());
        return DepenseResponse.from(d);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private EcritureGrandLivre ecriture(CompteOHADA compte, BigDecimal debit, BigDecimal credit,
                                        String libelle, LocalDate date) {
        return EcritureGrandLivre.builder()
            .compte(compte).debit(debit).credit(credit).libelle(libelle).dateEcriture(date).build();
    }

    private CompteOHADA resoudreCompteCharge(String numero) {
        if (!StringUtils.hasText(numero)) {
            return null;
        }
        return exigerCompte(numero);
    }

    /**
     * Interdit d'imputer une depense de vehicule directement sur un compte de
     * tresorerie (classe 5 : caisse, banque, mobile money).
     *
     * <p>Une telle imputation deplacerait de l'argent sans passer par le
     * module de tresorerie : ni controle du solde, ni transaction, ni ligne
     * dans le journal du canal concerne. Le rapprochement de caisse
     * deviendrait faux sans que rien ne le signale.</p>
     */
    private void refuserComptesTresorerie(CompteOHADA compte) {
        Integer classe = compte.getClasse();
        if (classe != null && classe == 5) {
            throw new IllegalArgumentException(
                "Le compte " + compte.getNumero() + " est un compte de tresorerie :"
                + " une depense de vehicule ne peut pas y etre imputee directement."
                + " Comptabilisez la depense (dette fournisseur), puis saisissez son reglement"
                + " dans le module Caisse, Banque ou Mobile Money.");
        }
    }

    private CompteOHADA exigerCompte(String numero) {
        return compteRepository.findByNumero(numero)
            .orElseThrow(() -> RessourceIntrouvableException.of("CompteOHADA", numero));
    }

    private User chargerUserOuNull(Long id) {
        if (id == null) {
            return null;
        }
        return userRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("User", id));
    }
}
