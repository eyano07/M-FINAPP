package com.mbsc.finapp.controller;

import com.mbsc.finapp.service.exercice.ExerciceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Liste des exercices comptables pour le sélecteur de la barre du haut (tout utilisateur connecté). */
@RestController
@RequestMapping("/exercices")
@RequiredArgsConstructor
public class ExerciceController {

    private final ExerciceService exercices;

    @GetMapping
    public List<ExerciceService.Exercice> lister() {
        return exercices.lister();
    }
}
