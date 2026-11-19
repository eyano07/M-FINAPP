package com.mbsc.hotellerie.commun;

import com.mbsc.hotellerie.finapp.FinappException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/** Toutes les erreurs ressortent en {@code {message}} lisible, avec le bon statut. */
@RestControllerAdvice
public class GestionErreurs {

    private static final Logger log = LoggerFactory.getLogger(GestionErreurs.class);

    @ExceptionHandler(Refus.class)
    ResponseEntity<Map<String, String>> refus(Refus e) {
        return ResponseEntity.status(e.statut()).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(FinappException.class)
    ResponseEntity<Map<String, String>> finapp(FinappException e) {
        int statut = e.statut() == 0 ? 503 : (e.statut() >= 500 ? 502 : e.statut());
        return ResponseEntity.status(statut).body(e.code() == null ? Map.of("message", e.getMessage())
            : Map.of("message", e.getMessage(), "code", e.code()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalide(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + " : " + f.getDefaultMessage()).collect(Collectors.joining(" · "));
        return ResponseEntity.badRequest().body(Map.of("message", detail.isEmpty() ? "Saisie invalide." : detail));
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> illisible(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("message", "Saisie illisible : vérifiez les champs (dates, nombres)."));
    }

    /** Erreur imprévue : journalisée avec sa référence, jamais montrée telle quelle (pas de SQL à l'écran). */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> imprevue(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse er) {
            return ResponseEntity.status(er.getStatusCode()).body(Map.of("message", "Requête invalide."));
        }
        String reference = java.util.UUID.randomUUID().toString().substring(0, 8);
        log.error("Erreur imprévue [{}]", reference, e);
        return ResponseEntity.status(500).body(Map.of("message", "Une erreur interne est survenue (référence " + reference + ")."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> integrite(DataIntegrityViolationException e) {
        String m = String.valueOf(e.getMostSpecificCause().getMessage());
        if (m.contains("reservation_sans_chevauchement")) {
            return ResponseEntity.status(409).body(Map.of("message",
                "Cette unité est déjà réservée sur une partie de ces dates : choisissez d'autres dates ou une autre unité."));
        }
        if (m.contains("duplicate key") || m.contains("unique")) {
            return ResponseEntity.status(409).body(Map.of("message", "Cette valeur existe déjà (nom ou numéro en double)."));
        }
        log.warn("Contrainte refusée : {}", m);
        return ResponseEntity.status(409).body(Map.of("message", "Enregistrement refusé par une règle de cohérence."));
    }
}
