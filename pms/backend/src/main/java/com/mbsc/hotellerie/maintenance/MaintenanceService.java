package com.mbsc.hotellerie.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.mbsc.hotellerie.audit.Journal;
import com.mbsc.hotellerie.commun.PhotoService;
import com.mbsc.hotellerie.commun.Refus;
import com.mbsc.hotellerie.finapp.FinappClient;
import com.mbsc.hotellerie.finapp.FinappException;
import com.mbsc.hotellerie.menage.MenageService;
import com.mbsc.hotellerie.parametrage.ParametrageService;
import com.mbsc.hotellerie.reservation.Durees;
import com.mbsc.hotellerie.securite.Roles;
import com.mbsc.hotellerie.securite.Session;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.Year;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maintenance : tickets (plomberie, climatisation, électroménager…), priorité, photos, affectation à un technicien.
 * Un ticket peut mettre l'unité hors service jusqu'à une fin prévue : elle est alors bloquée dans le calendrier, la
 * réservation en ligne et les flux iCal, et rendue au service (avec une tâche de ménage) quand le ticket est résolu.
 * Les achats de pièces ou de prestation deviennent des notes de frais M-FINAPP (circuit DFIN, DA, caisse) dont le
 * statut est relu sur le ticket.
 */
@Service
@RequiredArgsConstructor
public class MaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);
    static final Set<String> OUVERTS = Set.of("OUVERT", "EN_COURS", "ATTENTE_PIECES");
    /** Statuts de note M-FINAPP qui ne bougeront plus : inutile de les relire. */
    private static final Set<String> NOTES_FINALES = Set.of("PAYEE", "ANNULEE");

    private final JdbcTemplate jdbc;
    private final Journal journal;
    private final ParametrageService parametrage;
    private final MenageService menage;
    private final PhotoService photos;
    private final FinappClient finapp;

    public record Ticket(Long id, String reference, Long etablissementId, String etablissementNom, Long uniteId, String uniteNumero,
                         String lieu, String categorie, String priorite, String titre, String description, String statut,
                         boolean horsService, OffsetDateTime horsServiceAu, String assigneEmail, String assigneNom, String signalePar,
                         OffsetDateTime signaleLe, OffsetDateTime commenceLe, OffsetDateTime resoluLe, String resoluPar,
                         String resolution, int nbPhotos, int nbAchats) { }

    public record Achat(Long id, String objet, String fournisseur, BigDecimal montant, String devise, String compte, Long noteId,
                        String noteReference, String noteStatut, OffsetDateTime statutLuLe, String creePar, OffsetDateTime creeLe) { }

    public record Conflit(Long reservationId, String reference, String client, OffsetDateTime debut, OffsetDateTime fin, String statut) { }

    public record Detail(Ticket ticket, List<PhotoService.Photo> photos, List<Achat> achats, List<Conflit> conflits) { }

    private static final String SELECT = """
        select t.*, e.nom as etab_nom, u.numero as unite_numero,
               (select count(*) from photos p where p.objet = 'TICKET' and p.objet_id = t.id) as nb_photos,
               (select count(*) from achats_maintenance a where a.ticket_id = t.id) as nb_achats
        from tickets_maintenance t join etablissements e on e.id = t.etablissement_id left join unites u on u.id = t.unite_id
        """;

    private static final RowMapper<Ticket> LIGNE = (rs, i) -> new Ticket(rs.getLong("id"), rs.getString("reference"),
        rs.getLong("etablissement_id"), rs.getString("etab_nom"), (Long) rs.getObject("unite_id"), rs.getString("unite_numero"),
        rs.getString("lieu"), rs.getString("categorie"), rs.getString("priorite"), rs.getString("titre"), rs.getString("description"),
        rs.getString("statut"), rs.getBoolean("hors_service"), rs.getObject("hors_service_au", OffsetDateTime.class),
        rs.getString("assigne_email"), rs.getString("assigne_nom"), rs.getString("signale_par"),
        rs.getObject("signale_le", OffsetDateTime.class), rs.getObject("commence_le", OffsetDateTime.class),
        rs.getObject("resolu_le", OffsetDateTime.class), rs.getString("resolu_par"), rs.getString("resolution"),
        rs.getInt("nb_photos"), rs.getInt("nb_achats"));

    // ── Lecture ─────────────────────────────────────────────────────────────

    /** {@code ouverts} : tickets à traiter seulement ; {@code moi} : ceux affectés à l'utilisateur (ou à personne). */
    public List<Ticket> lister(boolean ouverts, Long etablissementId, Long uniteId, boolean moi, Session session) {
        return jdbc.query(SELECT + """
            where (not ? or t.statut in ('OUVERT', 'EN_COURS', 'ATTENTE_PIECES'))
              and (?::bigint is null or t.etablissement_id = ?) and (?::bigint is null or t.unite_id = ?)
              and (not ? or t.assigne_email is null or lower(t.assigne_email) = lower(?))
            order by case t.statut when 'OUVERT' then 0 when 'EN_COURS' then 1 when 'ATTENTE_PIECES' then 2 else 3 end,
                     case t.priorite when 'URGENTE' then 0 when 'HAUTE' then 1 when 'NORMALE' then 2 else 3 end, t.signale_le desc
            limit 500
            """, LIGNE, ouverts, etablissementId, etablissementId, uniteId, uniteId, moi, session.email());
    }

    public Ticket ticket(Long id) {
        return jdbc.query(SELECT + " where t.id = ?", LIGNE, id).stream().findFirst().orElseThrow(() -> Refus.introuvable("Ticket"));
    }

    /** Détail : photos, achats (statut relu dans M-FINAPP si possible) et réservations touchées par la mise hors service. */
    public Detail detail(Long id, Session session) {
        Ticket t = ticket(id);
        rafraichirAchats(id, session);
        return new Detail(t, photos.photos("TICKET", id), achats(id), conflits(t));
    }

    private List<Achat> achats(Long ticketId) {
        return jdbc.query("select * from achats_maintenance where ticket_id = ? order by cree_le", (rs, i) -> new Achat(rs.getLong("id"),
            rs.getString("objet"), rs.getString("fournisseur"), rs.getBigDecimal("montant"), rs.getString("devise"), rs.getString("compte"),
            (Long) rs.getObject("note_id"), rs.getString("note_reference"), rs.getString("note_statut"),
            rs.getObject("statut_lu_le", OffsetDateTime.class), rs.getString("cree_par"), rs.getObject("cree_le", OffsetDateTime.class)), ticketId);
    }

    private List<Conflit> conflits(Ticket t) {
        if (t.uniteId() == null || !t.horsService() || !OUVERTS.contains(t.statut())) return List.of();
        return jdbc.query("""
            select r.id, r.reference, trim(coalesce(c.prenom, '') || ' ' || c.nom), r.debut, r.fin, r.statut
            from reservations r join clients c on c.id = r.client_id
            where r.unite_id = ? and r.statut in ('OPTION', 'CONFIRMEE', 'ARRIVEE') and r.fin > now()
              and (?::timestamptz is null or r.debut < ?)
            order by r.debut
            """, (rs, i) -> new Conflit(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class),
            rs.getObject(5, OffsetDateTime.class), rs.getString(6)), t.uniteId(), t.horsServiceAu(), t.horsServiceAu());
    }

    // ── Signalement et suivi ────────────────────────────────────────────────

    public record TicketRequest(Long etablissementId, Long uniteId, @Size(max = 120) String lieu,
                                @NotNull @Pattern(regexp = "PLOMBERIE|CLIMATISATION|ELECTRICITE|ELECTROMENAGER|SERRURERIE|MOBILIER|PEINTURE|INTERNET_TV|AUTRE") String categorie,
                                @Pattern(regexp = "BASSE|NORMALE|HAUTE|URGENTE") String priorite, @NotBlank @Size(max = 150) String titre,
                                @Size(max = 4000) String description, Boolean horsService, OffsetDateTime horsServiceAu) { }

    @Transactional
    public Ticket signaler(TicketRequest r, Session session) {
        Long etablissementId = r.etablissementId();
        String numero = null;
        if (r.uniteId() != null) {
            var u = parametrage.unite(r.uniteId());
            etablissementId = u.etablissementId();
            numero = u.numero();
        }
        if (etablissementId == null) throw Refus.invalide("Précisez l'unité ou l'établissement concerné.");
        if (r.uniteId() == null && (r.lieu() == null || r.lieu().isBlank())) throw Refus.invalide("Précisez l'unité ou le lieu (hall, piscine…).");
        boolean hs = Boolean.TRUE.equals(r.horsService()) && r.uniteId() != null;
        if (hs && r.horsServiceAu() != null && !r.horsServiceAu().isAfter(OffsetDateTime.now())) {
            throw Refus.invalide("La fin prévue des travaux doit être à venir.");
        }
        String reference = "MNT-" + Year.now(Durees.FUSEAU) + "-"
            + String.format("%05d", jdbc.queryForObject("select nextval('reference_ticket_seq')", Long.class));
        Long id = jdbc.queryForObject("""
            insert into tickets_maintenance (reference, etablissement_id, unite_id, lieu, categorie, priorite, titre, description,
            hors_service, hors_service_au, signale_par) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
            """, Long.class, reference, etablissementId, r.uniteId(), vide(r.lieu()), r.categorie(),
            r.priorite() == null ? "NORMALE" : r.priorite(), r.titre().strip(), vide(r.description()), hs, hs ? r.horsServiceAu() : null,
            session.nomComplet());
        if (hs) bloquer(id, r.uniteId(), reference, r.titre(), r.horsServiceAu());
        journal.noter("TICKET_SIGNALE", reference, (numero == null ? r.lieu() : "unité " + numero) + " : " + r.titre()
            + (hs ? " (hors service)" : ""));
        return ticket(id);
    }

    @Transactional
    public Ticket modifier(Long id, TicketRequest r, Session session) {
        Ticket t = ouvert(id);
        boolean hs = Boolean.TRUE.equals(r.horsService()) && t.uniteId() != null;
        jdbc.update("""
            update tickets_maintenance set lieu = coalesce(?, lieu), categorie = ?, priorite = coalesce(?, priorite), titre = ?,
            description = ?, hors_service = ?, hors_service_au = ?, maj_le = now() where id = ?
            """, vide(r.lieu()), r.categorie(), r.priorite(), r.titre().strip(), vide(r.description()), hs, hs ? r.horsServiceAu() : null, id);
        if (hs) bloquer(id, t.uniteId(), t.reference(), r.titre(), r.horsServiceAu());
        else if (t.horsService()) liberer(id, t.uniteId());
        journal.noter("TICKET_MODIFIE", t.reference(), r.titre() + (hs ? " (hors service)" : ""));
        return ticket(id);
    }

    public record AffectationRequest(@Size(max = 255) String email, @Size(max = 255) String nom) { }

    /** Affectation à un technicien ; le technicien peut aussi se l'attribuer lui-même. */
    @Transactional
    public Ticket affecter(Long id, AffectationRequest a, Session session) {
        Ticket t = ouvert(id);
        boolean direction = session.a(Roles.DIRECTION, Roles.ADMIN);
        String email = a.email() == null || a.email().isBlank() ? session.email() : a.email().strip();
        if (!direction && !email.equalsIgnoreCase(session.email())) {
            throw new Refus(403, "Seule la direction de l'hébergement affecte un ticket à un autre technicien.");
        }
        String nom = email.equalsIgnoreCase(session.email()) ? session.nomComplet() : vide(a.nom());
        jdbc.update("update tickets_maintenance set assigne_email = ?, assigne_nom = ?, maj_le = now() where id = ?", email, nom, id);
        journal.noter("TICKET_AFFECTE", t.reference(), nom == null ? email : nom);
        return ticket(id);
    }

    public record StatutRequest(@NotNull @Pattern(regexp = "EN_COURS|ATTENTE_PIECES|RESOLU|ANNULE") String statut,
                                @Size(max = 4000) String resolution) { }

    @Transactional
    public Ticket changerStatut(Long id, StatutRequest s, Session session) {
        Ticket t = ouvert(id);
        switch (s.statut()) {
            case "EN_COURS" -> jdbc.update("""
                update tickets_maintenance set statut = 'EN_COURS', commence_le = coalesce(commence_le, now()),
                assigne_email = coalesce(assigne_email, ?), assigne_nom = coalesce(assigne_nom, ?), maj_le = now() where id = ?
                """, session.email(), session.nomComplet(), id);
            case "ATTENTE_PIECES" -> jdbc.update("update tickets_maintenance set statut = 'ATTENTE_PIECES', maj_le = now() where id = ?", id);
            case "RESOLU", "ANNULE" -> {
                if ("RESOLU".equals(s.statut()) && (s.resolution() == null || s.resolution().isBlank())) {
                    throw Refus.invalide("Décrivez l'intervention réalisée.");
                }
                if ("ANNULE".equals(s.statut()) && !session.a(Roles.DIRECTION, Roles.ADMIN) && !t.signalePar().equals(session.nomComplet())) {
                    throw new Refus(403, "Seuls la direction et l'auteur du signalement annulent un ticket.");
                }
                jdbc.update("""
                    update tickets_maintenance set statut = ?, resolu_le = now(), resolu_par = ?, resolution = ?, maj_le = now() where id = ?
                    """, s.statut(), session.nomComplet(), vide(s.resolution()), id);
                if (t.horsService()) liberer(id, t.uniteId());
                if ("RESOLU".equals(s.statut()) && t.uniteId() != null) {
                    // Après une intervention dans une unité : elle repasse par le ménage avant d'accueillir.
                    jdbc.update("update unites set etat_menage = 'A_NETTOYER' where id = ? and etat_menage = 'PROPRE'", t.uniteId());
                    menage.creerTache(t.uniteId(), null, "AUTRE", "Après intervention " + t.reference() + " : " + t.titre());
                }
            }
            default -> throw Refus.invalide("Statut inconnu.");
        }
        journal.noter("TICKET_" + s.statut(), t.reference(), s.resolution());
        return ticket(id);
    }

    // ── Achats (notes de frais M-FINAPP) ───────────────────────────────────

    public record AchatRequest(@NotBlank @Size(max = 80) String cle, @NotBlank @Size(max = 200) String objet,
                               @Size(max = 200) String fournisseur, @NotNull @Positive BigDecimal montant,
                               @NotBlank @Pattern(regexp = "6241|6242|6243|6248|6044|6056|6043") String compte,
                               @Size(max = 2000) String description, @Size(max = 1000) String justificationBudget) { }

    /**
     * Demande d'achat : note de frais créée et soumise au DFIN dans M-FINAPP au nom de l'utilisateur. Rejouée avec la même
     * clé (double clic, réseau coupé), elle ne crée jamais deux notes. Le ticket passe « en attente de pièces ».
     */
    @Transactional(noRollbackFor = FinappException.class)
    public Detail acheter(Long id, AchatRequest a, Session session) {
        Ticket t = ouvert(id);
        if (!jdbc.queryForList("select 1 from achats_maintenance where cle = ?", Integer.class, a.cle()).isEmpty()) {
            return detail(id, session);
        }
        String lieu = t.uniteNumero() != null ? "unité " + t.uniteNumero() : t.lieu();
        Map<String, Object> corps = new HashMap<>();
        corps.put("cle", "PMS-ACHAT-" + a.cle());
        corps.put("objet", a.objet().strip() + " — " + t.reference());
        corps.put("beneficiaire", a.fournisseur() == null || a.fournisseur().isBlank() ? session.nomComplet() : a.fournisseur().strip());
        corps.put("description", "Maintenance " + t.reference() + " (" + t.etablissementNom() + ", " + lieu + ") : " + t.titre()
            + (a.description() == null || a.description().isBlank() ? "" : ". " + a.description().strip()));
        corps.put("montant", a.montant());
        corps.put("compte", a.compte());
        corps.put("justificationBudget", vide(a.justificationBudget()));
        JsonNode note;
        try {
            note = finapp.signe("POST", "/pms/notes-frais", corps, session.jeton());
        } catch (FinappException e) {
            if (e.injoignable()) throw Refus.conflit("M-FINAPP est injoignable : la demande d'achat n'est pas partie. Réessayez dans un instant.");
            throw Refus.invalide("M-FINAPP a refusé la demande d'achat : " + e.getMessage());
        }
        jdbc.update("""
            insert into achats_maintenance (ticket_id, cle, objet, fournisseur, montant, devise, compte, note_id, note_reference, note_statut,
            statut_lu_le, cree_par) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?)
            """, id, a.cle(), a.objet().strip(), vide(a.fournisseur()), a.montant(), note.path("devise").asText("USD"), a.compte(),
            note.path("noteId").asLong(), note.path("reference").asText(null), note.path("statut").asText(null), session.email());
        jdbc.update("update tickets_maintenance set statut = 'ATTENTE_PIECES', maj_le = now() where id = ? and statut <> 'ATTENTE_PIECES'", id);
        journal.noter("TICKET_ACHAT", t.reference(), "note " + note.path("reference").asText() + ", " + a.montant() + " au compte " + a.compte());
        return detail(id, session);
    }

    /** Relit le statut des notes encore en circuit ; M-FINAPP injoignable : on garde le dernier statut connu. */
    private void rafraichirAchats(Long ticketId, Session session) {
        for (Achat a : achats(ticketId)) {
            if (a.noteId() == null || (a.noteStatut() != null && NOTES_FINALES.contains(a.noteStatut()))) continue;
            try {
                JsonNode n = finapp.signe("GET", "/pms/notes-frais/" + a.noteId(), null, session.jeton());
                jdbc.update("update achats_maintenance set note_statut = ?, statut_lu_le = now() where id = ?", n.path("statut").asText(null), a.id());
            } catch (FinappException e) {
                log.debug("Statut de la note {} non relu : {}", a.noteReference(), e.getMessage());
            }
        }
    }

    // ── Outils ──────────────────────────────────────────────────────────────

    private Ticket ouvert(Long id) {
        Ticket t = ticket(id);
        if (!OUVERTS.contains(t.statut())) throw Refus.conflit("Le ticket " + t.reference() + " est clos.");
        return t;
    }

    /** Met l'unité hors service pour ce ticket, de maintenant à la fin prévue (sans fin : jusqu'à la résolution). */
    private void bloquer(Long ticketId, Long uniteId, String reference, String titre, OffsetDateTime au) {
        var u = parametrage.unite(uniteId);
        OffsetDateTime du = u.horsService() && u.horsServiceDu() != null ? u.horsServiceDu() : OffsetDateTime.now();
        parametrage.horsService(uniteId, true, reference + " : " + titre.strip(), du, au, ticketId);
    }

    /** Rend l'unité au service si ce ticket la bloquait ; un autre ticket ouvert « hors service » reprend alors la main. */
    private void liberer(Long ticketId, Long uniteId) {
        var u = parametrage.unite(uniteId);
        if (!u.horsService() || (u.ticketHorsServiceId() != null && !u.ticketHorsServiceId().equals(ticketId))) return;
        var autre = jdbc.query("""
            select id, reference, titre, hors_service_au from tickets_maintenance
            where unite_id = ? and id <> ? and hors_service and statut in ('OUVERT', 'EN_COURS', 'ATTENTE_PIECES') order by signale_le limit 1
            """, (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class)}, uniteId, ticketId);
        if (!autre.isEmpty()) {
            Object[] a = autre.get(0);
            parametrage.horsService(uniteId, true, a[1] + " : " + a[2], u.horsServiceDu(), (OffsetDateTime) a[3], (Long) a[0]);
        } else {
            parametrage.horsService(uniteId, false, null, null, null, null);
        }
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
