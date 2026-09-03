package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.admin.CompteDetailResponse;
import com.mbsc.finapp.dto.admin.CompteRequest;
import com.mbsc.finapp.dto.admin.CompteResponse;
import com.mbsc.finapp.dto.admin.CompteUpdateRequest;
import com.mbsc.finapp.dto.admin.ImportJournalResponse;
import com.mbsc.finapp.dto.admin.TauxChangeRequest;
import com.mbsc.finapp.dto.admin.TauxChangeResponse;
import com.mbsc.finapp.dto.admin.TauxTvaRequest;
import com.mbsc.finapp.dto.admin.TauxTvaResponse;
import com.mbsc.finapp.dto.admin.UserCreateRequest;
import com.mbsc.finapp.dto.admin.UserResponse;
import com.mbsc.finapp.dto.admin.UserUpdateRequest;
import com.mbsc.finapp.dto.parametrage.ModuleActifRequest;
import com.mbsc.finapp.dto.parametrage.ModuleConfigResponse;
import com.mbsc.finapp.dto.parametrage.NiveauPermissionRequest;
import com.mbsc.finapp.dto.parametrage.ParametresEntrepriseRequest;
import com.mbsc.finapp.dto.parametrage.ParametresEntrepriseResponse;
import com.mbsc.finapp.dto.parametrage.RolePermissionResponse;
import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.service.AdminService;
import com.mbsc.finapp.service.ModuleConfigService;
import com.mbsc.finapp.service.ParametresEntrepriseService;
import com.mbsc.finapp.service.ImportJournalService;
import com.mbsc.finapp.service.PermissionService;
import com.mbsc.finapp.service.TauxTvaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Endpoints de lecture pour l'administration et les referentiels partages.
 * Le controle d'acces fin est porte par {@link AdminService} (et les
 * services de parametrage pour les nouveaux endpoints ci-dessous).
 */
@RestController
@RequiredArgsConstructor
public class AdminController {

    private final AdminService service;
    private final TauxTvaService tauxTvaService;
    private final ParametresEntrepriseService parametresService;
    private final ModuleConfigService moduleConfigService;
    private final PermissionService permissionService;
    private final ImportJournalService importJournalService;

    /** Plan comptable OHADA (referentiel partage). */
    @GetMapping("/comptes")
    public List<CompteResponse> comptes() {
        return service.listerComptes();
    }

    /** Detail d'un compte, rubriques du referentiel SYSCOHADA commente incluses. */
    @GetMapping("/comptes/{id}")
    public CompteDetailResponse compte(@PathVariable Long id) {
        return service.consulterCompte(id);
    }

    /** Ajoute un compte manuellement (ADMIN). */
    @PostMapping("/comptes")
    @ResponseStatus(HttpStatus.CREATED)
    public CompteResponse ajouterCompte(@Valid @RequestBody CompteRequest req) {
        return service.ajouterCompte(req);
    }

    /** Renomme un compte manuel (ADMIN, DFIN). */
    @PutMapping("/comptes/{id}")
    public CompteResponse modifierCompte(@PathVariable Long id, @Valid @RequestBody CompteUpdateRequest req) {
        return service.modifierCompte(id, req);
    }

    /** Supprime un compte manuel (ADMIN). */
    @DeleteMapping("/comptes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimerCompte(@PathVariable Long id) {
        service.supprimerCompte(id);
    }

    /** Active/désactive un compte (ADMIN) : alternative à la suppression. */
    @PatchMapping("/comptes/{id}/toggle-actif")
    public CompteResponse toggleCompteActif(@PathVariable Long id) {
        return service.toggleCompteActif(id);
    }

    /** Annuaire des utilisateurs (ADMIN). */
    @GetMapping("/admin/users")
    public List<UserResponse> utilisateurs() {
        return service.listerUtilisateurs();
    }

    /** Crée un nouvel utilisateur (ADMIN). */
    @PostMapping("/admin/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse creerUtilisateur(@Valid @RequestBody UserCreateRequest req) {
        return service.creerUtilisateur(req);
    }

