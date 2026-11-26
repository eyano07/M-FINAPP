package com.mbsc.hotellerie.menage;

import com.mbsc.hotellerie.audit.Journal;
import com.mbsc.hotellerie.commun.Refus;
import com.mbsc.hotellerie.securite.Session;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * Ménage : état de chaque unité (propre, à nettoyer, en cours, à inspecter) et tâches de nettoyage. Un départ crée une
 * tâche « départ » ; la terminer rend l'unité propre (ou « à inspecter » si la direction l'exige).
 */
@Service
@RequiredArgsConstructor
public class MenageService {

    private static final Set<String> ETATS = Set.of("PROPRE", "A_NETTOYER", "EN_COURS", "A_INSPECTER");

    private final JdbcTemplate jdbc;
    private final Journal journal;

    public record Tache(Long id, Long uniteId, String uniteNumero, String etablissementNom, Long reservationId, String type,
                        String statut, String priorite, String assigneEmail, String assigneNom, OffsetDateTime echeance,
                        String notes, OffsetDateTime creeLe, OffsetDateTime termineLe, String terminePar) { }

    public record EtatUnite(Long id, Long etablissementId, String etablissementNom, String numero, String typeNom, String etage,
                            String etatMenage, boolean horsService, String motifHorsService, boolean occupee,
                            String clientActuel, OffsetDateTime departPrevu, OffsetDateTime arriveePrevue) { }

    /** Tableau du ménage : chaque unité, son état, occupée ou non, départ et prochaine arrivée du jour. */
    public List<EtatUnite> etats(Long etablissementId) {
        return jdbc.query("""
            select u.id, u.etablissement_id, e.nom as etab, u.numero, t.nom as type, u.etage, u.etat_menage, u.hors_service,
                   u.motif_hors_service,
                   occ.client as client_actuel, occ.fin as depart_prevu,
                   (select min(r2.debut) from reservations r2 where r2.unite_id = u.id and r2.statut in ('OPTION', 'CONFIRMEE')
                       and r2.debut >= now() - interval '1 day') as arrivee_prevue
            from unites u join etablissements e on e.id = u.etablissement_id join types_unite t on t.id = u.type_id
            left join lateral (
                select trim(coalesce(c.prenom, '') || ' ' || c.nom) as client, r.fin from reservations r
                join clients c on c.id = r.client_id where r.unite_id = u.id and r.statut = 'ARRIVEE' limit 1
            ) occ on true
            where u.actif and (?::bigint is null or u.etablissement_id = ?)
            order by e.nom, u.ordre, u.numero
            """, (rs, i) -> new EtatUnite(rs.getLong("id"), rs.getLong("etablissement_id"), rs.getString("etab"),
            rs.getString("numero"), rs.getString("type"), rs.getString("etage"), rs.getString("etat_menage"),
            rs.getBoolean("hors_service"), rs.getString("motif_hors_service"), rs.getString("client_actuel") != null,
            rs.getString("client_actuel"), rs.getObject("depart_prevu", OffsetDateTime.class),
            rs.getObject("arrivee_prevue", OffsetDateTime.class)), etablissementId, etablissementId);
    }

    public List<Tache> taches(boolean toutes, Long etablissementId) {
        return jdbc.query("""
            select ta.*, u.numero, e.nom as etab from taches_menage ta join unites u on u.id = ta.unite_id
            join etablissements e on e.id = u.etablissement_id
            where (? or ta.statut <> 'TERMINEE' or ta.termine_le > now() - interval '1 day')
              and (?::bigint is null or u.etablissement_id = ?)
            order by case ta.statut when 'EN_COURS' then 0 when 'A_FAIRE' then 1 else 2 end,
                     case ta.priorite when 'HAUTE' then 0 when 'NORMALE' then 1 else 2 end, ta.cree_le
            """, (rs, i) -> new Tache(rs.getLong("id"), rs.getLong("unite_id"), rs.getString("numero"), rs.getString("etab"),
            (Long) rs.getObject("reservation_id"), rs.getString("type"), rs.getString("statut"), rs.getString("priorite"),
            rs.getString("assigne_email"), rs.getString("assigne_nom"), rs.getObject("echeance", OffsetDateTime.class),
            rs.getString("notes"), rs.getObject("cree_le", OffsetDateTime.class), rs.getObject("termine_le", OffsetDateTime.class),
            rs.getString("termine_par")), toutes, etablissementId, etablissementId);
    }

