package com.mbsc.hotellerie.publique;

import com.mbsc.hotellerie.audit.Journal;
import com.mbsc.hotellerie.commun.Refus;
import com.mbsc.hotellerie.reservation.Durees;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Moteur de réservation direct (phase 2) : page publique, sans commission. Le visiteur voit les types d'unité libres
 * aux dates choisies et leur prix (tarif à la nuit), puis envoie une demande : réservation en OPTION (l'unité est tenue),
 * que la réception confirme à réception de l'acompte. Une demande non confirmée sous 48 h est libérée. Aucun numéro
 * d'unité ni aucune donnée d'un autre client n'est exposé ; cinq demandes par heure et par adresse au plus.
 */
@Service
public class PubliqueService {

    private static final int DEMANDES_PAR_HEURE = 5;
    private static final int NUITS_MAX = 60;

    private final JdbcTemplate jdbc;
    private final Journal journal;
    private final Map<String, List<Long>> demandesParAdresse = new ConcurrentHashMap<>();

    public PubliqueService(JdbcTemplate jdbc, Journal journal) {
        this.jdbc = jdbc;
        this.journal = journal;
    }

    public record Etablissement(Long id, String nom, String type, String adresse, String telephone, LocalTime heureArrivee,
                                LocalTime heureDepart) { }

    public record Offre(Long typeId, String type, String description, int capacite, BigDecimal prixNuit, long nuits,
                        BigDecimal totalHt, int disponibles) { }

    public List<Etablissement> etablissements() {
        return jdbc.query("select * from etablissements where actif order by nom", (rs, i) -> new Etablissement(rs.getLong("id"),
            rs.getString("nom"), rs.getString("type"), rs.getString("adresse"), rs.getString("telephone"),
            rs.getTime("heure_arrivee").toLocalTime(), rs.getTime("heure_depart").toLocalTime()));
    }

    public List<Offre> disponibilites(Long etablissementId, LocalDate arrivee, LocalDate depart, int personnes) {
        verifierDates(arrivee, depart);
        OffsetDateTime[] p = periode(etablissementId, arrivee, depart);
        long nuits = ChronoUnit.DAYS.between(arrivee, depart);
        return jdbc.query("""
            select t.id, t.nom, t.description, t.capacite, count(u.id) as libres,
                   (select ta.prix from tarifs ta where ta.type_id = t.id and ta.unite_duree = 'NUIT' and ta.actif
                      and (ta.debut_saison is null or ta.debut_saison <= ?) and (ta.fin_saison is null or ta.fin_saison >= ?)
                    order by (ta.debut_saison is null) asc, ta.id desc limit 1) as prix
            from types_unite t join unites u on u.type_id = t.id and u.actif
            where t.etablissement_id = ? and t.actif and coalesce(u.capacite, t.capacite) >= ?
              and not (u.hors_service and tstzrange(coalesce(u.hors_service_du, '-infinity'), coalesce(u.hors_service_au, 'infinity'), '[)') && tstzrange(?, ?, '[)'))
              and not exists (select 1 from reservations r where r.unite_id = u.id and r.statut in ('OPTION', 'CONFIRMEE', 'ARRIVEE')
                  and tstzrange(r.debut, r.fin, '[)') && tstzrange(?, ?, '[)'))
            group by t.id, t.nom, t.description, t.capacite order by prix nulls last, t.nom
            """, (rs, i) -> {
            BigDecimal prix = rs.getBigDecimal("prix");
            return new Offre(rs.getLong("id"), rs.getString("nom"), rs.getString("description"), rs.getInt("capacite"), prix, nuits,
                prix == null ? null : Durees.ht(prix, BigDecimal.valueOf(nuits)), rs.getInt("libres"));
        }, arrivee, arrivee, etablissementId, personnes, p[0], p[1], p[0], p[1]).stream().filter(o -> o.prixNuit() != null).toList();
    }

    public record Demande(@NotNull Long etablissementId, @NotNull Long typeId, @NotNull LocalDate arrivee, @NotNull LocalDate depart,
                          @Min(1) @Max(20) int adultes, @Min(0) @Max(20) int enfants, @NotBlank @Size(max = 120) String nom,
                          @Size(max = 120) String prenom, @NotBlank @Size(max = 40) String telephone,
                          @Email @Size(max = 150) String email, @Size(max = 1000) String message,
                          /** Champ caché : un robot le remplit, un humain non. */ String site) { }

    public record Accuse(String reference, String message) { }

    @Transactional
    public Accuse demander(Demande d, String adresse) {
        if (d.site() != null && !d.site().isBlank()) {
            return new Accuse("—", "Demande reçue.");
        }
        limiter(adresse);
        verifierDates(d.arrivee(), d.depart());
        OffsetDateTime[] p = periode(d.etablissementId(), d.arrivee(), d.depart());
        var offre = disponibilites(d.etablissementId(), d.arrivee(), d.depart(), d.adultes() + d.enfants()).stream()
            .filter(o -> o.typeId().equals(d.typeId())).findFirst()
            .orElseThrow(() -> Refus.conflit("Plus de disponibilité pour ce type d'unité à ces dates : choisissez-en un autre."));
        Long uniteId = jdbc.queryForObject("""
            select u.id from unites u join types_unite t on t.id = u.type_id
            where u.type_id = ? and u.actif and coalesce(u.capacite, t.capacite) >= ?
              and not (u.hors_service and tstzrange(coalesce(u.hors_service_du, '-infinity'), coalesce(u.hors_service_au, 'infinity'), '[)') && tstzrange(?, ?, '[)'))
              and not exists (select 1 from reservations r where r.unite_id = u.id and r.statut in ('OPTION', 'CONFIRMEE', 'ARRIVEE')
                  and tstzrange(r.debut, r.fin, '[)') && tstzrange(?, ?, '[)'))
            order by u.ordre, u.numero limit 1
            """, Long.class, d.typeId(), d.adultes() + d.enfants(), p[0], p[1], p[0], p[1]);
        Long clientId = jdbc.queryForObject("""
            insert into clients (nom, prenom, telephone, email, notes) values (?, ?, ?, ?, 'Créé par une demande en ligne.') returning id
            """, Long.class, d.nom().strip(), vide(d.prenom()), d.telephone().strip(), vide(d.email()));
        String reference = "RES-" + Year.now(Durees.FUSEAU) + "-"
            + String.format("%06d", jdbc.queryForObject("select nextval('reference_reservation_seq')", Long.class));
        jdbc.update("""
            insert into reservations (reference, etablissement_id, unite_id, client_id, debut, fin, unite_duree, prix_unitaire, adultes,
            enfants, canal, statut, notes, demande_publique, cree_par) values (?, ?, ?, ?, ?, ?, 'NUIT', ?, ?, ?, 'DIRECT', 'OPTION', ?, true, 'réservation en ligne')
            """, reference, d.etablissementId(), uniteId, clientId, p[0], p[1], offre.prixNuit(), d.adultes(), d.enfants(),
            "Demande en ligne" + (d.message() == null || d.message().isBlank() ? "." : " : " + d.message().strip()));
        journal.noter("DEMANDE_EN_LIGNE", reference, offre.type() + ", " + d.arrivee() + " → " + d.depart() + ", " + d.nom());
        return new Accuse(reference, "Merci ! Votre demande " + reference + " est enregistrée. La réception vous contacte pour la "
            + "confirmer (un acompte peut être demandé). Sans confirmation sous 48 heures, elle est libérée.");
    }

    /** Demandes en ligne non confirmées sous 48 h : libérées (l'unité redevient disponible). */
    @Scheduled(initialDelay = 120_000, fixedDelay = 3_600_000)
    public void libererDemandesExpirees() {
        int n = jdbc.update("""
            update reservations set statut = 'ANNULEE', motif_annulation = 'Demande en ligne non confirmée sous 48 heures', maj_le = now()
            where demande_publique and statut = 'OPTION' and cree_le < now() - interval '48 hours'
              and not exists (select 1 from fonds f where f.reservation_id = reservations.id)
            """);
        if (n > 0) journal.noter("DEMANDES_EXPIREES", n + " demande(s)", "non confirmées sous 48 h, libérées");
    }

    private void limiter(String adresse) {
        long maintenant = System.currentTimeMillis();
        List<Long> liste = demandesParAdresse.computeIfAbsent(adresse == null ? "?" : adresse, k -> new java.util.ArrayList<>());
        synchronized (liste) {
            liste.removeIf(t -> t < maintenant - 3_600_000);
            if (liste.size() >= DEMANDES_PAR_HEURE) throw new Refus(429, "Trop de demandes : réessayez dans une heure ou appelez la réception.");
            liste.add(maintenant);
        }
    }

    private static void verifierDates(LocalDate arrivee, LocalDate depart) {
        if (arrivee == null || depart == null) throw Refus.invalide("Choisissez vos dates d'arrivée et de départ.");
        if (arrivee.isBefore(LocalDate.now(Durees.FUSEAU))) throw Refus.invalide("La date d'arrivée est passée.");
        if (!depart.isAfter(arrivee)) throw Refus.invalide("Le départ doit suivre l'arrivée d'au moins une nuit.");
        if (ChronoUnit.DAYS.between(arrivee, depart) > NUITS_MAX) throw Refus.invalide("Séjour de " + NUITS_MAX + " nuits au plus en ligne : contactez la réception.");
    }

    private OffsetDateTime[] periode(Long etablissementId, LocalDate arrivee, LocalDate depart) {
        LocalTime[] h = jdbc.query("select heure_arrivee, heure_depart from etablissements where id = ? and actif",
            rs -> rs.next() ? new LocalTime[] {rs.getTime(1).toLocalTime(), rs.getTime(2).toLocalTime()} : null, etablissementId);
        if (h == null) throw Refus.introuvable("Établissement");
        return new OffsetDateTime[] {arrivee.atTime(h[0]).atZone(Durees.FUSEAU).toOffsetDateTime(),
            depart.atTime(h[1]).atZone(Durees.FUSEAU).toOffsetDateTime()};
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
