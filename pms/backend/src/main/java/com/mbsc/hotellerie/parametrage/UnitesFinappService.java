package com.mbsc.hotellerie.parametrage;

import com.fasterxml.jackson.databind.JsonNode;
import com.mbsc.hotellerie.finapp.FinappClient;
import com.mbsc.hotellerie.finapp.FinappException;
import com.mbsc.hotellerie.securite.Session;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lien entre les unités de l'hôtellerie et le patrimoine de M-FINAPP :
 * <ul>
 *   <li>la liste des unités (numéro, type, établissement, meublé ou non) est recopiée dans M-FINAPP, où la logistique
 *       en fait des emplacements et y affecte les biens ; envoi par appel système signé, seulement quand elle change ;</li>
 *   <li>les biens affectés à une unité sont relus dans M-FINAPP (inventaire type d'un appartement, d'une chambre).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class UnitesFinappService {

    private static final Logger log = LoggerFactory.getLogger(UnitesFinappService.class);

    private final JdbcTemplate jdbc;
    private final FinappClient finapp;
    private volatile String dernierEnvoi;

    /** Toutes les minutes : renvoie la liste si elle a changé (création, renommage, désactivation d'une unité). */
    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void synchroniser() {
        List<Map<String, Object>> unites = jdbc.query("""
            select u.id, u.numero, t.nom as type_nom, e.nom as etab_nom, e.type as etab_type, t.ameublement, (u.actif and e.actif) as actif
            from unites u join types_unite t on t.id = u.type_id join etablissements e on e.id = u.etablissement_id order by u.id
            """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("numero", rs.getString("numero"));
            m.put("typeNom", rs.getString("type_nom"));
            m.put("etablissementNom", rs.getString("etab_nom"));
            m.put("etablissementType", rs.getString("etab_type"));
            m.put("ameublement", rs.getString("ameublement"));
            m.put("actif", rs.getBoolean("actif"));
            return m;
        });
        String empreinte = unites.toString();
        if (empreinte.equals(dernierEnvoi)) return;
        try {
            finapp.signe("PUT", "/pms/systeme/unites", unites, null);
            dernierEnvoi = empreinte;
            log.info("Unités transmises à M-FINAPP : {}", unites.size());
        } catch (FinappException e) {
            // Pas encore appairée, M-FINAPP arrêté ou ancienne version : nouvel essai à la prochaine minute.
            log.debug("Unités non transmises à M-FINAPP : {}", e.getMessage());
        }
    }

    /** Biens du patrimoine de M-FINAPP affectés à l'unité (code, désignation, catégorie, état). */
    public JsonNode patrimoine(Long uniteId, Session session) {
        return finapp.signe("GET", "/pms/patrimoine?uniteId=" + uniteId, null, session.jeton());
    }
}
