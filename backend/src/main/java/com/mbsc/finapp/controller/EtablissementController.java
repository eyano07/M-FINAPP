package com.mbsc.finapp.controller;

import com.mbsc.finapp.domain.enums.TypeEtablissement;
import com.mbsc.finapp.dto.etablissement.EtablissementRequest;
import com.mbsc.finapp.dto.etablissement.EtablissementResponse;
import com.mbsc.finapp.service.EtablissementTresorerieService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API REST des etablissements de tresorerie (banques et operateurs
 * mobile money). La consultation sert aux listes deroulantes des modules
 * Banque / Mobile Money ; la creation et le retrait sont reserves a
 * l'administrateur (controles portes par le service).
 */
@RestController
@RequestMapping("/etablissements")
@RequiredArgsConstructor
public class EtablissementController {

    private final EtablissementTresorerieService service;

    @GetMapping
    public List<EtablissementResponse> lister(@RequestParam(required = false) TypeEtablissement type) {
        return service.lister(type);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EtablissementResponse creer(@Valid @RequestBody EtablissementRequest req) {
        return service.creer(req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimer(@PathVariable Long id) {
        service.supprimer(id);
    }
}
