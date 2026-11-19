package com.mbsc.hotellerie.maintenance;

import com.mbsc.hotellerie.commun.PhotoService;
import com.mbsc.hotellerie.securite.Roles;
import com.mbsc.hotellerie.securite.SessionCourante;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Maintenance : tout le personnel signale ; le technicien et la direction traitent ; photos des pannes et des réparations. */
@RestController
@RequiredArgsConstructor
public class MaintenanceController {

    private final MaintenanceService service;
    private final PhotoService photos;

    @GetMapping("/maintenance/tickets")
    public List<MaintenanceService.Ticket> lister(@RequestParam(defaultValue = "true") boolean ouverts,
                                                  @RequestParam(required = false) Long etablissementId,
                                                  @RequestParam(required = false) Long uniteId,
                                                  @RequestParam(defaultValue = "false") boolean moi) {
        return service.lister(ouverts, etablissementId, uniteId, moi, SessionCourante.exiger());
    }

    @GetMapping("/maintenance/tickets/{id}")
    public MaintenanceService.Detail detail(@PathVariable Long id) {
        return service.detail(id, SessionCourante.exiger());
    }

    @PostMapping("/maintenance/tickets")
    public MaintenanceService.Ticket signaler(@Valid @RequestBody MaintenanceService.TicketRequest r) {
        return service.signaler(r, SessionCourante.exiger(Roles.SIGNALEMENT));
    }

    @PutMapping("/maintenance/tickets/{id}")
    public MaintenanceService.Ticket modifier(@PathVariable Long id, @Valid @RequestBody MaintenanceService.TicketRequest r) {
        return service.modifier(id, r, SessionCourante.exiger(Roles.MAINTENANCE));
    }

    @PutMapping("/maintenance/tickets/{id}/affectation")
    public MaintenanceService.Ticket affecter(@PathVariable Long id, @Valid @RequestBody MaintenanceService.AffectationRequest a) {
        return service.affecter(id, a, SessionCourante.exiger(Roles.MAINTENANCE));
    }

    @PostMapping("/maintenance/tickets/{id}/statut")
    public MaintenanceService.Ticket statut(@PathVariable Long id, @Valid @RequestBody MaintenanceService.StatutRequest s) {
        // Annuler : aussi l'auteur du signalement (contrôlé dans le service) ; le reste : technicien et direction.
        return service.changerStatut(id, s, "ANNULE".equals(s.statut()) ? SessionCourante.exiger(Roles.SIGNALEMENT)
            : SessionCourante.exiger(Roles.MAINTENANCE));
    }

    @PostMapping("/maintenance/tickets/{id}/achats")
    public MaintenanceService.Detail acheter(@PathVariable Long id, @Valid @RequestBody MaintenanceService.AchatRequest a) {
        return service.acheter(id, a, SessionCourante.exiger(Roles.ACHATS));
    }

    @PostMapping(value = "/maintenance/tickets/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PhotoService.Photo photo(@PathVariable Long id, @RequestPart("fichier") MultipartFile fichier,
                                    @RequestParam(required = false) String legende) {
        service.ticket(id);
        return photos.ajouter("TICKET", id, fichier, legende, SessionCourante.exiger(Roles.SIGNALEMENT));
    }

    /** Photos (tickets, états des lieux, relevés) : à tout le personnel connecté. */
    @GetMapping("/photos/{id}")
    public ResponseEntity<FileSystemResource> lirePhoto(@PathVariable Long id) {
        SessionCourante.exiger();
        PhotoService.Fichier f = photos.fichier(id);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"photo-" + id + "\"")
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
            .contentType(MediaType.parseMediaType(f.type()))
            .body(new FileSystemResource(f.chemin()));
    }

    @DeleteMapping("/photos/{id}")
    public void supprimerPhoto(@PathVariable Long id) {
        photos.supprimer(id, SessionCourante.exiger(Roles.RECEPTION, Roles.TECHNICIEN, Roles.DIRECTION, Roles.ADMIN));
    }
}
