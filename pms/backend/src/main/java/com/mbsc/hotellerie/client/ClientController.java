package com.mbsc.hotellerie.client;

import com.mbsc.hotellerie.audit.Journal;
import com.mbsc.hotellerie.securite.Roles;
import com.mbsc.hotellerie.securite.SessionCourante;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Clients de l'hébergement : réception et direction (les pièces d'identité ne sortent que pour elles). */
@RestController
@RequiredArgsConstructor
public class ClientController {

    private final ClientService service;
    private final Journal journal;

    @GetMapping("/clients")
    public List<ClientService.Client> rechercher(@RequestParam(required = false) String q) {
        SessionCourante.exiger(Roles.EXPLOITATION);
        return service.rechercher(q);
    }

    @GetMapping("/clients/{id}")
    public ClientService.Fiche fiche(@PathVariable Long id) {
        SessionCourante.exiger(Roles.EXPLOITATION);
        return service.fiche(id);
    }

    @PostMapping("/clients")
    public ClientService.Client creer(@Valid @RequestBody ClientService.ClientRequest r) {
        SessionCourante.exiger(Roles.EXPLOITATION);
        return service.enregistrer(null, r);
    }

    @PutMapping("/clients/{id}")
    public ClientService.Client modifier(@PathVariable Long id, @Valid @RequestBody ClientService.ClientRequest r) {
        SessionCourante.exiger(Roles.EXPLOITATION);
        return service.enregistrer(id, r);
    }

    @PostMapping("/clients/{id}/lier-finapp")
    public ClientService.Client lier(@PathVariable Long id) {
        return service.lierFinapp(id, SessionCourante.exiger(Roles.ENCAISSEMENT));
    }

    @PostMapping(value = "/clients/{id}/pieces", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ClientService.Piece ajouterPiece(@PathVariable Long id, @RequestPart("fichier") MultipartFile fichier) {
        return service.ajouterPiece(id, fichier, SessionCourante.exiger(Roles.EXPLOITATION));
    }

    @GetMapping("/clients/{id}/pieces/{pieceId}")
    public ResponseEntity<FileSystemResource> piece(@PathVariable Long id, @PathVariable Long pieceId) {
        SessionCourante.exiger(Roles.RECEPTION, Roles.DIRECTION);
        ClientService.Fichier f = service.piece(id, pieceId);
        journal.noter("PIECE_IDENTITE_CONSULTEE", "client " + id, "pièce " + pieceId);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"piece-" + pieceId + "\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .contentType(MediaType.parseMediaType(f.type()))
            .body(new FileSystemResource(f.chemin()));
    }
}
