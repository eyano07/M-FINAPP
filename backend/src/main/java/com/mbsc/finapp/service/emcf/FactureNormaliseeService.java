package com.mbsc.finapp.service.emcf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbsc.finapp.domain.FactureNormalisee;
import com.mbsc.finapp.domain.FactureNormalisee.Statut;
import com.mbsc.finapp.domain.FactureNormalisee.Type;
import com.mbsc.finapp.domain.ParametresEntreprise;
import com.mbsc.finapp.domain.Vente;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.TypeNotification;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.repository.FactureNormaliseeRepository;
import com.mbsc.finapp.repository.VenteRepository;
import com.mbsc.finapp.service.NotificationService;
import com.mbsc.finapp.service.ParametresEntrepriseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Facture normalisée DGI : enregistre chaque vente validée (et chaque avoir d'annulation) dans le registre, la
 * transmet au dispositif e-MCF, mémorise la réponse fiscale et relance les transmissions en attente.
 *
 * <p>La vente n'est jamais bloquée par une panne du dispositif : la facture reste « en attente » et repart
 * automatiquement (délai croissant), l'administrateur est prévenu après {@value #ECHECS_AVANT_ALERTE} échecs.
 * Le dispositif n'est appelé qu'après validation de la transaction de la vente, hors de toute transaction.</p>
 */
@Service
public class FactureNormaliseeService {

    private static final Logger log = LoggerFactory.getLogger(FactureNormaliseeService.class);
    static final int ECHECS_AVANT_ALERTE = 5;

    private final FactureNormaliseeRepository repository;
    private final VenteRepository ventes;
    private final ParametresEmcfService parametres;
    private final ParametresEntrepriseService entreprise;
    private final FactureNormaliseeMapper mapper;
    private final EmcfSimulateur simulateur;
    private final EmcfHttpClient http;
    private final NotificationService notifications;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public FactureNormaliseeService(FactureNormaliseeRepository repository, VenteRepository ventes,
                                    ParametresEmcfService parametres, ParametresEntrepriseService entreprise,
                                    FactureNormaliseeMapper mapper, EmcfSimulateur simulateur, EmcfHttpClient http,
                                    NotificationService notifications, ObjectMapper json, PlatformTransactionManager tm) {
        this.repository = repository;
        this.ventes = ventes;
        this.parametres = parametres;
        this.entreprise = entreprise;
        this.mapper = mapper;
        this.simulateur = simulateur;
        this.http = http;
        this.notifications = notifications;
        this.json = json;
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---------------------------------------------------------------------
    // Points d'accroche (appelés par VenteService, dans la transaction de la vente)
    // ---------------------------------------------------------------------

    /** Vente validée : crée l'entrée du registre (idempotent) puis transmet après le commit. */
    public void surVenteValidee(Vente vente) {
        if (!parametres.config().actif()) return;
        FactureNormalisee f = repository.findByVenteIdAndType(vente.getId(), Type.VENTE).orElse(null);
        if (f == null) {
            f = repository.save(FactureNormalisee.builder().vente(vente).type(Type.VENTE).statut(Statut.EN_ATTENTE).build());
        }
        if (f.getStatut() == Statut.EN_ATTENTE) transmettreApresCommit(f.getId());
    }

    /**
     * Vente annulée : une facture déjà certifiée ne s'efface pas, elle est compensée par un avoir certifié ; une
     * facture jamais certifiée est simplement classée annulée. Appelé même si le module a été désactivé depuis.
     */
    public void surVenteAnnulee(Vente vente) {
        FactureNormalisee f = repository.findByVenteIdAndType(vente.getId(), Type.VENTE).orElse(null);
        if (f == null) return;
        if (f.getStatut() != Statut.CERTIFIEE) {
            f.setStatut(Statut.ANNULEE);
            f.setDateMaj(Instant.now());
            return;
        }
        FactureNormalisee avoir = repository.findByVenteIdAndType(vente.getId(), Type.AVOIR).orElse(null);
        if (avoir == null) {
            avoir = repository.save(FactureNormalisee.builder().vente(vente).type(Type.AVOIR).origine(f)
                .statut(Statut.EN_ATTENTE).build());
        }
        if (avoir.getStatut() == Statut.EN_ATTENTE) transmettreApresCommit(avoir.getId());
    }

    private void transmettreApresCommit(Long id) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    transmettre(id);
                }
            });
        } else {
            transmettre(id);
        }
    }

    // ---------------------------------------------------------------------
    // Transmission
    // ---------------------------------------------------------------------

    private record Preparation(ConfigEmcf config, EmcfDemande demande, String corps) {}

    /** Transmet une facture en attente au dispositif et enregistre le résultat. Ne lève jamais d'exception. */
    public void transmettre(Long id) {
        Preparation prep;
        try {
            prep = tx.execute(s -> preparer(id));
        } catch (ErreurEmcf e) {
            noterEchec(id, e);
            return;
        } catch (RuntimeException e) {
            noterEchec(id, new ErreurEmcf("Préparation impossible : " + e.getMessage(), false, e));
            return;
        }
        if (prep == null) return;
        try {
            EmcfClient client = prep.config().simulation() ? simulateur : http;
            EmcfReponse r = client.certifier(prep.config(), prep.demande(), prep.corps());
            tx.executeWithoutResult(s -> noterSucces(id, prep.config(), r));
            log.info("Facture normalisee certifiee [id={}, uid={}, mode={}]", id, r.uid(), prep.config().mode());
        } catch (ErreurEmcf e) {
            noterEchec(id, e);
        } catch (RuntimeException e) {
            noterEchec(id, new ErreurEmcf("Erreur inattendue : " + e, true, e));
        }
    }

    private Preparation preparer(Long id) {
        FactureNormalisee f = repository.findById(id).orElse(null);
        if (f == null || f.getStatut() != Statut.EN_ATTENTE) return null;
        ConfigEmcf config = parametres.config();
        if (!config.utilisable()) {
            throw new ErreurEmcf("Dispositif e-MCF non configuré (adresse manquante) : Administration › Facture normalisée.", true, null);
        }
        ParametresEntreprise e = entreprise.obtenirEntite();
        EmcfDemande demande = mapper.construire(f, e, parametres.groupes());
        String corps = EmcfJson.versJson(json, demande);
        f.setRequete(corps);
        f.setMode(config.mode());
        return new Preparation(config, demande, corps);
    }

    private void noterSucces(Long id, ConfigEmcf config, EmcfReponse r) {
        FactureNormalisee f = repository.findById(id).orElseThrow();
        f.setStatut(Statut.CERTIFIEE);
        f.setMode(config.mode());
        f.setUid(r.uid());
        f.setSignature(r.signature());
        f.setNumeroDef(r.numeroDef());
        f.setDateFiscale(r.dateFiscale());
        f.setCodeQr(r.codeQr());
        f.setReponse(r.brut());
        f.setDerniereErreur(null);
        f.setProchaineTentative(null);
        f.setDateMaj(Instant.now());
        repository.save(f);
    }

    private void noterEchec(Long id, ErreurEmcf e) {
        log.warn("Facture normalisee non certifiee [id={}, retentable={}] : {}", id, e.retentable(), e.getMessage());
        tx.executeWithoutResult(s -> repository.findById(id).ifPresent(f -> {
            f.setTentatives(f.getTentatives() + 1);
            f.setDerniereErreur(e.getMessage() == null ? "Erreur inconnue" : abreger(e.getMessage()));
            f.setDateMaj(Instant.now());
            boolean alerte;
            if (e.retentable()) {
                f.setStatut(Statut.EN_ATTENTE);
                f.setProchaineTentative(Instant.now().plus(delai(f.getTentatives())));
                alerte = f.getTentatives() == ECHECS_AVANT_ALERTE;
            } else {
                f.setStatut(Statut.REJETEE);
                f.setProchaineTentative(null);
                alerte = true;
            }
            repository.save(f);
            if (alerte) {
                notifications.notifierRole(RoleType.ADMIN, TypeNotification.FACTURE_NORMALISEE_ECHEC,
                    "Facture normalisée non certifiée",
                    "La facture " + f.getVente().getReference() + " n'a pas pu être certifiée : " + f.getDerniereErreur(),
                    "/ventes/" + f.getVente().getId(), null);
            }
        }));
    }

    static Duration delai(int tentatives) {
        long minutes = Math.min(60L, 1L << Math.min(Math.max(tentatives - 1, 0), 6));
        return Duration.ofMinutes(minutes);
    }

    private static String abreger(String s) {
        return s.length() > 480 ? s.substring(0, 480) : s;
    }

    /** État fiscal d'une vente (facture et avoir éventuel), prêt pour l'API. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<com.mbsc.finapp.dto.emcf.FactureNormaliseeResponse> etat(Long venteId) {
        return repository.findByVenteIdOrderByIdAsc(venteId).stream()
            .map(com.mbsc.finapp.dto.emcf.FactureNormaliseeResponse::from).toList();
    }

    // ---------------------------------------------------------------------
    // Relance
    // ---------------------------------------------------------------------

    /** Retransmet les factures en attente dont le délai est écoulé (aussi les avoirs, même module désactivé). */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void retransmettreEnAttente() {
        List<Long> ids;
        try {
            ids = tx.execute(s -> repository.aRetransmettre(Instant.now(), PageRequest.of(0, 20)).stream()
                .map(FactureNormalisee::getId).toList());
        } catch (RuntimeException e) {
            log.warn("Relance des factures normalisees impossible : {}", e.toString());
            return;
        }
        if (ids != null) ids.forEach(this::transmettre);
    }

    /** Reprise manuelle (bouton « Retransmettre ») : remet en attente une facture refusée ou en retard, puis transmet. */
    public void retransmettreVente(Long venteId) {
        List<Long> ids = tx.execute(s -> repository.findByVenteIdOrderByIdAsc(venteId).stream()
            .filter(f -> f.getStatut() == Statut.EN_ATTENTE || f.getStatut() == Statut.REJETEE)
            .peek(f -> {
                f.setStatut(Statut.EN_ATTENTE);
                f.setProchaineTentative(null);
                f.setDateMaj(Instant.now());
            })
            .map(FactureNormalisee::getId).toList());
        if (ids == null || ids.isEmpty()) {
            if (!ventes.existsById(venteId)) throw RessourceIntrouvableException.of("Vente", venteId);
            return;
        }
        ids.forEach(this::transmettre);
    }
}