    /** Modifie un utilisateur (ADMIN). */
    @PutMapping("/admin/users/{id}")
    public UserResponse modifierUtilisateur(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest req) {
        return service.modifierUtilisateur(id, req);
    }

    /** Active ou désactive un utilisateur (ADMIN). */
    @PatchMapping("/admin/users/{id}/actif")
    public UserResponse toggleActif(@PathVariable Long id) {
        return service.toggleActif(id);
    }

    /** Taux de change actuel + historique (tout utilisateur authentifie). */
    @GetMapping("/admin/taux-change")
    public TauxChangeResponse getTauxChange() {
        return service.getTauxChange();
    }

    /** Enregistre un nouveau taux de change (ADMIN). */
    @PostMapping("/admin/taux-change")
    @ResponseStatus(HttpStatus.CREATED)
    public TauxChangeResponse.HistoriqueEntry enregistrerTaux(@Valid @RequestBody TauxChangeRequest req) {
        return service.enregistrerTaux(req);
    }

    /** Taux de TVA en vigueur + historique (tout utilisateur authentifie). */
    @GetMapping("/admin/taux-tva")
    public TauxTvaResponse getTauxTva() {
        return tauxTvaService.consulter();
    }

    /** Enregistre un nouveau taux de TVA (ADMIN). */
    @PostMapping("/admin/taux-tva")
    @ResponseStatus(HttpStatus.CREATED)
    public TauxTvaResponse.HistoriqueEntry enregistrerTauxTva(@Valid @RequestBody TauxTvaRequest req) {
        return tauxTvaService.enregistrer(req);
    }

    // ---------------------------------------------------------------------
    // Identite de l'entreprise (nom, slogan, logo)
    // ---------------------------------------------------------------------

    /** Parametres d'identite (tout utilisateur authentifie : affiches dans l'entete/le menu). */
    @GetMapping("/parametres")
    public ParametresEntrepriseResponse parametres() {
        return parametresService.obtenir();
    }

    /** Modifie nom / nom complet / slogan (ADMIN). */
    @PutMapping("/parametres")
    public ParametresEntrepriseResponse modifierParametres(@Valid @RequestBody ParametresEntrepriseRequest req) {
        return parametresService.mettreAJour(req);
    }

    /** Remplace le logo (ADMIN). */
    @PostMapping(value = "/parametres/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ParametresEntrepriseResponse remplacerLogo(@RequestParam("fichier") MultipartFile fichier) {
        return parametresService.mettreAJourLogo(fichier);
    }

    /**
     * Sert le logo actuel. Volontairement PUBLIC (voir {@code SecurityConfig})
     * pour rester visible sur la page de connexion, avant authentification.
     */
    @GetMapping("/parametres/logo")
    public ResponseEntity<Resource> logo() {
        var telechargement = parametresService.telechargerLogo();
        MediaType type = telechargement.typeMime() != null
            ? MediaType.parseMediaType(telechargement.typeMime())
            : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
            .contentType(type)
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
            .body(telechargement.ressource());
    }

    // ---------------------------------------------------------------------
    // Modules metier activables/desactivables
    // ---------------------------------------------------------------------

    /** Etat actif/inactif de chaque module (tout utilisateur authentifie : necessaire a la navigation). */
    @GetMapping("/admin/modules")
    public List<ModuleConfigResponse> modules() {
        return moduleConfigService.lister();
    }

    /** Active ou desactive un module (ADMIN). */
    @PutMapping("/admin/modules/{module}")
    public ModuleConfigResponse definirModule(@PathVariable ModuleMetier module, @Valid @RequestBody ModuleActifRequest req) {
        return moduleConfigService.definir(module, req.actif());
    }

    // ---------------------------------------------------------------------
    // Grille de permissions role x module (ADMIN)
    // ---------------------------------------------------------------------

    /** Grille complete role x module (ADMIN uniquement : c'est la configuration de securite elle-meme). */
    @GetMapping("/admin/permissions")
    public List<RolePermissionResponse> permissions() {
        return permissionService.listerGrille();
    }

