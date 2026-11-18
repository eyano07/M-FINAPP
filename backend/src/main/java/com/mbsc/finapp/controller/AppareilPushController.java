package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.push.AppareilPushRequest;
import com.mbsc.finapp.service.push.PushService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Inscription des téléphones aux notifications push (application Android). Tout compte connecté peut s'inscrire. */
@RestController
@RequestMapping("/appareils")
@RequiredArgsConstructor
public class AppareilPushController {

    private final PushService push;

    @PostMapping
    public ResponseEntity<Void> inscrire(@Valid @RequestBody AppareilPushRequest req) {
        push.inscrire(req.jeton());
        return ResponseEntity.noContent().build();
    }

    /** Déconnexion : le téléphone ne reçoit plus les notifications de ce compte. */
    @PostMapping("/retrait")
    public ResponseEntity<Void> desinscrire(@Valid @RequestBody AppareilPushRequest req) {
        push.desinscrire(req.jeton());
        return ResponseEntity.noContent().build();
    }
}