    /** Tâche créée à un départ (ou délogement) : priorité haute si une arrivée est attendue aujourd'hui dans l'unité. */
    @Transactional
    public void creerTache(Long uniteId, Long reservationId, String type, String notes) {
        boolean arriveeAujourdhui = Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists (select 1 from reservations where unite_id = ? and statut in ('OPTION', 'CONFIRMEE')
                and debut < date_trunc('day', now()) + interval '1 day')
            """, Boolean.class, uniteId));
        jdbc.update("""
            insert into taches_menage (unite_id, reservation_id, type, priorite, notes) values (?, ?, ?, ?, ?)
            """, uniteId, reservationId, type, arriveeAujourdhui ? "HAUTE" : "NORMALE", notes);
    }

    public record TacheRequest(Long uniteId, String type, String priorite, String notes) { }

    @Transactional
    public Tache creer(TacheRequest r) {
        if (r.uniteId() == null) throw Refus.invalide("Choisissez l'unité.");
        Long id = jdbc.queryForObject("""
            insert into taches_menage (unite_id, type, priorite, notes) values (?, ?, ?, ?) returning id
            """, Long.class, r.uniteId(), r.type() == null ? "AUTRE" : r.type(), r.priorite() == null ? "NORMALE" : r.priorite(), r.notes());
        journal.noter("TACHE_CREEE", "tâche " + id, r.notes());
        return tache(id);
    }

    @Transactional
    public Tache affecter(Long id, String email, String nom) {
        if (jdbc.update("update taches_menage set assigne_email = ?, assigne_nom = ? where id = ?", email, nom, id) == 0) {
            throw Refus.introuvable("Tâche");
        }
        journal.noter("TACHE_AFFECTEE", "tâche " + id, nom);
        return tache(id);
    }

    @Transactional
    public Tache commencer(Long id, Session s) {
        Tache t = tache(id);
        if ("TERMINEE".equals(t.statut())) throw Refus.conflit("Tâche déjà terminée.");
        jdbc.update("""
            update taches_menage set statut = 'EN_COURS', commence_le = now(),
            assigne_email = coalesce(assigne_email, ?), assigne_nom = coalesce(assigne_nom, ?) where id = ?
            """, s.email(), s.nomComplet(), id);
        jdbc.update("update unites set etat_menage = 'EN_COURS' where id = ?", t.uniteId());
        journal.noter("TACHE_COMMENCEE", "unité " + t.uniteNumero(), null);
        return tache(id);
    }

    @Transactional
    public Tache terminer(Long id, Session s, boolean aInspecter) {
        Tache t = tache(id);
        if ("TERMINEE".equals(t.statut())) return t;
        jdbc.update("update taches_menage set statut = 'TERMINEE', termine_le = now(), termine_par = ? where id = ?", s.email(), id);
        boolean autres = Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists (select 1 from taches_menage where unite_id = ? and statut <> 'TERMINEE')", Boolean.class, t.uniteId()));
        if (!autres) {
            jdbc.update("update unites set etat_menage = ? where id = ?", aInspecter ? "A_INSPECTER" : "PROPRE", t.uniteId());
        }
        journal.noter("TACHE_TERMINEE", "unité " + t.uniteNumero(), aInspecter ? "à inspecter" : "propre");
        return tache(id);
    }

    @Transactional
    public EtatUnite changerEtat(Long uniteId, String etat) {
        if (!ETATS.contains(etat)) throw Refus.invalide("État de ménage inconnu : " + etat);
        if (jdbc.update("update unites set etat_menage = ? where id = ?", etat, uniteId) == 0) throw Refus.introuvable("Unité");
        if ("PROPRE".equals(etat)) {
            jdbc.update("""
                update taches_menage set statut = 'TERMINEE', termine_le = now(), termine_par = 'état passé à propre'
                where unite_id = ? and statut <> 'TERMINEE'
                """, uniteId);
        }
        journal.noter("ETAT_MENAGE", "unité " + uniteId, etat);
        return etats(null).stream().filter(e -> e.id().equals(uniteId)).findFirst().orElseThrow();
    }

    private Tache tache(Long id) {
        return taches(true, null).stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow(() -> Refus.introuvable("Tâche"));
    }
}
