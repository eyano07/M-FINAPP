package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.widget.WidgetResume;
import com.mbsc.finapp.service.widget.WidgetService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Widgets de l'écran d'accueil Android (18/11/2026).
 * <ul>
 *   <li>{@code POST /appareils/widget} : jeton du téléphone pour le compte connecté (l'application le demande à la
 *       connexion) ; {@code POST /appareils/widget/retrait} : retrait à la déconnexion ;</li>
 *   <li>{@code GET /widget/resume} : résumé lu par les widgets (jeton en en-tête X-Jeton-Widget). Le widget
 *       Trésorerie s'active par compte : préférence {@code widget.tresorerie} (PUT /profil/preferences).</li>
 * </ul>
 */
@RestController
public class WidgetController {

    private final WidgetService service;

    public WidgetController(WidgetService service) {
        this.service = service;
    }

    public record DemandeJeton(String ancien) {}

    public record RetraitJeton(String jeton) {}

    @PostMapping("/appareils/widget")
    public Map<String, String> emettre(@RequestBody(required = false) DemandeJeton demande) {
        return Map.of("jeton", service.emettre(demande == null ? null : demande.ancien()));
    }

    @PostMapping("/appareils/widget/retrait")
    public void retirer(@RequestBody RetraitJeton retrait) {
        service.retirer(retrait.jeton());
    }

    @GetMapping("/widget/resume")
    public WidgetResume resume() {
        return service.resume();
    }
}
