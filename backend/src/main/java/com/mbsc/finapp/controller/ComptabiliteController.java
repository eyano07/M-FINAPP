package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.comptabilite.*;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.service.AnalyseFinanciereIaService;
import com.mbsc.finapp.service.ArchiveClotureService;
import com.mbsc.finapp.service.ExportEcrituresService;
import com.mbsc.finapp.dto.admin.CompteResponse;
import com.mbsc.finapp.service.ComptabiliteAnalytiqueService;
import com.mbsc.finapp.service.ComptabiliteService;
import com.mbsc.finapp.service.JournalCompteService;
import com.mbsc.finapp.service.EtatsFinanciersExcelService;
import com.mbsc.finapp.service.BalanceAgeeService;
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
    private final JournalCompteService journalCompte;
    private final EtatsFinanciersExcelService excelService;
    private final com.mbsc.finapp.service.ConversionPdfService conversionPdf;
    private final AnalyseFinanciereIaService analyseIaService;
    private final com.mbsc.finapp.service.ClotureGouvernanceService gouvernance;
    private final com.mbsc.finapp.service.ArchiveClotureService archives;
    private final com.mbsc.finapp.service.ExportEcrituresService exportEcritures;
    private final com.mbsc.finapp.service.ConsultationEvenementsService evenementsService;
    private final TvaService tvaService;
    private final SoldesIntermediairesService sigService;
    private final BalanceAgeeService balanceAgeeService;
    private final ComptabiliteAnalytiqueService analytiqueService;

    /** Comptabilité analytique (SYSCOHADA classe 9, comptes 92 à 99) : soldes sur la période. */
    @GetMapping("/analytique")
    public EtatAnalytiqueResponse etatAnalytique(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au) {
        return analytiqueService.etat(du, au);
    }

    /** Crée un sous-compte analytique (ex. 94 + 11 = 9411). */
    @PostMapping("/analytique/comptes")
    @ResponseStatus(HttpStatus.CREATED)
    public CompteResponse creerSousCompteAnalytique(@Valid @RequestBody SousCompteAnalytiqueRequest req) {
        return analytiqueService.creerSousCompte(req);
    }

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
        return service.basculerSoldeOuverture(id, req.soldeOuverture(), req.motif());
    }

    @DeleteMapping("/pieces/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimerPiece(@PathVariable Long id) {
        service.supprimerPiece(id);
    }

    /**
     * Restaure une pièce annulée par erreur : nouveau brouillon identique à faire valider par le DFIN (comptable), ou
     * comptabilisation rétablie tout de suite (DFIN, administrateur).
     */
    @PostMapping("/pieces/{id}/restaurer")
    public PieceResponse restaurerPiece(@PathVariable Long id) {
        return service.restaurer(id);
    }

    /** Annule (extourne) une pièce comptabilisée : le comptable seul. */
    @PostMapping("/pieces/{id}/annuler")
    public PieceResponse annuler(@PathVariable Long id) {
        return service.annuler(id);
    }

    /**
     * Journal d'un compte au choix ({@code compte} : numero du plan comptable, sous-comptes compris) du {@code du} au
     * {@code au} (mois en cours par defaut) : une ligne par mouvement avec note, objet, beneficiaire, contrepartie et
     * solde cumule ; {@code devise} : USD (par defaut) ou CDF.
     */
    @GetMapping("/journal-compte")
    public JournalCompteResponse journalCompte(
        @RequestParam String compte,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au,
        @RequestParam(required = false) Devise devise
    ) {
        return journalCompte.journal(compte, du, au, devise);
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
     * Le même classeur, converti en PDF par LibreOffice : toutes les feuilles (page de garde, fiches R1 à R4,
     * états, notes 1 à 36, journal, balance, ratios, graphiques), une feuille après l'autre (15/11/2026).
     */
    @GetMapping("/etats-financiers/export-pdf")
    public ResponseEntity<byte[]> exporterEtatsFinanciersPdf(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        byte[] pdf = conversionPdf.classeurEnPdf(excelService.genererClasseur(du, au));
        LocalDate fin = au != null ? au : LocalDate.now();
        String nomFichier = "Etats_financiers_MBSC_" + fin.format(DateTimeFormatter.ISO_DATE) + ".pdf";
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nomFichier + "\"")
            .body(pdf);
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

    // ── Clôture de période et d'exercice (audit SYSCOHADA du 10/11/2026, C-03, C-06, C-10) ──

    @GetMapping("/cloture")
    public EtatClotureResponse cloture() {
        return gouvernance.etat();
    }

    /** Avance le verrou (DFIN) ; le reculer ou le lever crée une demande à faire approuver par le DA ou le DG. */
    @PutMapping("/cloture")
    public EtatClotureResponse definirCloture(@Valid @RequestBody ClotureRequest req) {
        return gouvernance.definirVerrou(req.dateCloture(), req.motif());
    }

    /** Contrôles préalables à la clôture de l'exercice qui se termine à la date donnée. */
    @GetMapping("/cloture/exercice/controles")
    public List<ControleClotureResponse> controlesCloture(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return gouvernance.controles(date);
    }

    /**
     * Demande de clôture définitive de l'exercice (DFIN) : réévaluation des soldes en devise, soldage des classes 6,
     * 7 et 8 vers le résultat, verrou définitif et archive des livres — exécutés quand le DA ou le DG approuve.
     */
    @PostMapping("/cloture/exercice")
    public EtatClotureResponse demanderClotureExercice(@Valid @RequestBody ClotureExerciceRequest req) {
        return gouvernance.demanderClotureExercice(req.dateCloture(), req.motif());
    }

    @PostMapping("/cloture/demandes/{id}/approuver")
    public ClotureExerciceResponse approuverDemande(@PathVariable Long id, @Valid @RequestBody DecisionRequest req) {
        return gouvernance.approuver(id, req.commentaire());
    }

    @PostMapping("/cloture/demandes/{id}/refuser")
    public EtatClotureResponse refuserDemande(@PathVariable Long id, @Valid @RequestBody DecisionRequest req) {
        return gouvernance.refuser(id, req.commentaire());
    }

    @PostMapping("/cloture/demandes/{id}/retirer")
    public EtatClotureResponse retirerDemande(@PathVariable Long id) {
        return gouvernance.retirer(id);
    }

    /** Dérogation à la validation par une autre personne (DA, DG), motif obligatoire. */
    @PutMapping("/cloture/auto-validation")
    public EtatClotureResponse definirAutoValidation(@RequestParam boolean autorisee,
                                                     @Valid @RequestBody DecisionRequest req) {
        return gouvernance.definirAutoValidation(autorisee, req.commentaire());
    }

    /** Archives figées des clôtures d'exercice (C-13). */
    @GetMapping("/cloture/archives")
    public List<ArchiveClotureService.Archive> archivesCloture() {
        return archives.lister();
    }

    @GetMapping("/cloture/archives/{id}")
    public ResponseEntity<byte[]> telechargerArchive(@PathVariable Long id) {
        byte[] contenu = archives.contenu(id);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"archive_cloture_" + id + ".zip\"")
            .body(contenu);
    }

    // ── Éditions et piste d'audit (C-09, C-19, C-25) ──────────────────────

    /** Fichier de toutes les écritures de la période (CSV, une ligne par écriture). */
    @GetMapping("/ecritures/export")
    public ResponseEntity<byte[]> exporterEcritures(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        byte[] fichier = exportEcritures.fichierEcritures(du, au);
        String nom = "Ecritures_" + du.format(DateTimeFormatter.BASIC_ISO_DATE) + "_"
            + au.format(DateTimeFormatter.BASIC_ISO_DATE) + ".csv";
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nom + "\"")
            .body(fichier);
    }

    @GetMapping("/grand-livre-general")
    public List<ExportEcrituresService.CompteGrandLivre> grandLivreGeneral(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        return exportEcritures.grandLivreGeneral(du, au);
    }

    @GetMapping("/centralisation")
    public List<ExportEcrituresService.LigneCentralisation> centralisation(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au
    ) {
        return exportEcritures.centralisation(du, au);
    }

    /** Journal des événements comptables (ajout seul, chaîné) et contrôle de sa chaîne d'empreintes. */
    @GetMapping("/evenements")
    public JournalEvenementsResponse evenements(@RequestParam(defaultValue = "200") int limite) {
        return evenementsService.consulter(limite);
    }
}
