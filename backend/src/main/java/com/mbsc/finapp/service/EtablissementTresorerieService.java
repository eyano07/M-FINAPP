package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EtablissementTresorerie;
import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.domain.enums.TypeEtablissement;
import com.mbsc.finapp.dto.etablissement.EtablissementRequest;
import com.mbsc.finapp.dto.etablissement.EtablissementResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.EtablissementTresorerieRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Gestion des banques et operateurs mobile money.
 *
 * <p>Chaque etablissement est adosse a un sous-compte OHADA dedie
 * (banques sous 521, mobile money sous 582) : son solde est donc lu
 * directement dans le grand livre. Un etablissement ne peut etre retire
 * que si ce solde est nul, pour ne jamais faire disparaitre de la
 * tresorerie encore engagee.</p>
 */
@Service
@RequiredArgsConstructor
public class EtablissementTresorerieService {

    private static final Logger log = LoggerFactory.getLogger(EtablissementTresorerieService.class);

    /**
     * Prefixe de compte OHADA et sequence d'allocation, par type
     * d'etablissement. Le referentiel SYSCOHADA place la monnaie
     * electronique en 552 (« telephone portable ») et non en 58, qui
     * designe les regies d'avances et accreditifs.
     */
    private static final String PREFIXE_BANQUE = "52";
    private static final String PREFIXE_MOBILE_MONEY = "55";
    private static final String SEQ_BANQUE = "seq_compte_banque";
    private static final String SEQ_MOBILE_MONEY = "seq_compte_mobile_money";

    private final EtablissementTresorerieRepository etablissementRepository;
    private final CompteOHADARepository compteRepository;
    private final EcritureGrandLivreRepository ecritureRepository;

    @PersistenceContext
    private EntityManager em;

    // ---------------------------------------------------------------------
    // Lecture
    // ---------------------------------------------------------------------

    /** Etablissements d'un type donne (ou tous si {@code type} est null), avec leur solde. */
    @PreAuthorize("hasAnyRole('CAISSIER', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<EtablissementResponse> lister(TypeEtablissement type) {
        List<EtablissementTresorerie> etablissements = type == null
            ? etablissementRepository.findAllAvecCompte()
            : etablissementRepository.findByTypeAvecCompte(type);
        return etablissements.stream()
            .map(e -> EtablissementResponse.from(e, solde(e)))
            .toList();
    }

    /** Resout un etablissement du type attendu, ou leve une erreur metier explicite. */
    @Transactional(readOnly = true)
    public EtablissementTresorerie requireEtablissement(Long id, TypeEtablissement typeAttendu) {
        EtablissementTresorerie etablissement = etablissementRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("EtablissementTresorerie", id));
        if (etablissement.getType() != typeAttendu) {
            throw new IllegalArgumentException(
                "L'etablissement \"" + etablissement.getNom() + "\" n'est pas de type " + typeAttendu);
        }
        if (!etablissement.isActif()) {
            throw new TransitionInvalideException(
                "L'etablissement \"" + etablissement.getNom() + "\" est desactive.");
        }
        return etablissement;
    }

    /** Solde du compte dedie (debits - credits), convention des comptes d'actif. */
    private BigDecimal solde(EtablissementTresorerie e) {
        BigDecimal s = ecritureRepository.soldePourCompte(e.getCompte().getNumero());
        return s == null ? BigDecimal.ZERO : s;
    }

    // ---------------------------------------------------------------------
    // Administration
    // ---------------------------------------------------------------------

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public EtablissementResponse creer(EtablissementRequest req) {
        String nom = req.nom().strip();
        if (etablissementRepository.existsByNomIgnoreCaseAndType(nom, req.type())) {
            throw new TransitionInvalideException(
                "Un etablissement \"" + nom + "\" existe deja pour ce type.");
        }

        CompteOHADA compte = creerCompteDedie(nom, req.type());
        EtablissementTresorerie etablissement = etablissementRepository.save(
            EtablissementTresorerie.builder()
                .nom(nom)
                .type(req.type())
                .compte(compte)
                .actif(true)
                .build());

        log.info("Etablissement de tresorerie cree [nom={}, type={}, compte={}]",
            nom, req.type(), compte.getNumero());
        return EtablissementResponse.from(etablissement, BigDecimal.ZERO);
    }

    /**
     * Retire un etablissement. Refuse tant que son compte presente un solde :
     * la tresorerie doit d'abord etre transferee ou soldee.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void supprimer(Long id) {
        EtablissementTresorerie etablissement = etablissementRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("EtablissementTresorerie", id));

        BigDecimal soldeActuel = solde(etablissement);
        if (soldeActuel.signum() != 0) {
            throw new TransitionInvalideException(
                "Impossible de retirer \"" + etablissement.getNom() + "\" : son solde est de "
                + soldeActuel.toPlainString() + " " + ConversionDeviseService.DEVISE_BASE
                + ". Seul un etablissement au solde nul peut etre retire ; "
                + "transferez ou soldez d'abord la tresorerie.");
        }

        // Le compte OHADA est conserve s'il a deja porte des ecritures
        // (historique comptable), simplement desactive.
        CompteOHADA compte = etablissement.getCompte();
        etablissementRepository.delete(etablissement);
        if (ecritureRepository.existsByCompteId(compte.getId())) {
            compte.setActif(false);
            compteRepository.save(compte);
        } else {
            compteRepository.delete(compte);
        }

        log.info("Etablissement de tresorerie retire [nom={}, type={}]",
            etablissement.getNom(), etablissement.getType());
    }

    /**
     * Alloue le prochain numero de compte libre du plan comptable pour le
     * type demande et cree le compte correspondant.
     */
    private CompteOHADA creerCompteDedie(String nom, TypeEtablissement type) {
        boolean estBanque = type == TypeEtablissement.BANQUE;
        String prefixe = estBanque ? PREFIXE_BANQUE : PREFIXE_MOBILE_MONEY;
        String sequence = estBanque ? SEQ_BANQUE : SEQ_MOBILE_MONEY;

        // La sequence peut retomber sur un numero deja utilise par le plan
        // comptable OHADA standard (5221 par exemple) : on avance jusqu'au
        // premier numero reellement libre.
        String numero;
        do {
            Number suffixe = (Number) em.createNativeQuery("SELECT nextval('" + sequence + "')")
                .getSingleResult();
            numero = prefixe + suffixe.longValue();
        } while (compteRepository.existsByNumero(numero));

        String libelle = (estBanque ? "Banque — " : "Mobile Money — ") + nom;
        return compteRepository.save(CompteOHADA.builder()
            .numero(numero)
            .libelle(libelle.length() > 200 ? libelle.substring(0, 200) : libelle)
            .type(TypeCompte.ACTIF)
            .classe(5)
            .manuel(true)
            .imputable(true)
            .actif(true)
            .build());
    }
}
