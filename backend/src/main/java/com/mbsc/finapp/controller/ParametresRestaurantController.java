package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.restaurant.ParametresRestaurantRequest;
import com.mbsc.finapp.dto.restaurant.ParametresRestaurantResponse;
import com.mbsc.finapp.service.ParametresRestaurantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Paramètres du module Restaurant. Le préfixe /restaurant/parametres est
 * couvert par le rattachement de /restaurant à ModuleMetier.RESTAURANT dans
 * ModuleAccessFilter — pas d'entrée dédiée nécessaire.
 */
@RestController
@RequestMapping("/restaurant/parametres")
@RequiredArgsConstructor
public class ParametresRestaurantController {

    private final ParametresRestaurantService service;

    @GetMapping
    public ParametresRestaurantResponse consulter() {
        return service.consulter();
    }

    @PutMapping
    public ParametresRestaurantResponse enregistrer(@Valid @RequestBody ParametresRestaurantRequest req) {
        return service.enregistrer(req);
    }
}
