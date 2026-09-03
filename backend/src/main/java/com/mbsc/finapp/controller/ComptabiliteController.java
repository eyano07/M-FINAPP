package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.comptabilite.*;
import com.mbsc.finapp.service.AnalyseFinanciereIaService;
import com.mbsc.finapp.service.ClotureAnnuelleService;
import com.mbsc.finapp.service.ComptabiliteService;
import com.mbsc.finapp.service.EtatsFinanciersExcelService;
import com.mbsc.finapp.service.BalanceAgeeService;
import com.mbsc.finapp.service.PeriodeComptableService;
import com.mbsc.finapp.service.SoldesIntermediairesService;
import com.mbsc.finapp.service.TvaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/comptabilite")
@RequiredArgsConstructor
public class ComptabiliteController {

    private final ComptabiliteService service;
    private final PeriodeComptableService periodeService;
    private final EtatsFinanciersExcelService excelService;
    private final AnalyseFinanciereIaService analyseIaService;
    private final ClotureAnnuelleService clotureAnnuelleService;
    private final TvaService tvaService;
    private final SoldesIntermediairesService sigService;
    private final BalanceAgeeService balanceAgeeService;

    @PostMapping("/pieces")
    @ResponseStatus(HttpStatus.CREATED)
    public PieceResponse creerPiece(@Valid @RequestBody PieceCreateRequest req) {
        return service.creerPiece(req);
    }

    @GetMapping("/pieces")
    public List<PieceResponse> listerPieces() {
        return service.listerPieces();
    }

    @GetMapping("/pieces/{id}")
    public PieceResponse consulterPiece(@PathVariable Long id) {
        return service.consulterPiece(id);
    }

    @PostMapping("/pieces/{id}/comptabiliser")
    public PieceResponse comptabiliser(@PathVariable Long id) {
        return service.comptabiliser(id);
    }

    @PutMapping("/pieces/{id}")
    public PieceResponse modifierPiece(@PathVariable Long id, @Valid @RequestBody PieceCreateRequest req) {
        return service.modifierPiece(id, req);
    }

    /**
     * Reclasse une pièce déjà comptabilisée en/hors solde d'ouverture (case à
     * cocher oubliée à la saisie) sans toucher à ses montants ni à ses
     * comptes — voir {@code ComptabiliteService.basculerSoldeOuverture}.
     */
    @PatchMapping("/pieces/{id}/solde-ouverture")
    public PieceResponse basculerSoldeOuverture(@PathVariable Long id, @Valid @RequestBody SoldeOuvertureRequest req) {
        return service.basculerSoldeOuverture(id, req.soldeOuverture());
    }

    @DeleteMapping("/pieces/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimerPiece(@PathVariable Long id) {
        service.supprimerPiece(id);
    }

    @PostMapping("/pieces/{id}/annuler")
    public PieceResponse annuler(@PathVariable Long id) {
        return service.annuler(id);
    }

    @GetMapping("/grand-livre")
    public GrandLivreCompteResponse grandLivreParCompte(
        @RequestParam String compte,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        return service.grandLivreParCompte(compte, du, au);
    }

    @GetMapping("/balance-verification")
    public BalanceVerificationResponse balanceVerification(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        return service.balanceVerification(du, au);
    }

    // ── États financiers ─────────────────────────────────────────────────

    @GetMapping("/livre-journal")
    public LivreJournalResponse livreJournal(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        return service.livreJournal(du, au);
    }

    @GetMapping("/compte-resultat")
    public CompteResultatResponse compteResultat(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        return service.compteResultat(du, au);
    }

    @GetMapping("/bilan")
    public BilanResponse bilan(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        return service.bilan(au);
    }

    /**
     * Classeur Excel complet des états financiers (Journal, Balance, Bilan,
     * Résultat, Flux de trésorerie, Ratios), sur le modèle du classeur de
     * suivi manuel de l'entreprise.
     */
    @GetMapping("/etats-financiers/export")
    public ResponseEntity<byte[]> exporterEtatsFinanciers(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        byte[] classeur = excelService.genererClasseur(du, au);
        LocalDate fin = au != null ? au : LocalDate.now();
        String nomFichier = "Etats_financiers_MBSC_" + fin.format(DateTimeFormatter.ISO_DATE) + ".xlsx";
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nomFichier + "\"")
            .body(classeur);
    }

    /**
     * Classeur Excel du seul journal comptable (une feuille « Journal »),
     * destiné à la reprise/correction hors ligne puis à la réimportation.
     */
    @GetMapping("/journal/export")
    public ResponseEntity<byte[]> exporterJournal(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        byte[] classeur = excelService.genererJournalSeul(du, au);
        LocalDate fin = au != null ? au : LocalDate.now();
        String nomFichier = "Journal_MBSC_" + fin.format(DateTimeFormatter.ISO_DATE) + ".xlsx";
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nomFichier + "\"")
            .body(classeur);
    }

    /**
     * Agent IA d'analyse financière : interprète Bilan, Compte de résultat et
     * Balance de vérification de la période pour produire une synthèse, des
     * points forts, des points de vigilance et des recommandations — affichés
     * en fin du document imprimable (PDF) des états financiers.
     */
    @PostMapping("/etats-financiers/analyse-ia")
    public AnalyseFinanciereResponse analyserEtatsFinanciers(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        return analyseIaService.analyser(du, au);
    }

    /** Balance agee des creances clients : ventilation du 4111 par anciennete. */
    @GetMapping("/balance-agee")
    public BalanceAgeeResponse balanceAgee(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        return balanceAgeeService.calculer(au);
    }

    /** Soldes intermediaires de gestion (cascade SYSCOHADA : marge, VA, EBE, resultats). */
    @GetMapping("/soldes-intermediaires")
    public SoldesIntermediairesResponse soldesIntermediaires(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        return sigService.calculer(du, au);
    }

    // ── TVA ─────────────────────────────────────────────────────────────

    @GetMapping("/tva")
    public TvaSituationResponse tva(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        return tvaService.situation(du, au);
    }

    /** Arrete la TVA de la periode : genere la piece BROUILLON qui solde 443x/445x vers 4441 ou 4449. */
    @PostMapping("/tva/declarer")
    public DeclarationTvaResponse declarerTva(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        return tvaService.declarer(du, au);
    }

    // ── Clôture de période ───────────────────────────────────────────────

    @GetMapping("/cloture")
    public ClotureResponse cloture() {
        return new ClotureResponse(periodeService.dateCloture());
    }

    @PutMapping("/cloture")
    public ClotureResponse definirCloture(@RequestBody ClotureRequest req) {
        return new ClotureResponse(periodeService.definirDateCloture(req.dateCloture()));
    }

    /**
     * Clôture annuelle formelle : réévalue les créances en devise étrangère,
     * solde les comptes de gestion (classes 6/7) vers le résultat, verrouille
     * la date. Distincte du simple verrou ci-dessus (utilisable seul pour un
     * arrêté mensuel qui n'appelle pas de clôture de comptes).
     */
    @PostMapping("/cloture/exercice")
    public ClotureExerciceResponse cloturerExercice(@Valid @RequestBody ClotureExerciceRequest req) {
        return clotureAnnuelleService.cloturerExercice(req.dateCloture());
    }
}
