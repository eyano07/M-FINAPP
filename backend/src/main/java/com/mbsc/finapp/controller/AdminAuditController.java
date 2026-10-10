package com.mbsc.finapp.controller;

import com.mbsc.finapp.audit.AuditAnalyseIaService;
import com.mbsc.finapp.audit.AuditLecteurService;
import com.mbsc.finapp.audit.AuditModele.Filtre;
import com.mbsc.finapp.audit.AuditModele.Page;
import com.mbsc.finapp.audit.AuditModele.Synthese;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/** Administration › Journal d'audit : lecture, synthèse, export et agent IA (administrateur seul). */
@RestController
@RequestMapping("/admin/audit")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AuditLecteurService lecteur;
    private final AuditAnalyseIaService analyse;

    public record QuestionRequest(@NotBlank @Size(max = 600) String question,
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au) {}

    @GetMapping("/evenements")
    public Page evenements(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au,
                           @RequestParam(required = false) String utilisateur, @RequestParam(required = false) String module,
                           @RequestParam(required = false) String operation, @RequestParam(required = false) String resultat,
                           @RequestParam(required = false) String type, @RequestParam(required = false) String q,
                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int taille) {
        return lecteur.page(new Filtre(du, au, utilisateur, module, operation, resultat, type, q), page, taille);
    }

    @GetMapping("/synthese")
    public Synthese synthese(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au) {
        return lecteur.synthese(new Filtre(du, au, null, null, null, null, null, null));
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> exporter(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au,
                                           @RequestParam(required = false) String utilisateur, @RequestParam(required = false) String module,
                                           @RequestParam(required = false) String operation, @RequestParam(required = false) String resultat,
                                           @RequestParam(required = false) String type, @RequestParam(required = false) String q) {
        String csv = lecteur.exporterCsv(new Filtre(du, au, utilisateur, module, operation, resultat, type, q));
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("journal-audit.csv").build().toString())
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    /** Question posée à l'agent IA sur la période. */
    @PostMapping("/interroger")
    public AuditAnalyseIaService.ReponseAudit interroger(@Valid @RequestBody QuestionRequest req) {
        return analyse.interroger(req.question(), req.du(), req.au());
    }
}
