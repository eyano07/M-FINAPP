package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.notifications.NotificationsPageResponse;
import com.mbsc.finapp.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Notifications de l'utilisateur authentifie : toujours implicitement
 * limitees a son propre destinataire (aucun controle de role necessaire,
 * la portee est deja l'utilisateur courant de bout en bout).
 */
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;

    @GetMapping
    public NotificationsPageResponse lister() {
        return service.lister();
    }

    @PostMapping("/{id}/lue")
    public void marquerLue(@PathVariable Long id) {
        service.marquerLue(id);
    }

    @PostMapping("/lues")
    public void marquerToutesLues() {
        service.marquerToutesLues();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return service.creerFlux();
    }
}
