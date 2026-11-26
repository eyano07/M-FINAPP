package com.mbsc.hotellerie.publique;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Moteur de réservation direct : routes publiques (sans connexion). */
@RestController
@RequestMapping("/public")
@RequiredArgsConstructor
public class PubliqueController {

    private final PubliqueService service;
    private final com.mbsc.hotellerie.commun.PhotoService photos;

    /** Photo d'une unité (chambre, appartement) montrée sur la réservation en ligne ; aucune autre photo n'est publique. */
    @GetMapping("/photos/{id}")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.FileSystemResource> photo(@org.springframework.web.bind.annotation.PathVariable Long id) {
        var f = photos.fichierPublic(id);
        return org.springframework.http.ResponseEntity.ok()
            .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
            .contentType(org.springframework.http.MediaType.parseMediaType(f.type()))
            .body(new org.springframework.core.io.FileSystemResource(f.chemin()));
    }

    @GetMapping("/etablissements")
    public List<PubliqueService.Etablissement> etablissements() {
        return service.etablissements();
    }

    @GetMapping("/disponibilites")
    public List<PubliqueService.Offre> disponibilites(@RequestParam Long etablissementId, @RequestParam LocalDate arrivee,
                                                      @RequestParam LocalDate depart, @RequestParam(defaultValue = "1") int personnes) {
        return service.disponibilites(etablissementId, arrivee, depart, Math.max(1, personnes));
    }

    @PostMapping("/demandes")
    public PubliqueService.Accuse demander(@Valid @RequestBody PubliqueService.Demande d, HttpServletRequest requete) {
        String adresse = requete.getHeader("X-Real-IP") != null ? requete.getHeader("X-Real-IP") : requete.getRemoteAddr();
        return service.demander(d, adresse);
    }
}
