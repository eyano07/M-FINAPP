package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.instance.ActionDistanteResponse;
import com.mbsc.finapp.dto.notes.NoteFraisRequest;
import com.mbsc.finapp.service.instance.ActionsDistantesService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Copie en ligne d'un serveur local : actions faites ici et relayees au serveur local (voir ActionsDistantesService).
 * Les validations passent par les routes habituelles des notes ; seule la creation d'une note a sa route, car elle
 * part en un seul envoi (note, pieces jointes, soumission).
 */
@RestController
@RequestMapping("/actions-distantes")
@RequiredArgsConstructor
public class ActionsDistantesController {

    private final ActionsDistantesService service;

    /** Nouvelle note (partie « note » en JSON, pieces jointes « fichiers »), soumise aussitot si {@code soumettre}. */
    @PostMapping(value = "/notes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ActionDistanteResponse creerNote(@Valid @RequestPart("note") NoteFraisRequest note,
                                            @RequestPart(value = "fichiers", required = false) List<MultipartFile> fichiers,
                                            @RequestParam(defaultValue = "false") boolean soumettre) {
        return service.enregistrerCreationNote(note, fichiers, soumettre);
    }

    /** Mes actions faites en ligne : en attente, et traitees ces 7 derniers jours. */
    @GetMapping
    public List<ActionDistanteResponse> miennes() {
        return service.miennes();
    }

    /** Actions faites en ligne sur une note (en attente, et traitees ces 2 derniers jours). */
    @GetMapping("/note/{noteId}")
    public List<ActionDistanteResponse> pourNote(@PathVariable Long noteId) {
        return service.pourNote(noteId);
    }
}
