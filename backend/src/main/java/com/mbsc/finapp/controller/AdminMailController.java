package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.mail.MailEnregistrerRequest;
import com.mbsc.finapp.dto.mail.MailEtatResponse;
import com.mbsc.finapp.dto.mail.MailTestRequest;
import com.mbsc.finapp.dto.mail.MailTestResponse;
import com.mbsc.finapp.service.mail.AdminMailService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Administration › Messagerie (SMTP) : administrateur seul. */
@RestController
@RequestMapping("/admin/mail")
@RequiredArgsConstructor
public class AdminMailController {

    private final AdminMailService service;

    @GetMapping
    public MailEtatResponse etat() {
        return service.etat();
    }

    @PutMapping
    public MailEtatResponse enregistrer(@Valid @RequestBody MailEnregistrerRequest req) {
        return service.enregistrer(req);
    }

    @DeleteMapping("/mot-de-passe")
    public MailEtatResponse retirerMotDePasse() {
        return service.retirerMotDePasse();
    }

    @PostMapping("/tester")
    public MailTestResponse tester(@Valid @RequestBody(required = false) MailTestRequest req) {
        return service.tester(req);
    }
}
