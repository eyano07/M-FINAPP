package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.vente.ClientRequest;
import com.mbsc.finapp.dto.vente.ClientResponse;
import com.mbsc.finapp.service.ClientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Repertoire clients. Le controle d'acces est porte par {@link ClientService}.
 */
@RestController
@RequestMapping("/clients")
@RequiredArgsConstructor
public class ClientController {

    private final ClientService service;

    @GetMapping
    public List<ClientResponse> lister() {
        return service.lister();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClientResponse creer(@Valid @RequestBody ClientRequest req) {
        return service.creer(req);
    }

    @PutMapping("/{id}")
    public ClientResponse modifier(@PathVariable Long id, @Valid @RequestBody ClientRequest req) {
        return service.modifier(id, req);
    }
}
