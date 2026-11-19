package com.mbsc.hotellerie.canal;

import com.mbsc.hotellerie.audit.Journal;
import com.mbsc.hotellerie.commun.Refus;
import com.mbsc.hotellerie.reservation.Durees;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canaux de vente par calendrier iCal (phase 2). Chaque unité publie son flux (dates occupées dans l'hôtellerie) et
 * lit ceux de Booking.com, Airbnb, Expedia/VRBO toutes les 10 minutes :
 * <ul>
 *   <li>nouvel événement → réservation CONFIRMEE du canal (client « Client Booking.com », prix à compléter) ;</li>
 *   <li>dates changées → réservation déplacée ; événement retiré ou annulé → réservation annulée (si pas encore arrivée) ;</li>
 *   <li>dates déjà prises dans l'hôtellerie → aucune surréservation : une alerte à traiter par la réception.</li>
 * </ul>
 */
@Service
public class CanalService {

    private static final Logger log = LoggerFactory.getLogger(CanalService.class);
    static final Map<String, String> NOMS = Map.of("BOOKING", "Booking.com", "AIRBNB", "Airbnb", "EXPEDIA", "Expedia / VRBO",
        "AUTRE", "autre canal");

    private final JdbcTemplate jdbc;
    private final AdaptateurCanal adaptateur;
    private final Journal journal;
    private final TransactionTemplate transaction;

    public CanalService(JdbcTemplate jdbc, AdaptateurCanal adaptateur, Journal journal, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.adaptateur = adaptateur;
        this.journal = journal;
        this.transaction = transaction;
    }

    public record Canal(Long id, Long uniteId, String uniteNumero, String etablissementNom, String canal, String url, boolean actif,
                        OffsetDateTime dernierImport, String dernierStatut, String dernierMessage, Integer nbEvenements) { }

    public record Export(Long uniteId, String uniteNumero, String typeNom, String etablissementNom, String jeton) { }

    public record Alerte(Long id, Long canalId, String canal, String uniteNumero, String uidExterne, LocalDate debut, LocalDate fin,
                         String message, OffsetDateTime creeLe) { }

    public record CanalRequest(@NotNull Long uniteId, @NotNull @Pattern(regexp = "BOOKING|AIRBNB|EXPEDIA|AUTRE") String canal,
                               @NotBlank @Size(max = 1000) String url, Boolean actif) { }

    public record Bilan(int crees, int deplaces, int annules, int conflits, int evenements) { }

    public List<Canal> canaux() {
        return jdbc.query("""
            select c.*, u.numero, e.nom as etab from canaux_ical c join unites u on u.id = c.unite_id
            join etablissements e on e.id = u.etablissement_id order by e.nom, u.ordre, u.numero, c.canal
            """, (rs, i) -> new Canal(rs.getLong("id"), rs.getLong("unite_id"), rs.getString("numero"), rs.getString("etab"),
            rs.getString("canal"), rs.getString("url"), rs.getBoolean("actif"), rs.getObject("dernier_import", OffsetDateTime.class),
            rs.getString("dernier_statut"), rs.getString("dernier_message"), (Integer) rs.getObject("nb_evenements")));
    }

    public List<Export> exports() {
        return jdbc.query("""
            select u.id, u.numero, t.nom as type, e.nom as etab, u.jeton_ical from unites u join types_unite t on t.id = u.type_id
            join etablissements e on e.id = u.etablissement_id where u.actif order by e.nom, u.ordre, u.numero
            """, (rs, i) -> new Export(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
    }

    public List<Alerte> alertes() {
        return jdbc.query("""
            select a.*, c.canal, u.numero from alertes_canaux a join canaux_ical c on c.id = a.canal_ical_id
            join unites u on u.id = c.unite_id where not a.traitee order by a.debut
            """, (rs, i) -> new Alerte(rs.getLong("id"), rs.getLong("canal_ical_id"), rs.getString("canal"), rs.getString("numero"),
            rs.getString("uid_externe"), rs.getDate("debut") == null ? null : rs.getDate("debut").toLocalDate(),
            rs.getDate("fin") == null ? null : rs.getDate("fin").toLocalDate(), rs.getString("message"),
            rs.getObject("cree_le", OffsetDateTime.class)));
    }

    public Canal enregistrer(Long id, CanalRequest r) {
        AdaptateurIcal.verifier(r.url());
        boolean actif = r.actif() == null || r.actif();
        if (id == null) {
            id = jdbc.queryForObject("insert into canaux_ical (unite_id, canal, url, actif) values (?, ?, ?, ?) returning id",
                Long.class, r.uniteId(), r.canal(), r.url().strip(), actif);
        } else if (jdbc.update("update canaux_ical set canal = ?, url = ?, actif = ? where id = ?", r.canal(), r.url().strip(), actif, id) == 0) {
            throw Refus.introuvable("Canal");
        }
        journal.noter("CANAL_ICAL", NOMS.get(r.canal()), "unité " + r.uniteId() + (actif ? "" : " (désactivé)"));
        Long cle = id;
        return canaux().stream().filter(c -> c.id().equals(cle)).findFirst().orElseThrow();
    }

    public void supprimer(Long id) {
        jdbc.update("update reservations set canal_ical_id = null where canal_ical_id = ?", id);
        jdbc.update("delete from canaux_ical where id = ?", id);
        journal.noter("CANAL_ICAL_RETIRE", "canal " + id, null);
    }

    public void alerteTraitee(Long id) {
        jdbc.update("update alertes_canaux set traitee = true where id = ?", id);
    }

    public String renouvelerJeton(Long uniteId) {
        String jeton = jdbc.queryForObject("""
            update unites set jeton_ical = md5(random()::text || clock_timestamp()::text) where id = ? returning jeton_ical
            """, String.class, uniteId);
        journal.noter("ICAL_RENOUVELE", "unité " + uniteId, "ancienne adresse du flux désactivée");
        return jeton;
    }

    /** Flux iCal d'une unité : ses périodes occupées (réservations actives), depuis hier jusqu'à 18 mois. */
    public String flux(String jeton) {
        var unite = jdbc.query("""
            select u.id, u.numero, e.nom from unites u join etablissements e on e.id = u.etablissement_id where u.jeton_ical = ? and u.actif
            """, rs -> rs.next() ? new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3)} : null, jeton);
        if (unite == null) throw Refus.introuvable("Calendrier");
        List<Ical.Evenement> occupations = jdbc.query("""
            select reference, debut, fin from reservations where unite_id = ? and statut in ('OPTION', 'CONFIRMEE', 'ARRIVEE')
              and fin > now() - interval '1 day' and debut < now() + interval '18 months' and canal_ical_id is null
            order by debut
            """, (rs, i) -> new Ical.Evenement(rs.getString(1) + "@hotellerie",
            rs.getObject(2, OffsetDateTime.class).atZoneSameInstant(Durees.FUSEAU).toLocalDate(),
            rs.getObject(3, OffsetDateTime.class).atZoneSameInstant(Durees.FUSEAU).toLocalDate(), "Réservé", false), unite[0]);
        // Les réservations venues d'un canal sont renvoyées aussi, pour que les AUTRES canaux voient ces dates occupées.
        occupations = new java.util.ArrayList<>(occupations);
        occupations.addAll(jdbc.query("""
            select coalesce(uid_externe, reference) || '-relais', debut, fin from reservations where unite_id = ?
              and statut in ('OPTION', 'CONFIRMEE', 'ARRIVEE') and fin > now() - interval '1 day' and canal_ical_id is not null
            """, (rs, i) -> new Ical.Evenement(rs.getString(1),
            rs.getObject(2, OffsetDateTime.class).atZoneSameInstant(Durees.FUSEAU).toLocalDate(),
            rs.getObject(3, OffsetDateTime.class).atZoneSameInstant(Durees.FUSEAU).toLocalDate(), "Réservé", false), unite[0]));
        return Ical.ecrire(unite[2] + " — " + unite[1], occupations);
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 600_000)
    public void synchroniserTout() {
        for (Long id : jdbc.queryForList("select id from canaux_ical where actif", Long.class)) {
            try {
                synchroniser(id);
            } catch (RuntimeException e) {
                log.warn("Canal {} : {}", id, e.getMessage());
            }
        }
    }

    /** Lit le calendrier d'un canal et applique ses réservations à l'unité. */
    public Bilan synchroniser(Long canalId) {
        Canal c = canaux().stream().filter(x -> x.id().equals(canalId)).findFirst().orElseThrow(() -> Refus.introuvable("Canal"));
        List<Ical.Evenement> evenements;
        try {
            evenements = adaptateur.lire(c.url());
        } catch (Refus e) {
            jdbc.update("update canaux_ical set dernier_import = now(), dernier_statut = 'ERREUR', dernier_message = ? where id = ?",
                e.getMessage(), canalId);
            throw e;
        }
        String nomCanal = NOMS.get(c.canal());
        Long clientId = clientDuCanal(c.canal(), nomCanal);
        LocalTime[] heures = jdbc.queryForObject("""
            select e.heure_arrivee, e.heure_depart from unites u join etablissements e on e.id = u.etablissement_id where u.id = ?
            """, (rs, i) -> new LocalTime[] {rs.getTime(1).toLocalTime(), rs.getTime(2).toLocalTime()}, c.uniteId());
        Long etablissementId = jdbc.queryForObject("select etablissement_id from unites where id = ?", Long.class, c.uniteId());
        LocalDate hier = LocalDate.now(Durees.FUSEAU).minusDays(1);
        int crees = 0, deplaces = 0, annules = 0, conflits = 0;
        Set<String> vus = new HashSet<>();
        for (Ical.Evenement e : evenements) {
            if (e.annule() || !e.fin().isAfter(hier)) continue;
            vus.add(e.uid());
            OffsetDateTime debut = e.debut().atTime(heures[0]).atZone(Durees.FUSEAU).toOffsetDateTime();
            OffsetDateTime fin = e.fin().atTime(heures[1]).atZone(Durees.FUSEAU).toOffsetDateTime();
            try {
                String resultat = transaction.execute(s -> appliquer(c, e, debut, fin, clientId, etablissementId, nomCanal));
                if ("CREE".equals(resultat)) crees++;
                if ("DEPLACE".equals(resultat)) deplaces++;
            } catch (DataIntegrityViolationException ex) {
                conflits++;
                jdbc.update("""
                    insert into alertes_canaux (canal_ical_id, uid_externe, debut, fin, message) values (?, ?, ?, ?, ?)
                    on conflict (canal_ical_id, uid_externe, debut) do nothing
                    """, canalId, e.uid(), e.debut(), e.fin(), "Réservation " + nomCanal + " du " + e.debut() + " au " + e.fin()
                    + " : l'unité " + c.uniteNumero() + " est déjà occupée dans l'hôtellerie. Déplacez l'une des deux réservations ou "
                    + "refusez-la sur " + nomCanal + ".");
            }
        }
        // Événements retirés du flux : réservation annulée tant que le client n'est pas arrivé.
        for (var r : jdbc.queryForList("""
            select id, reference, uid_externe from reservations where canal_ical_id = ? and statut in ('OPTION', 'CONFIRMEE')
            """, canalId)) {
            if (!vus.contains((String) r.get("uid_externe"))) {
                jdbc.update("update reservations set statut = 'ANNULEE', motif_annulation = ?, maj_le = now() where id = ?",
                    "Retirée du calendrier " + nomCanal, r.get("id"));
                journal.noter("CANAL_ANNULATION", (String) r.get("reference"), "retirée du calendrier " + nomCanal);
                annules++;
            }
        }
        String message = evenements.size() + " événement(s) lus : " + crees + " créé(s), " + deplaces + " déplacé(s), " + annules
            + " annulé(s)" + (conflits > 0 ? ", " + conflits + " conflit(s) à traiter" : "") + ".";
        jdbc.update("""
            update canaux_ical set dernier_import = now(), dernier_statut = ?, dernier_message = ?, nb_evenements = ? where id = ?
            """, conflits > 0 ? "CONFLIT" : "OK", message, evenements.size(), canalId);
        if (crees + deplaces + annules + conflits > 0) journal.noter("CANAL_SYNCHRO", nomCanal + " / unité " + c.uniteNumero(), message);
        return new Bilan(crees, deplaces, annules, conflits, evenements.size());
    }

    private String appliquer(Canal c, Ical.Evenement e, OffsetDateTime debut, OffsetDateTime fin, Long clientId, Long etablissementId,
                             String nomCanal) {
        var existante = jdbc.queryForList("""
            select id, debut, fin, statut from reservations where canal_ical_id = ? and uid_externe = ?
            """, c.id(), e.uid());
        if (existante.isEmpty()) {
            String reference = "RES-" + Year.now(Durees.FUSEAU) + "-"
                + String.format("%06d", jdbc.queryForObject("select nextval('reference_reservation_seq')", Long.class));
            jdbc.update("""
                insert into reservations (reference, etablissement_id, unite_id, client_id, debut, fin, unite_duree, prix_unitaire,
                canal, statut, notes, canal_ical_id, uid_externe, cree_par)
                values (?, ?, ?, ?, ?, ?, 'NUIT', 0, ?, 'CONFIRMEE', ?, ?, ?, 'synchronisation ' || ?)
                """, reference, etablissementId, c.uniteId(), clientId, debut, fin, c.canal(),
                "Réservation " + nomCanal + (e.resume() == null ? "" : " (" + e.resume() + ")")
                    + " : identité du client et prix à compléter (le calendrier iCal ne les transmet pas).",
                c.id(), e.uid(), nomCanal);
            journal.noter("CANAL_RESERVATION", reference, nomCanal + ", unité " + c.uniteNumero() + ", " + e.debut() + " → " + e.fin());
            return "CREE";
        }
        var r = existante.get(0);
        if (!java.util.Set.of("OPTION", "CONFIRMEE").contains((String) r.get("statut"))) return "INCHANGE";
        OffsetDateTime d = ((java.sql.Timestamp) r.get("debut")).toInstant().atOffset(java.time.ZoneOffset.UTC);
        OffsetDateTime f = ((java.sql.Timestamp) r.get("fin")).toInstant().atOffset(java.time.ZoneOffset.UTC);
        if (d.isEqual(debut) && f.isEqual(fin)) return "INCHANGE";
        jdbc.update("update reservations set debut = ?, fin = ?, maj_le = now() where id = ?", debut, fin, r.get("id"));
        return "DEPLACE";
    }

    /** Client générique du canal (le flux iCal ne donne pas l'identité) ; la réception le remplace par le vrai client. */
    private Long clientDuCanal(String canal, String nomCanal) {
        String nom = "Client " + nomCanal;
        return jdbc.query("select id from clients where nom = ? and prenom is null order by id limit 1", rs -> rs.next() ? rs.getLong(1) : null, nom) instanceof Long id
            ? id
            : jdbc.queryForObject("insert into clients (nom, notes) values (?, ?) returning id", Long.class, nom,
                "Client générique des réservations reçues de " + nomCanal + " par calendrier iCal.");
    }
}
