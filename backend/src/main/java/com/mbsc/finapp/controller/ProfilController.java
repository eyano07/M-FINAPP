package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.profil.ChangerMotDePasseRequest;
import com.mbsc.finapp.dto.profil.ProfilResponse;
import com.mbsc.finapp.dto.profil.ProfilUpdateRequest;
import com.mbsc.finapp.service.ProfilService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Profil de l'utilisateur courant (auto-service). Toujours scope a
 * l'appelant authentifie - voir ProfilService/CurrentUserProvider - jamais
 * un {id} de chemin comme dans AdminController.
 */
@RestController
@RequestMapping("/profil")
@RequiredArgsConstructor
public class ProfilController {

    private final ProfilService service;

    @GetMapping
    public ProfilResponse obtenir() {
        return service.obtenir();
    }

    @PutMapping
    public ProfilResponse mettreAJour(@Valid @RequestBody ProfilUpdateRequest req) {
        return service.mettreAJour(req);
    }

    @PutMapping("/mot-de-passe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changerMotDePasse(@Valid @RequestBody ChangerMotDePasseRequest req) {
        service.changerMotDePasse(req);
    }

    @PostMapping(value = "/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProfilResponse remplacerPhoto(@RequestParam("fichier") MultipartFile fichier) {
        return service.mettreAJourPhoto(fichier);
    }

    /** Sert la photo de l'utilisateur courant. Authentifie (pas de PUBLIC_ENDPOINTS ici). */
    @GetMapping("/photo")
    public ResponseEntity<Resource> photo() {
        var telechargement = service.telechargerPhoto();
        MediaType type = telechargement.typeMime() != null
            ? MediaType.parseMediaType(telechargement.typeMime())
            : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
            .contentType(type)
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
            .body(telechargement.ressource());
    }
}
