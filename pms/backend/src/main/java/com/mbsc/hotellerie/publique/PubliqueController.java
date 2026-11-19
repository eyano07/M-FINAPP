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
