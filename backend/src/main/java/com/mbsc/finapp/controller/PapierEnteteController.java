package com.mbsc.finapp.controller;

import com.mbsc.finapp.domain.enums.OrientationPapier;
import com.mbsc.finapp.domain.enums.TypePapierEntete;
import com.mbsc.finapp.service.PapierEnteteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Papier à en-tête vierge (sans corps), imprimable par tout utilisateur
 * authentifié — voir PapierEnteteService. Auto-service comme /profil : pas
 * de {id} de chemin, pas de @PreAuthorize (aucun rôle particulier requis).
 */
@RestController
@RequestMapping("/papier-entete")
@RequiredArgsConstructor
public class PapierEnteteController {

    private final PapierEnteteService service;

    @GetMapping("/pdf")
    public ResponseEntity<byte[]> pdf(
        @RequestParam TypePapierEntete type,
        @RequestParam OrientationPapier orientation,
        @RequestParam(defaultValue = "1") int nombrePages
    ) {
        byte[] pdf = service.genererPdf(type, orientation, nombrePages);
        String nomFichier = "papier-entete-" + type.name().toLowerCase(java.util.Locale.FRENCH)
            + "-" + orientation.name().toLowerCase(java.util.Locale.FRENCH) + ".pdf";
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + nomFichier + "\"")
            .body(pdf);
    }
}
