package com.mbsc.hotellerie.menage;

import com.mbsc.hotellerie.securite.Roles;
import com.mbsc.hotellerie.securite.SessionCourante;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Ménage : la gouvernante, la réception et la direction. */
@RestController
@RequiredArgsConstructor
public class MenageController {

    private final MenageService service;

    @GetMapping("/menage/unites")
    public List<MenageService.EtatUnite> etats(@RequestParam(required = false) Long etablissementId) {
        SessionCourante.exiger();
        return service.etats(etablissementId);
    }

    @GetMapping("/menage/taches")
    public List<MenageService.Tache> taches(@RequestParam(defaultValue = "false") boolean toutes,
                                            @RequestParam(required = false) Long etablissementId) {
        SessionCourante.exiger();
        return service.taches(toutes, etablissementId);
    }

    @PostMapping("/menage/taches")
    public MenageService.Tache creer(@RequestBody MenageService.TacheRequest r) {
        SessionCourante.exiger(Roles.MENAGE_ECRITURE);
        return service.creer(r);
    }

    public record AffectationRequest(String email, String nom) { }

    @PutMapping("/menage/taches/{id}/affectation")
    public MenageService.Tache affecter(@PathVariable Long id, @RequestBody AffectationRequest r) {
        SessionCourante.exiger(Roles.MENAGE_ECRITURE);
        return service.affecter(id, r.email(), r.nom());
    }

    @PostMapping("/menage/taches/{id}/commencer")
    public MenageService.Tache commencer(@PathVariable Long id) {
        return service.commencer(id, SessionCourante.exiger(Roles.MENAGE_ECRITURE));
    }

    @PostMapping("/menage/taches/{id}/terminer")
    public MenageService.Tache terminer(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean aInspecter) {
        return service.terminer(id, SessionCourante.exiger(Roles.MENAGE_ECRITURE), aInspecter);
    }

    public record EtatRequest(String etat) { }

    @PutMapping("/menage/unites/{id}/etat")
    public MenageService.EtatUnite changerEtat(@PathVariable Long id, @RequestBody EtatRequest r) {
        SessionCourante.exiger(Roles.MENAGE_ECRITURE);
        return service.changerEtat(id, r.etat());
    }
}
