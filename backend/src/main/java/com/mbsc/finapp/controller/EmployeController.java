package com.mbsc.finapp.controller;

import com.mbsc.finapp.dto.drh.EmployeRequest;
import com.mbsc.finapp.dto.drh.EmployeResponse;
import com.mbsc.finapp.service.DrhExcelService;
import com.mbsc.finapp.service.EmployeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Personnel (module DRH_PERSONNEL). Le préfixe /drh/employes est rattaché à
 * {@code ModuleMetier.DRH_PERSONNEL} par {@code ModuleAccessFilter}.
 */
@RestController
@RequestMapping("/drh/employes")
@RequiredArgsConstructor
public class EmployeController {

    private final EmployeService service;
    private final DrhExcelService excelService;

    @GetMapping
    public List<EmployeResponse> lister(@RequestParam(required = false) String q) {
        return service.rechercher(q);
    }

    @GetMapping("/{id}")
    public EmployeResponse consulter(@PathVariable Long id) {
        return service.consulter(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeResponse creer(@Valid @RequestBody EmployeRequest req) {
        return service.creer(req);
    }

    @PutMapping("/{id}")
    public EmployeResponse modifier(@PathVariable Long id, @Valid @RequestBody EmployeRequest req) {
        return service.modifier(id, req);
    }

    @PatchMapping("/{id}/desactiver")
    public EmployeResponse desactiver(@PathVariable Long id) {
        return service.desactiver(id);
    }

    @PatchMapping("/{id}/reactiver")
    public EmployeResponse reactiver(@PathVariable Long id) {
        return service.reactiver(id);
    }

    /** Registre des employés en classeur Excel (voir {@code DrhExcelService}). */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exporter() {
        byte[] classeur = excelService.genererClasseurEmployes();
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Employes_MBSC.xlsx\"")
            .body(classeur);
    }
}
