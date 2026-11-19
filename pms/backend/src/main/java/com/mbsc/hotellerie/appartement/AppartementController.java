package com.mbsc.hotellerie.appartement;

import com.mbsc.hotellerie.commun.PhotoService;
import com.mbsc.hotellerie.securite.Roles;
import com.mbsc.hotellerie.securite.SessionCourante;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
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

/** Appartements : baux et échéances, compteurs, inventaire type et états des lieux. */
@RestController
@RequiredArgsConstructor
public class AppartementController {

    private final BailService baux;
    private final EtatDesLieuxService etats;
    private final PhotoService photos;

    // ── Baux ────────────────────────────────────────────────────────────────

    @GetMapping("/baux")
    public List<BailService.Bail> lister(@RequestParam(required = false) Long etablissementId, @RequestParam(required = false) String statut) {
        SessionCourante.exiger(Roles.BAUX);
        return baux.lister(etablissementId, statut);
    }

    @GetMapping("/baux/{id}")
    public BailService.Detail detail(@PathVariable Long id) {
        SessionCourante.exiger(Roles.BAUX);
        return baux.detail(id);
    }

    @PostMapping("/baux")
    public BailService.Detail creer(@Valid @RequestBody BailService.BailRequest r) {
        return baux.creer(r, SessionCourante.exiger(Roles.BAUX));
    }

    public record MotifRequest(String motif) { }

    @PostMapping("/baux/{id}/resiliation")
    public BailService.Bail resilier(@PathVariable Long id, @RequestBody MotifRequest r) {
        SessionCourante.exiger(Roles.DIRECTION, Roles.ADMIN);
        return baux.resilier(id, r.motif());
    }

    /** Facturation à crédit : la réception et la direction (l'administrateur ne vend pas). */
    @PostMapping("/baux/{id}/echeances/{echeanceId}/facture")
    public BailService.Echeance facturer(@PathVariable Long id, @PathVariable Long echeanceId) {
        return baux.facturer(id, echeanceId, SessionCourante.exiger(Roles.ENCAISSEMENT));
    }

    @PostMapping("/baux/{id}/echeances/{echeanceId}/reglement")
    public BailService.Echeance regler(@PathVariable Long id, @PathVariable Long echeanceId, @Valid @RequestBody BailService.ReglementRequest r) {
        return baux.regler(id, echeanceId, r, SessionCourante.exiger(Roles.ENCAISSEMENT));
    }

    // ── Compteurs ───────────────────────────────────────────────────────────

    @GetMapping("/compteurs/tarifs")
    public List<BailService.TarifCompteur> tarifs(@RequestParam(required = false) Long etablissementId) {
        SessionCourante.exiger();
        return baux.tarifsCompteurs(etablissementId);
    }

    @PutMapping("/compteurs/tarifs")
    public BailService.TarifCompteur tarif(@Valid @RequestBody BailService.TarifCompteurRequest r) {
        SessionCourante.exiger(Roles.PARAMETRAGE);
        return baux.enregistrerTarifCompteur(r);
    }

    @GetMapping("/unites/{id}/releves")
    public List<BailService.Releve> releves(@PathVariable Long id) {
        SessionCourante.exiger();
        return baux.releves(id);
    }

    @PostMapping("/unites/{id}/releves")
    public BailService.Releve releve(@PathVariable Long id, @Valid @RequestBody BailService.ReleveRequest r) {
        return baux.releve(id, r, SessionCourante.exiger(Roles.RECEPTION, Roles.TECHNICIEN, Roles.DIRECTION, Roles.ADMIN));
    }

    @DeleteMapping("/releves/{id}")
    public void supprimerReleve(@PathVariable Long id) {
        SessionCourante.exiger(Roles.RECEPTION, Roles.TECHNICIEN, Roles.DIRECTION, Roles.ADMIN);
        baux.supprimerReleve(id);
    }

    // ── Inventaire type et états des lieux ─────────────────────────────────

    @GetMapping("/unites/{id}/inventaire")
    public List<EtatDesLieuxService.ElementModele> modele(@PathVariable Long id) {
        SessionCourante.exiger();
        return etats.modele(id);
    }

    @PutMapping("/unites/{id}/inventaire")
    public List<EtatDesLieuxService.ElementModele> enregistrerModele(@PathVariable Long id,
                                                                     @RequestBody List<EtatDesLieuxService.ElementModele> elements) {
        SessionCourante.exiger(Roles.BAUX);
        return etats.enregistrerModele(id, elements);
    }

    @GetMapping("/reservations/{id}/etats-des-lieux")
    public List<EtatDesLieuxService.Etat> etats(@PathVariable Long id) {
        SessionCourante.exiger();
        return etats.etats(id);
    }

    public record OuvertureRequest(String type) { }

    @PostMapping("/reservations/{id}/etats-des-lieux")
    public EtatDesLieuxService.Etat ouvrir(@PathVariable Long id, @RequestBody OuvertureRequest r) {
        return etats.ouvrir(id, r.type(), SessionCourante.exiger(Roles.BAUX));
    }

    @PutMapping("/etats-des-lieux/{id}")
    public EtatDesLieuxService.Etat enregistrer(@PathVariable Long id, @Valid @RequestBody EtatDesLieuxService.EtatRequest r) {
        SessionCourante.exiger(Roles.BAUX);
        return etats.enregistrer(id, r);
    }

    @PostMapping("/etats-des-lieux/{id}/validation")
    public EtatDesLieuxService.Etat valider(@PathVariable Long id) {
        return etats.valider(id, SessionCourante.exiger(Roles.BAUX));
    }

    @PostMapping(value = "/etats-des-lieux/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PhotoService.Photo photo(@PathVariable Long id, @RequestPart("fichier") MultipartFile fichier,
                                    @RequestParam(required = false) String legende) {
        etats.etat(id);
        return photos.ajouter("ETAT_LIEUX", id, fichier, legende, SessionCourante.exiger(Roles.BAUX));
    }
}
