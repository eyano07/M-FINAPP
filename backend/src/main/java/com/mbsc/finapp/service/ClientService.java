package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Client;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.dto.vente.ClientRequest;
import com.mbsc.finapp.dto.vente.ClientResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.ClientRepository;
import com.mbsc.finapp.repository.CompteOHADARepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Repertoire des clients.
 *
 * <p>Le rattachement d'une vente a une fiche client reste facultatif :
 * une vente au comptoir peut n'indiquer qu'un nom libre. Le repertoire
 * sert au suivi des creances et evite de ressaisir les coordonnees.</p>
 */
@Service
@RequiredArgsConstructor
public class ClientService {

    private static final Logger log = LoggerFactory.getLogger(ClientService.class);

    private final ClientRepository clientRepository;
    private final CompteOHADARepository compteRepository;

    @PreAuthorize("hasAnyRole('CAISSIER', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<ClientResponse> lister() {
        return clientRepository.findAllByOrderByNomAsc().stream()
            .map(ClientResponse::from)
            .toList();
    }

    @PreAuthorize("hasAnyRole('CAISSIER', 'ADMIN')")
    @Transactional
    public ClientResponse creer(ClientRequest req) {
        // Demande du 15/11/2026 : le code (CLI-0001…) et le compte propre sous 4111 sont attribués ici, plus saisis.
        CompteOHADA compte = ouvrirCompte(req.nom().strip());
        Client client = clientRepository.save(Client.builder()
            .code("PROV-" + java.util.UUID.randomUUID().toString().substring(0, 30))
            .nom(req.nom().strip())
            .telephone(vide(req.telephone()))
            .email(vide(req.email()))
            .adresse(vide(req.adresse()))
            .nif(req.nif() == null || req.nif().isBlank() ? null : req.nif().strip().toUpperCase())
            .compteComptable(compte.getNumero())
            .typeClient(req.typeClient() == null ? "PARTICULIER" : req.typeClient())
            .actif(req.actif() == null || req.actif())
            .build());
        client.setCode(codeLibre(client.getId()));

        log.info("Client cree [code={}, nom={}, compte={}]", client.getCode(), client.getNom(), compte.getNumero());
        return ClientResponse.from(client);
    }

    @PreAuthorize("hasAnyRole('CAISSIER', 'ADMIN')")
    @Transactional
    public ClientResponse modifier(Long id, ClientRequest req) {
        Client client = clientRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Client", id));

        // Le code et le compte ne changent pas ; l'intitulé du compte suit le nom du client.
        client.setNom(req.nom().strip());
        client.setTelephone(vide(req.telephone()));
        client.setEmail(vide(req.email()));
        client.setAdresse(vide(req.adresse()));
        client.setNif(req.nif() == null || req.nif().isBlank() ? null : req.nif().strip().toUpperCase());
        client.setTypeClient(req.typeClient() == null ? "PARTICULIER" : req.typeClient());
        if (req.actif() != null) {
            client.setActif(req.actif());
        }
        if (client.getCompteComptable() == null) {
            client.setCompteComptable(ouvrirCompte(client.getNom()).getNumero());
        } else {
            compteRepository.findByNumero(client.getCompteComptable())
                .filter(c -> c.getParent() != null && COMPTE_CLIENTS.equals(c.getParent().getNumero()))
                .ifPresent(c -> c.setLibelle(libelleCompte(client.getNom())));
        }
        return ClientResponse.from(clientRepository.save(client));
    }

    /** Collectif sous lequel s'ouvrent les comptes propres des clients. */
    static final String COMPTE_CLIENTS = VenteService.COMPTE_CLIENTS;

    /** Premier code CLI-0001… libre, à partir du numéro de la fiche. */
    private String codeLibre(Long id) {
        long n = id;
        String code = String.format("CLI-%04d", n);
        while (clientRepository.existsByCodeIgnoreCase(code)) {
            code = String.format("CLI-%04d", ++n);
        }
        return code;
    }

    /** Ouvre le compte propre du client au premier numéro libre sous 4111 (41111, 41112…). */
    private CompteOHADA ouvrirCompte(String nom) {
        CompteOHADA collectif = compteRepository.findByNumero(COMPTE_CLIENTS)
            .orElseThrow(() -> new TransitionInvalideException("Le compte " + COMPTE_CLIENTS
                + " (Clients) manque au plan comptable : impossible d'ouvrir le compte du client."));
        for (int rang = 1; rang <= 99_999; rang++) {
            String numero = COMPTE_CLIENTS + rang;
            if (compteRepository.existsByNumero(numero)) {
                continue;
            }
            return compteRepository.save(CompteOHADA.builder()
                .numero(numero)
                .libelle(libelleCompte(nom))
                .type(collectif.getType())
                .classe(collectif.getClasse())
                .parent(collectif)
                .manuel(true)
                .imputable(true)
                .actif(true)
                .build());
        }
        throw new IllegalStateException("Plus de numéro libre sous " + COMPTE_CLIENTS);
    }

    private static String libelleCompte(String nom) {
        String libelle = "Clients : " + nom;
        return libelle.length() > 200 ? libelle.substring(0, 200) : libelle;
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** Resout un client existant, utilise par le module Vente. */
    @Transactional(readOnly = true)
    public Client requireClient(Long id) {
        Client client = clientRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Client", id));
        if (!client.isActif()) {
            throw new TransitionInvalideException(
                "Le client \"" + client.getNom() + "\" est desactive.");
        }
        return client;
    }
}
