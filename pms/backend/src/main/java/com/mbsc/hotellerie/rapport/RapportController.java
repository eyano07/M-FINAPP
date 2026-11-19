package com.mbsc.hotellerie.rapport;

import com.mbsc.hotellerie.securite.SessionCourante;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Rapports : la direction de l'hébergement, l'administration, la direction générale et la comptabilité. */
@RestController
@RequiredArgsConstructor
public class RapportController {

    private final RapportService service;

    @GetMapping("/rapports/synthese")
    public RapportService.Synthese synthese(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au,
                                            @RequestParam(required = false) Long etablissementId,
                                            @RequestParam(defaultValue = "false") boolean previsionnel) {
        SessionCourante.exiger("DIR_HEBERGEMENT", "ADMIN", "DG", "DFIN", "DA", "COMPTABLE");
        return service.synthese(du, au, etablissementId, previsionnel);
    }
}
