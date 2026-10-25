package com.mbsc.finapp.controller;

import com.mbsc.finapp.domain.ParametresInstance;
import com.mbsc.finapp.domain.enums.ModeInstance;
import com.mbsc.finapp.dto.instance.CleAppairageResponse;
import com.mbsc.finapp.dto.instance.InstanceAdminResponse;
import com.mbsc.finapp.dto.instance.InstanceEtatResponse;
import com.mbsc.finapp.dto.instance.ServeurDistantRequest;
import com.mbsc.finapp.dto.instance.TestConnexionResponse;
import com.mbsc.finapp.service.instance.InstanceAdminService;
import com.mbsc.finapp.dto.instance.InstanceAdminVue;
import com.mbsc.finapp.service.instance.ActivationModeLocalService;
import com.mbsc.finapp.service.instance.ReplicationEmetteur;
import com.mbsc.finapp.service.instance.ReplicationSchemaVerifier;
import java.util.Map;
import com.mbsc.finapp.service.instance.InstanceService;
import com.mbsc.finapp.service.instance.JournalReplication;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mode du serveur : etat pour tout utilisateur connecte (bandeau, pastille), administration de l'appairage (ADMIN, droits
 * verifies par {@link InstanceAdminService}).
 */
@RestController
@RequiredArgsConstructor
public class InstanceController {

    private final InstanceService instance;
    private final InstanceAdminService admin;
    private final JournalReplication journal;
    private final ActivationModeLocalService activation;
    private final ReplicationSchemaVerifier verificateur;

    @GetMapping("/instance/etat")
    public InstanceEtatResponse etat() {
        ParametresInstance p = instance.etat();
        boolean local = p.getMode() == ModeInstance.LOCAL;
        return new InstanceEtatResponse(p.getMode().name(), p.getMode().lectureSeule(), p.getDerniereSynchro(),
            local ? journal.enAttente() : 0, local && p.getDerniereErreur() != null);
    }

    @GetMapping("/admin/instance")
    public InstanceAdminVue etatAdmin() {
        return new InstanceAdminVue(admin.etat(), activation.progression(), verificateur.pret(), verificateur.probleme());
    }

    @PostMapping("/admin/instance/cle-appairage")
    public CleAppairageResponse genererCle() {
        return admin.genererCle();
    }

    @PutMapping("/admin/instance/serveur-distant")
    public InstanceAdminResponse configurerServeurDistant(@Valid @RequestBody ServeurDistantRequest req) {
        return admin.configurerServeurDistant(req);
    }

    @PostMapping("/admin/instance/tester")
    public TestConnexionResponse tester() {
        return admin.tester();
    }

    @PostMapping("/admin/instance/activer-mode-local")
    public ActivationModeLocalService.Progression activerModeLocal() {
        return activation.activer();
    }

    @PostMapping("/admin/instance/desactiver-mode-local")
    public Map<String, String> desactiverModeLocal() {
        return Map.of("message", activation.desactiver());
    }

    @PostMapping("/admin/instance/reprendre-la-main")
    public Map<String, String> reprendreLaMain() {
        return Map.of("message", activation.reprendreLaMain());
    }

    @PostMapping("/admin/instance/synchroniser")
    public ReplicationEmetteur.Bilan synchroniser() {
        return admin.synchroniser();
    }

    @PostMapping("/admin/instance/resynchroniser")
    public Map<String, String> resynchroniser() {
        return Map.of("message", admin.resynchroniser());
    }
}