    /** Definit le niveau d'un role sur un module (ADMIN). */
    @PutMapping("/admin/permissions/{role}/{module}")
    public RolePermissionResponse definirPermission(
            @PathVariable RoleType role, @PathVariable ModuleMetier module,
            @Valid @RequestBody NiveauPermissionRequest req) {
        return permissionService.definir(role, module, req.niveau());
    }

    /** Permissions effectives de l'utilisateur courant, par module (navigation, garde de page cote frontend). */
    @GetMapping("/mes-permissions")
    public java.util.Map<ModuleMetier, com.mbsc.finapp.domain.enums.NiveauPermission> mesPermissions() {
        return permissionService.mesPermissions();
    }

    // ── Import de journal ────────────────────────────────────────────────

    /**
     * Importe un journal comptable (.xlsx ou .csv) pour peupler l'application.
     *
     * <p>Le bilan, la balance et le compte de resultat etant tous recalcules a
     * partir du grand livre, importer le journal alimente l'ensemble des etats
     * d'un seul geste.</p>
     *
     * @param simulation true (defaut) pour analyser le fichier sans rien ecrire
     */
    @PostMapping(value = "/admin/import/journal", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportJournalResponse importerJournal(
        @RequestParam("fichier") MultipartFile fichier,
        @RequestParam(name = "simulation", defaultValue = "true") boolean simulation,
        @RequestParam(name = "substitutions", required = false) String substitutions,
        @RequestParam(name = "regroupement", defaultValue = "REFERENCE")
            ImportJournalService.ModeRegroupement regroupement,
        @RequestParam(name = "devise", defaultValue = "CDF")
            com.mbsc.finapp.domain.enums.Devise devise,
        @RequestParam(name = "modeImport", defaultValue = "REMPLACER")
            ImportJournalService.ModeImport modeImport
    ) {
        return importJournalService.importer(fichier, simulation,
            parserSubstitutions(substitutions), regroupement, devise, modeImport);
    }

    /**
     * Substitutions de comptes validees par l'administrateur, transmises en
     * "ancien:nouveau,ancien:nouveau". Format volontairement simple : la
     * requete etant multipart, y glisser du JSON imposerait une partie
     * supplementaire sans rien apporter.
     */
    private java.util.Map<String, String> parserSubstitutions(String brut) {
        java.util.Map<String, String> resultat = new java.util.LinkedHashMap<>();
        if (brut == null || brut.isBlank()) {
            return resultat;
        }
        for (String paire : brut.split(",")) {
            String[] p = paire.split(":", 2);
            if (p.length == 2 && !p[0].isBlank() && !p[1].isBlank()) {
                resultat.put(p[0].trim(), p[1].trim());
            }
        }
        return resultat;
    }

    /**
     * Prepare le plan comptable pour ce fichier : cree les comptes absents et
     * retient les sous-comptes de saisie. Renvoie les substitutions a appliquer.
     */
    @PostMapping(value = "/admin/import/journal/correction-auto", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> corrigerAutomatiquement(
        @RequestParam("fichier") MultipartFile fichier,
        @RequestParam(name = "regroupement", defaultValue = "REFERENCE")
            ImportJournalService.ModeRegroupement regroupement
    ) {
        return importJournalService.corrigerAutomatiquement(fichier, regroupement);
    }

    /**
     * Renvoie le fichier journal corrige des substitutions retenues, pret a
     * etre reimporte et a archiver.
     */
    @PostMapping(value = "/admin/import/journal/correction", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> fichierJournalCorrige(
        @RequestParam("fichier") MultipartFile fichier,
        @RequestParam(name = "substitutions", required = false) String substitutions
    ) {
        byte[] corrige = importJournalService.genererFichierCorrige(fichier, parserSubstitutions(substitutions));
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"Journal_corrige.xlsx\"")
            .body(corrige);
    }

    /** Modele de fichier d'import, aux colonnes attendues. */
    @GetMapping("/admin/import/journal/modele")
    public ResponseEntity<byte[]> modeleImportJournal() {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"Modele_import_journal.xlsx\"")
            .body(importJournalService.modele());
    }
}
