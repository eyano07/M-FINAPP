package com.mbsc.finapp.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gestion centralisee des exceptions.
 * Principe de securite : on ne renvoie jamais de stack trace ni de detail
 * technique interne au client. Les erreurs inattendues sont journalisees avec
 * un identifiant de correlation que l'on retourne pour faciliter le diagnostic.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({BadCredentialsException.class, DisabledException.class, AuthenticationException.class})
    public ResponseEntity<Map<String, Object>> handleAuth(AuthenticationException ex) {
        log.warn("Authentification refusee : {}", ex.getMessage());
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Acces refuse : {}", ex.getMessage());
        return build(HttpStatus.FORBIDDEN, "Acces refuse : droits insuffisants");
    }

    @ExceptionHandler(RessourceIntrouvableException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(RessourceIntrouvableException ex) {
        log.info("Ressource introuvable : {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(TransitionInvalideException.class)
    public ResponseEntity<Map<String, Object>> handleTransition(TransitionInvalideException ex) {
        log.warn("Transition de workflow invalide : {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(ReglePrioriteException.class)
    public ResponseEntity<Map<String, Object>> handleReglePriorite(ReglePrioriteException ex) {
        log.warn("Regle de priorite non respectee : {}", ex.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> body = baseBody(HttpStatus.BAD_REQUEST, "Validation echouee");
        Map<String, String> fields = new HashMap<>();
        ex.getBindingResult().getFieldErrors()
            .forEach(fe -> fields.put(fe.getField(), fe.getDefaultMessage()));
        body.put("fieldErrors", fields);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Exceptions portant deja leur propre statut HTTP.
     *
     * <p>Sans ce handler, elles etaient captees par le filet {@code
     * Exception.class} et transformees en 500 « Une erreur interne est
     * survenue » : le statut et le message metier voulus par l'appelant
     * etaient perdus. Concretement, « Email deja utilise » (409), « Compte
     * parent introuvable » (404) ou « Numero de compte deja existant » (409)
     * arrivaient tous au client comme une erreur serveur opaque, impossible
     * a corriger par l'utilisateur.</p>
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String message = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        if (status.is5xxServerError()) {
            log.error("Erreur serveur explicite [{}] : {}", status.value(), message, ex);
        } else {
            log.info("Requete refusee [{}] : {}", status.value(), message);
        }
        return build(status, message);
    }

    /**
     * Route ou chemin inexistant (URL mal formee, typo cote client).
     *
     * <p>Sans ce handler, elle etait elle aussi captee par le filet {@code
     * Exception.class} : un simple 404 (« cette route n'existe pas »)
     * arrivait comme un 500 « erreur interne », polluant les journaux
     * d'erreurs serveur avec de simples fautes de frappe d'URL.</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException ex) {
        log.info("Route introuvable : {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, "Ressource introuvable : " + ex.getResourcePath());
    }

    /** Route existante mais methode HTTP non supportee (ex. GET sur une route qui n'accepte que PUT). */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.info("Methode non supportee : {}", ex.getMessage());
        return build(HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** Conflit de verrou optimiste (deux modifications simultanées du même stock). */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        log.warn("Conflit de concurrence detecte : {}", ex.getMessage());
        return build(HttpStatus.CONFLICT,
            "Cette donnée vient d'être modifiée par un autre utilisateur. Rechargez la page et réessayez.");
    }

    /** Filet de securite : toute exception non geree explicitement. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        String traceId = UUID.randomUUID().toString();
        log.error("Erreur inattendue [traceId={}]", traceId, ex);
        Map<String, Object> body = baseBody(HttpStatus.INTERNAL_SERVER_ERROR,
            "Une erreur interne est survenue. Reference: " + traceId);
        body.put("traceId", traceId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(baseBody(status, message));
    }

    private Map<String, Object> baseBody(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return body;
    }
}
