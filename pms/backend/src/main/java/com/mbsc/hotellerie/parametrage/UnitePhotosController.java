package com.mbsc.hotellerie.parametrage;

import com.fasterxml.jackson.databind.JsonNode;
import com.mbsc.hotellerie.commun.PhotoService;
import com.mbsc.hotellerie.securite.Roles;
import com.mbsc.hotellerie.securite.SessionCourante;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Photos des unités (chambre, unité de motel, appartement) : la direction les ajoute, les clients les voient sur la
 * réservation en ligne (3 au moins par type d'unité). Patrimoine de l'unité : les biens que la logistique de M-FINAPP
 * lui a affectés.
 */
@RestController
@RequiredArgsConstructor
public class UnitePhotosController {

    private final PhotoService photos;
    private final ParametrageService parametrage;
    private final UnitesFinappService finapp;

    @GetMapping("/unites/{id}/photos")
    public List<PhotoService.Photo> photos(@PathVariable Long id) {
        SessionCourante.exiger();
        return photos.photos("UNITE", id);
    }

    @PostMapping(value = "/unites/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PhotoService.Photo ajouter(@PathVariable Long id, @RequestPart("fichier") MultipartFile fichier,
                                      @RequestParam(required = false) String legende) {
        var session = SessionCourante.exiger(Roles.PARAMETRAGE);
        parametrage.unite(id);
        return photos.ajouter("UNITE", id, fichier, legende, session);
    }

    @GetMapping("/unites/{id}/patrimoine")
    public JsonNode patrimoine(@PathVariable Long id) {
        var session = SessionCourante.exiger();
        parametrage.unite(id);
        return finapp.patrimoine(id, session);
    }
}
