package com.mbsc.hotellerie.message;

import com.mbsc.hotellerie.securite.Roles;
import com.mbsc.hotellerie.securite.SessionCourante;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Modèles des messages automatiques (direction, administrateur) et historique des envois (réception). */
@RestController
@RequiredArgsConstructor
public class MessageController {

    private final MessageService service;

    @GetMapping("/messages/modeles")
    public List<MessageService.Modele> modeles() {
        SessionCourante.exiger(Roles.EXPLOITATION);
        return service.modeles();
    }

    @PutMapping("/messages/modeles/{type}")
    public MessageService.Modele modifier(@PathVariable String type, @Valid @RequestBody MessageService.ModeleRequest r) {
        return service.modifier(type, r, SessionCourante.exiger(Roles.PARAMETRAGE).email());
    }

    @GetMapping("/messages/envois")
    public List<MessageService.Envoi> historique() {
        SessionCourante.exiger(Roles.EXPLOITATION);
        return service.historique();
    }
}
