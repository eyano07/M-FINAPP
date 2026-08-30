package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Client;
import com.mbsc.finapp.dto.vente.ClientRequest;
import com.mbsc.finapp.dto.vente.ClientResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.ClientRepository;
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
        String code = req.code().strip();
        if (clientRepository.existsByCodeIgnoreCase(code)) {
            throw new TransitionInvalideException("Un client porte deja le code \"" + code + "\".");
        }
        Client client = clientRepository.save(Client.builder()
            .code(code)
            .nom(req.nom().strip())
            .telephone(req.telephone())
            .email(req.email())
            .adresse(req.adresse())
            .actif(req.actif() == null || req.actif())
            .build());

        log.info("Client cree [code={}, nom={}]", client.getCode(), client.getNom());
        return ClientResponse.from(client);
    }

    @PreAuthorize("hasAnyRole('CAISSIER', 'ADMIN')")
    @Transactional
    public ClientResponse modifier(Long id, ClientRequest req) {
        Client client = clientRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Client", id));

        String code = req.code().strip();
        if (!client.getCode().equalsIgnoreCase(code) && clientRepository.existsByCodeIgnoreCase(code)) {
            throw new TransitionInvalideException("Un client porte deja le code \"" + code + "\".");
        }

        client.setCode(code);
        client.setNom(req.nom().strip());
        client.setTelephone(req.telephone());
        client.setEmail(req.email());
        client.setAdresse(req.adresse());
        if (req.actif() != null) {
            client.setActif(req.actif());
        }
        return ClientResponse.from(clientRepository.save(client));
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
