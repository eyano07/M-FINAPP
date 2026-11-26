package com.mbsc.finapp.controller;

import com.mbsc.finapp.service.DoubleAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Double authentification (audit SYSCOHADA du 10/11/2026, C-23) : activation depuis le profil, et réglages de
 * l'administrateur (obligation pour les rôles sensibles, réinitialisation après la perte d'un téléphone).
 */
@RestController
@RequiredArgsConstructor
public class DoubleAuthController {

    private final DoubleAuthService service;

    public record CodeRequest(String code) {
    }

    @GetMapping("/profil/double-auth")
    public DoubleAuthService.Etat etat() {
        return service.etat();
    }

    @PostMapping("/profil/double-auth/demarrer")
    public DoubleAuthService.Enrolement demarrer() {
        return service.demarrer();
    }

    @PostMapping("/profil/double-auth/confirmer")
    public DoubleAuthService.Etat confirmer(@RequestBody CodeRequest req) {
        return service.confirmer(req.code());
    }

    @PostMapping("/profil/double-auth/desactiver")
    public DoubleAuthService.Etat desactiver(@RequestBody CodeRequest req) {
        return service.desactiver(req.code());
    }

    @GetMapping("/admin/securite/double-auth")
    public Map<String, Boolean> obligation() {
        return Map.of("obligatoire", service.obligatoire());
    }

    @PutMapping("/admin/securite/double-auth")
    public Map<String, Boolean> definirObligation(@RequestParam boolean obligatoire) {
        return Map.of("obligatoire", service.definirObligatoire(obligatoire));
    }

    @PostMapping("/admin/utilisateurs/{id}/double-auth/reinitialiser")
    public void reinitialiser(@PathVariable Long id) {
        service.reinitialiser(id);
    }
}
