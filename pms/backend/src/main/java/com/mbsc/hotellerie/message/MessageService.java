package com.mbsc.hotellerie.message;

import com.mbsc.hotellerie.appairage.AppairageService;
import com.mbsc.hotellerie.audit.Journal;
import com.mbsc.hotellerie.commun.Refus;
import com.mbsc.hotellerie.finapp.FinappClient;
import com.mbsc.hotellerie.finapp.FinappException;
import com.mbsc.hotellerie.reservation.Durees;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Messages automatiques aux clients (phase 2), envoyés par e-mail avec la messagerie de M-FINAPP :
 * confirmation (réservation confirmée), rappel d'arrivée (la veille), code d'accès (à l'arrivée, si un code est donné),
 * remerciement (après le départ). Un message par séjour et par type, jamais deux ; un échec se retente après 30 minutes.
 * WhatsApp et SMS pourront s'ajouter derrière la même file, quand un fournisseur sera choisi.
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH'h'mm");

    private final JdbcTemplate jdbc;
    private final FinappClient finapp;
    private final AppairageService appairage;
    private final Journal journal;

    public MessageService(JdbcTemplate jdbc, FinappClient finapp, AppairageService appairage, Journal journal) {
        this.jdbc = jdbc;
        this.finapp = finapp;
        this.appairage = appairage;
        this.journal = journal;
    }

    public record Modele(String type, boolean actif, String sujet, String corps, OffsetDateTime majLe, String majPar) { }

    public record ModeleRequest(Boolean actif, @NotBlank @Size(max = 200) String sujet, @NotBlank @Size(max = 8000) String corps) { }

    public record Envoi(Long id, Long reservationId, String reference, String type, String destinataire, String statut, String detail,
                        OffsetDateTime envoyeLe) { }

    public List<Modele> modeles() {
        return jdbc.query("select * from messages_modeles order by case type when 'CONFIRMATION' then 1 when 'RAPPEL_ARRIVEE' then 2 "
            + "when 'CODE_ACCES' then 3 else 4 end", (rs, i) -> new Modele(rs.getString("type"), rs.getBoolean("actif"),
            rs.getString("sujet"), rs.getString("corps"), rs.getObject("maj_le", OffsetDateTime.class), rs.getString("maj_par")));
    }

    public Modele modifier(String type, ModeleRequest r, String auteur) {
        if (jdbc.update("update messages_modeles set actif = coalesce(?, actif), sujet = ?, corps = ?, maj_le = now(), maj_par = ? where type = ?",
            r.actif(), r.sujet().strip(), r.corps().strip(), auteur, type) == 0) {
            throw Refus.introuvable("Modèle");
        }
        journal.noter("MODELE_MESSAGE", type, r.sujet());
        return modeles().stream().filter(m -> m.type().equals(type)).findFirst().orElseThrow();
    }

    public List<Envoi> historique() {
        return jdbc.query("""
            select m.*, r.reference from messages_envoyes m join reservations r on r.id = m.reservation_id order by m.envoye_le desc limit 200
            """, (rs, i) -> new Envoi(rs.getLong("id"), rs.getLong("reservation_id"), rs.getString("reference"), rs.getString("type"),
            rs.getString("destinataire"), rs.getString("statut"), rs.getString("detail"), rs.getObject("envoye_le", OffsetDateTime.class)));
    }

    /** Les messages dus, pour chaque type actif (sans doublon : messages_envoyes, un par séjour et par type). */
    static final Map<String, String> DUS = new LinkedHashMap<>();
    static {
        DUS.put("CONFIRMATION", "r.statut = 'CONFIRMEE' and r.fin > now() and r.canal_ical_id is null");
        DUS.put("RAPPEL_ARRIVEE", "r.statut = 'CONFIRMEE' and r.debut between now() + interval '12 hours' and now() + interval '36 hours'");
        DUS.put("CODE_ACCES", "r.statut = 'ARRIVEE' and r.code_acces is not null");
        DUS.put("REMERCIEMENT", "r.statut = 'PARTIE' and r.depart_le > now() - interval '3 days'");
    }

    @Scheduled(initialDelay = 90_000, fixedDelay = 120_000)
    public void envoyerDus() {
        if (!appairage.appaire()) return;
        for (var entree : DUS.entrySet()) {
            String type = entree.getKey();
            var modele = jdbc.query("select actif, sujet, corps from messages_modeles where type = ?",
                rs -> rs.next() && rs.getBoolean(1) ? new String[] {rs.getString(2), rs.getString(3)} : null, type);
            if (modele == null) continue;
            List<Map<String, Object>> dus = jdbc.queryForList("""
                select r.id, r.reference, r.debut, r.fin, r.code_acces, e.nom as etab, t.nom || ' ' || u.numero as unite,
                       trim(coalesce(c.prenom, '') || ' ' || c.nom) as client, c.email
                from reservations r join clients c on c.id = r.client_id join unites u on u.id = r.unite_id
                join types_unite t on t.id = u.type_id join etablissements e on e.id = r.etablissement_id
                where c.email is not null and c.email like '%@%' and""" + " " + entree.getValue() + " " + """
                  and not exists (select 1 from messages_envoyes m where m.reservation_id = r.id and m.type = ?
                      and (m.statut = 'ENVOYE' or m.envoye_le > now() - interval '30 minutes'))
                limit 50
                """, type);
            for (Map<String, Object> r : dus) {
                envoyer(type, modele[0], modele[1], r);
            }
        }
    }

    private void envoyer(String type, String sujet, String corps, Map<String, Object> r) {
        Map<String, String> v = Map.of(
            "{client}", String.valueOf(r.get("client")), "{reference}", String.valueOf(r.get("reference")),
            "{etablissement}", String.valueOf(r.get("etab")), "{unite}", String.valueOf(r.get("unite")),
            "{arrivee}", date(r.get("debut")), "{depart}", date(r.get("fin")),
            "{code}", r.get("code_acces") == null ? "" : String.valueOf(r.get("code_acces")), "{total}", "");
        String s = remplacer(sujet, v);
        String c = remplacer(corps, v);
        Long id = ((Number) r.get("id")).longValue();
        String destinataire = String.valueOf(r.get("email"));
        String statut = "ENVOYE";
        String detail = null;
        try {
            finapp.signe("POST", "/pms/systeme/messages", Map.of("cle", "PMS-MSG-" + id + "-" + type, "destinataire", destinataire,
                "nom", String.valueOf(r.get("client")), "sujet", s, "corps", c), null);
        } catch (FinappException e) {
            statut = "ECHEC";
            detail = e.getMessage();
            log.warn("Message {} de {} non envoyé : {}", type, r.get("reference"), e.getMessage());
        }
        jdbc.update("""
            insert into messages_envoyes (reservation_id, type, destinataire, statut, detail) values (?, ?, ?, ?, ?)
            on conflict (reservation_id, type) do update set destinataire = excluded.destinataire, statut = excluded.statut,
                detail = excluded.detail, envoye_le = now()
            """, id, type, destinataire, statut, detail);
        if ("ENVOYE".equals(statut)) journal.noter("MESSAGE_" + type, String.valueOf(r.get("reference")), destinataire);
    }

    static String remplacer(String texte, Map<String, String> variables) {
        String t = texte;
        for (var e : variables.entrySet()) t = t.replace(e.getKey(), e.getValue());
        return t;
    }

    private static String date(Object o) {
        if (o == null) return "";
        OffsetDateTime d = o instanceof java.sql.Timestamp ts ? ts.toInstant().atZone(Durees.FUSEAU).toOffsetDateTime()
            : o instanceof OffsetDateTime od ? od : null;
        return d == null ? String.valueOf(o) : d.atZoneSameInstant(Durees.FUSEAU).format(DATE);
    }
}
