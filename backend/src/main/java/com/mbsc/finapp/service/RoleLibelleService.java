package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.RoleLibelle;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.repository.RoleLibelleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.Map;

/**
 * Libelles d'affichage des roles, modifiables par l'ADMIN. Changement de
 * forme uniquement : le role technique, ses droits et les gardes backend
 * ne dependent jamais du libelle.
 */
@Service
@RequiredArgsConstructor
public class RoleLibelleService {

    private static final Map<RoleType, String> PAR_DEFAUT = new EnumMap<>(RoleType.class);
    static {
        PAR_DEFAUT.put(RoleType.ADMIN, "Administrateur");
        PAR_DEFAUT.put(RoleType.DG, "DG");
        PAR_DEFAUT.put(RoleType.DA, "DA");
        PAR_DEFAUT.put(RoleType.DFIN, "DFIN");
        PAR_DEFAUT.put(RoleType.DIRECTEUR, "Directeur métier");
        PAR_DEFAUT.put(RoleType.CAISSIER, "Caissier");
        PAR_DEFAUT.put(RoleType.COMPTABLE, "Comptable");
        PAR_DEFAUT.put(RoleType.LOGISTIQUE, "Logistique");
        PAR_DEFAUT.put(RoleType.GEST_PATRIMOINE, "Gestionnaire patrimoine");
        PAR_DEFAUT.put(RoleType.RESP_DRH, "Responsable DRH");
        PAR_DEFAUT.put(RoleType.RESP_RESTAURANT, "Responsable restaurant");
    }

    private final RoleLibelleRepository repository;

    /** Libelle effectif de chaque role (personnalise sinon par defaut). */
    @Transactional(readOnly = true)
    public Map<RoleType, String> lister() {
        Map<RoleType, String> resultat = new EnumMap<>(PAR_DEFAUT);
        repository.findAll().forEach(rl -> resultat.put(rl.getRole(), rl.getLibelle()));
        return resultat;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Map<RoleType, String> definir(RoleType role, String libelle) {
        String propre = libelle == null ? "" : libelle.trim();
        if (propre.isEmpty() || propre.equals(PAR_DEFAUT.get(role))) {
            repository.deleteById(role);
            repository.flush();
        } else {
            repository.save(RoleLibelle.builder().role(role).libelle(propre).build());
        }
        return lister();
    }
}
