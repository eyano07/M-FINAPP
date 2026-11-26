package com.mbsc.finapp.controller;

import com.mbsc.finapp.domain.enums.ModeInstance;
import com.mbsc.finapp.dto.instance.PingResponse;
import com.mbsc.finapp.service.StorageService;
import com.mbsc.finapp.dto.instance.ActionDistanteTransfert;
import com.mbsc.finapp.dto.instance.ResultatActionDistante;
import com.mbsc.finapp.service.instance.ActionsDistantesService;
import com.mbsc.finapp.service.instance.EmpreintesService;
import com.mbsc.finapp.service.instance.InstanceService;
import com.mbsc.finapp.service.instance.InstantaneService;
import com.mbsc.finapp.service.instance.ReceptionReplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Appels du serveur local vers le serveur en ligne (signes avec la cle d'appairage : voir CleReplicationFilter). Jamais
 * appeles par un navigateur. Les corps volumineux (lots, audit, resynchronisation) sont du JSON compresse (gzip).
 */
@RestController
@RequestMapping("/replication")
@RequiredArgsConstructor
@PreAuthorize("hasRole('REPLICATION')")
public class ReplicationController {

    private final InstanceService instance;
    private final ReceptionReplicationService reception;
    private final InstantaneService instantane;
    private final EmpreintesService empreintes;
    private final StorageService storage;
    private final ActionsDistantesService actions;

    /** Joignabilite, cle acceptee, version du schema et heure du serveur en ligne (ecart d'horloge). */
    @GetMapping("/ping")
    public PingResponse ping() {
        var p = instance.etat();
        return new PingResponse(instance.versionSchema(), p.getMode().name(), p.getAppairageId(), System.currentTimeMillis());
    }

    /** Le serveur local devient principal : ce serveur passe en copie (saisies gelees). */
    @PostMapping("/appairer")
    public PingResponse appairer(@RequestBody(required = false) Map<String, String> corps) {
        reception.appairer(corps == null ? null : corps.get("serveur"));
        return ping();
    }

    /** Instantane complet (gzip), pour le demarrage du serveur local : seulement une fois les saisies gelees ici. */
    @GetMapping("/instantane")
    public void instantane(HttpServletResponse response) throws IOException {
        if (instance.mode() != ModeInstance.MIROIR) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Instantané refusé : le serveur n'est pas encore en copie.");
        }
        response.setContentType("application/gzip");
        instantane.exporter(response.getOutputStream());
        response.flushBuffer();
    }

    /** Un fichier stocke ici (demarrage du serveur local : pieces jointes, photos, logo). */
    @GetMapping("/fichier")
    public void fichier(@RequestParam String chemin, HttpServletResponse response) throws IOException {
        if (!storage.existe(chemin)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fichier absent : " + chemin);
        }
        response.setContentType("application/octet-stream");
        try (InputStream in = storage.charger(chemin).getInputStream()) {
            in.transferTo(response.getOutputStream());
        }
        response.flushBuffer();
    }

    @PostMapping("/demarrage-termine")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void demarrageTermine() {
        instance.noterSucces();
    }

    @PostMapping("/annuler-appairage")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void annulerAppairage() {
        reception.annulerAppairage();
    }

    /** Le serveur local desactive le mode local : ce serveur redevient principal, l'appairage est revoque. */
    @PostMapping("/liberer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void liberer() {
        reception.liberer();
    }

    /** Lot de changements du journal du serveur local (gzip). */
    @PostMapping("/lot")
    public ReceptionReplicationService.ResultatLot lot(HttpServletRequest request) throws IOException {
        return reception.appliquerLot(request.getInputStream().readAllBytes());
    }

    /** Fichier cite par une ligne recopiee (corps = contenu brut). */
    @PostMapping("/fichiers")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void fichiers(@RequestParam String chemin, HttpServletRequest request) throws IOException {
        reception.recevoirFichier(chemin, request.getInputStream());
    }

    /** Lignes du journal d'audit du serveur local (gzip, tableau de textes). */
    @PostMapping("/audit")
    public Map<String, Integer> audit(HttpServletRequest request) throws IOException {
        return Map.of("recues", reception.recevoirAudit(request.getInputStream().readAllBytes()));
    }

    /** Empreintes des tables recopiees (controle de derive). */
    @GetMapping("/empreintes")
    public Map<String, String> empreintes() {
        return empreintes.calculer();
    }

    /** Resynchronisation complete : instantane du serveur local (gzip) qui remplace les donnees recopiees ici. */
    @PostMapping("/resynchronisation")
    public ReceptionReplicationService.ResultatLot resynchronisation(HttpServletRequest request) throws IOException {
        return reception.resynchroniser(request.getInputStream().readAllBytes());
    }

    /** Actions faites sur la copie en ligne, en attente du serveur local. */
    @GetMapping("/actions")
    public List<ActionDistanteTransfert> actions() {
        return actions.enAttente();
    }

    /** Piece jointe d'une nouvelle note faite en ligne (numero dans la liste de l'action). */
    @org.springframework.web.bind.annotation.GetMapping("/actions/{uuid}/fichiers/{numero}")
    public void fichierAction(@org.springframework.web.bind.annotation.PathVariable String uuid,
                              @org.springframework.web.bind.annotation.PathVariable int numero,
                              HttpServletResponse response) throws IOException {
        response.setContentType("application/octet-stream");
        actions.ecrireFichier(uuid, numero, response.getOutputStream());
        response.flushBuffer();
    }

    /** Resultats de l'application des actions par le serveur local. */
    @PostMapping("/actions/resultats")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resultats(@RequestBody List<ResultatActionDistante> resultats) {
        actions.noterResultats(resultats);
    }
}
