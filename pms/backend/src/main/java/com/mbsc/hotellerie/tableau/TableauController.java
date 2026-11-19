package com.mbsc.hotellerie.tableau;

import com.mbsc.hotellerie.securite.SessionCourante;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tableau de bord du jour : arrivées, départs, présents, occupation, ménage, encaissements en attente. */
@RestController
@RequiredArgsConstructor
public class TableauController {

    private final JdbcTemplate jdbc;

    @GetMapping("/tableau-de-bord")
    public Map<String, Object> tableau(@RequestParam(required = false) Long etablissementId) {
        SessionCourante.exiger();
        Object[] p = {etablissementId, etablissementId};
        String filtre = " and (?::bigint is null or r.etablissement_id = ?)";
        Map<String, Object> t = new LinkedHashMap<>();
        long unites = jdbc.queryForObject("select count(*) from unites u where u.actif and not u.hors_service"
            + " and (?::bigint is null or u.etablissement_id = ?)", Long.class, p);
        long occupees = jdbc.queryForObject("select count(distinct r.unite_id) from reservations r where r.statut = 'ARRIVEE'"
            + filtre, Long.class, p);
        t.put("unitesDisponibles", unites);
        t.put("unitesOccupees", occupees);
        t.put("tauxOccupation", unites == 0 ? 0 : Math.round(occupees * 1000.0 / unites) / 10.0);
        t.put("arriveesDuJour", liste("""
            r.statut in ('OPTION', 'CONFIRMEE') and r.debut < date_trunc('day', now()) + interval '1 day'
            and r.debut >= date_trunc('day', now()) - interval '1 day'
            """ + filtre, p));
        t.put("departsDuJour", liste("r.statut = 'ARRIVEE' and r.fin < date_trunc('day', now()) + interval '1 day'" + filtre, p));
        t.put("presents", jdbc.queryForObject("select count(*) from reservations r where r.statut = 'ARRIVEE'" + filtre, Long.class, p));
        t.put("encaissementsEnAttente", liste("r.encaissement = 'EN_ATTENTE'" + filtre, p));
        t.put("unitesANettoyer", jdbc.queryForObject("select count(*) from unites u where u.actif and u.etat_menage <> 'PROPRE'"
            + " and (?::bigint is null or u.etablissement_id = ?)", Long.class, p));
        t.put("demandesEnLigne", liste("r.demande_publique and r.statut = 'OPTION'" + filtre, p));
        t.put("alertesCanaux", jdbc.queryForObject("select count(*) from alertes_canaux where not traitee", Long.class));
        t.put("unitesHorsService", jdbc.queryForObject("select count(*) from unites u where u.actif and u.hors_service"
            + " and (?::bigint is null or u.etablissement_id = ?)", Long.class, p));
        return t;
    }

    private List<Map<String, Object>> liste(String condition, Object[] p) {
        return jdbc.queryForList("""
            select r.id, r.reference, r.debut, r.fin, r.statut, r.encaissement, u.numero as unite,
                   trim(coalesce(c.prenom, '') || ' ' || c.nom) as client
            from reservations r join unites u on u.id = r.unite_id join clients c on c.id = r.client_id
            where""" + " " + condition + " order by r.debut limit 100", p);
    }
}
