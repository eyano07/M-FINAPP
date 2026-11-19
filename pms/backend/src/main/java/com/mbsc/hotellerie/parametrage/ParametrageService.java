package com.mbsc.hotellerie.parametrage;

import com.mbsc.hotellerie.audit.Journal;
import com.mbsc.hotellerie.commun.Refus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

/** Établissements (hôtel, motel, résidence d'appartements), types d'unité, unités et tarifs. */
@Service
@RequiredArgsConstructor
public class ParametrageService {

    private final JdbcTemplate jdbc;
    private final Journal journal;

    // ── Établissements ──────────────────────────────────────────────────────

    public record Etablissement(Long id, String nom, String type, String adresse, String telephone, LocalTime heureArrivee,
                                LocalTime heureDepart, boolean actif, int unites) { }

    public record EtablissementRequest(@NotBlank @Size(max = 150) String nom,
                                       @NotNull @Pattern(regexp = "HOTEL|MOTEL|APPARTEMENTS|MIXTE") String type,
                                       @Size(max = 255) String adresse, @Size(max = 40) String telephone,
                                       LocalTime heureArrivee, LocalTime heureDepart, Boolean actif) { }

    public List<Etablissement> etablissements() {
        return jdbc.query("""
            select e.*, (select count(*) from unites u where u.etablissement_id = e.id and u.actif) as nb
            from etablissements e order by e.actif desc, e.nom
            """, (rs, i) -> new Etablissement(rs.getLong("id"), rs.getString("nom"), rs.getString("type"),
            rs.getString("adresse"), rs.getString("telephone"), rs.getTime("heure_arrivee").toLocalTime(),
            rs.getTime("heure_depart").toLocalTime(), rs.getBoolean("actif"), rs.getInt("nb")));
    }

    @Transactional
    public Etablissement enregistrerEtablissement(Long id, EtablissementRequest r) {
        LocalTime arrivee = r.heureArrivee() == null ? LocalTime.of(14, 0) : r.heureArrivee();
        LocalTime depart = r.heureDepart() == null ? LocalTime.of(12, 0) : r.heureDepart();
        boolean actif = r.actif() == null || r.actif();
        if (id == null) {
            id = jdbc.queryForObject("""
                insert into etablissements (nom, type, adresse, telephone, heure_arrivee, heure_depart, actif)
                values (?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, r.nom().strip(), r.type(), vide(r.adresse()), vide(r.telephone()), arrivee, depart, actif);
            journal.noter("ETABLISSEMENT_CREE", r.nom(), r.type());
        } else {
            int n = jdbc.update("""
                update etablissements set nom = ?, type = ?, adresse = ?, telephone = ?, heure_arrivee = ?, heure_depart = ?, actif = ?
                where id = ?
                """, r.nom().strip(), r.type(), vide(r.adresse()), vide(r.telephone()), arrivee, depart, actif, id);
            if (n == 0) throw Refus.introuvable("Établissement");
            journal.noter("ETABLISSEMENT_MODIFIE", r.nom(), r.type());
        }
        Long cle = id;
        return etablissements().stream().filter(e -> e.id().equals(cle)).findFirst().orElseThrow();
    }

    // ── Types d'unité ───────────────────────────────────────────────────────

    public record TypeUnite(Long id, Long etablissementId, String nom, int capacite, String description, boolean actif) { }

    public record TypeRequest(@NotNull Long etablissementId, @NotBlank @Size(max = 100) String nom, @Positive Integer capacite,
                              @Size(max = 500) String description, Boolean actif) { }

    public List<TypeUnite> types(Long etablissementId) {
        return jdbc.query("""
            select * from types_unite where (?::bigint is null or etablissement_id = ?) order by etablissement_id, nom
            """, (rs, i) -> new TypeUnite(rs.getLong("id"), rs.getLong("etablissement_id"), rs.getString("nom"),
            rs.getInt("capacite"), rs.getString("description"), rs.getBoolean("actif")), etablissementId, etablissementId);
    }

    @Transactional
    public TypeUnite enregistrerType(Long id, TypeRequest r) {
        int capacite = r.capacite() == null ? 2 : r.capacite();
        boolean actif = r.actif() == null || r.actif();
        if (id == null) {
            id = jdbc.queryForObject("""
                insert into types_unite (etablissement_id, nom, capacite, description, actif) values (?, ?, ?, ?, ?) returning id
                """, Long.class, r.etablissementId(), r.nom().strip(), capacite, vide(r.description()), actif);
        } else if (jdbc.update("update types_unite set nom = ?, capacite = ?, description = ?, actif = ? where id = ?",
            r.nom().strip(), capacite, vide(r.description()), actif, id) == 0) {
            throw Refus.introuvable("Type d'unité");
        }
        journal.noter("TYPE_UNITE", r.nom(), "capacité " + capacite);
        Long cle = id;
        return types(null).stream().filter(t -> t.id().equals(cle)).findFirst().orElseThrow();
    }

    // ── Unités ──────────────────────────────────────────────────────────────

    /**
     * {@code horsServiceDu}/{@code horsServiceAu} : période de la mise hors service (fin prévue des travaux ; sans fin,
     * l'unité est bloquée sans limite). {@code ticketHorsServiceId} : ticket de maintenance qui la bloque.
     */
    public record Unite(Long id, Long etablissementId, String etablissementNom, Long typeId, String typeNom, String numero,
                        String etage, Integer capacite, String equipements, String etatMenage, boolean horsService,
                        String motifHorsService, String notes, int ordre, boolean actif, OffsetDateTime horsServiceDu,
                        OffsetDateTime horsServiceAu, Long ticketHorsServiceId) {

        /** Vrai si la mise hors service touche la période [debut, fin). */
        public boolean bloquee(OffsetDateTime debut, OffsetDateTime fin) {
            if (!horsService) return false;
            boolean apresDebut = horsServiceDu == null || fin == null || fin.isAfter(horsServiceDu);
            boolean avantFin = horsServiceAu == null || debut == null || debut.isBefore(horsServiceAu);
            return apresDebut && avantFin;
        }
    }

    public record UniteRequest(@NotNull Long typeId, @NotBlank @Size(max = 30) String numero, @Size(max = 20) String etage,
                               @Positive Integer capacite, @Size(max = 500) String equipements, @Size(max = 500) String notes,
                               Integer ordre, Boolean actif) { }

    public List<Unite> unites(Long etablissementId) {
        return jdbc.query("""
            select u.*, e.nom as etab_nom, t.nom as type_nom from unites u
            join etablissements e on e.id = u.etablissement_id join types_unite t on t.id = u.type_id
            where (?::bigint is null or u.etablissement_id = ?)
            order by e.nom, u.ordre, u.numero
            """, (rs, i) -> new Unite(rs.getLong("id"), rs.getLong("etablissement_id"), rs.getString("etab_nom"),
            rs.getLong("type_id"), rs.getString("type_nom"), rs.getString("numero"), rs.getString("etage"),
            (Integer) rs.getObject("capacite"), rs.getString("equipements"), rs.getString("etat_menage"),
            rs.getBoolean("hors_service"), rs.getString("motif_hors_service"), rs.getString("notes"), rs.getInt("ordre"),
            rs.getBoolean("actif"), rs.getObject("hors_service_du", OffsetDateTime.class),
            rs.getObject("hors_service_au", OffsetDateTime.class), (Long) rs.getObject("ticket_hors_service_id")),
            etablissementId, etablissementId);
    }

    public Unite unite(Long id) {
        return unites(null).stream().filter(u -> u.id().equals(id)).findFirst().orElseThrow(() -> Refus.introuvable("Unité"));
    }

    @Transactional
    public Unite enregistrerUnite(Long id, UniteRequest r) {
        Long etablissementId = jdbc.queryForList("select etablissement_id from types_unite where id = ?", Long.class, r.typeId())
            .stream().findFirst().orElseThrow(() -> Refus.introuvable("Type d'unité"));
        boolean actif = r.actif() == null || r.actif();
        int ordre = r.ordre() == null ? 0 : r.ordre();
        if (id == null) {
            id = jdbc.queryForObject("""
                insert into unites (etablissement_id, type_id, numero, etage, capacite, equipements, notes, ordre, actif)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, etablissementId, r.typeId(), r.numero().strip(), vide(r.etage()), r.capacite(),
                vide(r.equipements()), vide(r.notes()), ordre, actif);
            journal.noter("UNITE_CREEE", r.numero(), null);
        } else if (jdbc.update("""
            update unites set type_id = ?, etablissement_id = ?, numero = ?, etage = ?, capacite = ?, equipements = ?, notes = ?,
            ordre = ?, actif = ? where id = ?
            """, r.typeId(), etablissementId, r.numero().strip(), vide(r.etage()), r.capacite(), vide(r.equipements()),
            vide(r.notes()), ordre, actif, id) == 0) {
            throw Refus.introuvable("Unité");
        }
        return unite(id);
    }

    /** Mise hors service manuelle (sans fin prévue) ou remise en service. */
    @Transactional
    public Unite horsService(Long id, boolean horsService, String motif) {
        return horsService(id, horsService, motif, null, null, null);
    }

    /**
     * Mise hors service datée : du … au … (fin prévue ; null : sans limite), éventuellement par un ticket de maintenance.
     * Les réservations qui commencent après la fin prévue restent possibles.
     */
    @Transactional
    public Unite horsService(Long id, boolean horsService, String motif, OffsetDateTime du, OffsetDateTime au, Long ticketId) {
        if (horsService && du != null && au != null && !au.isAfter(du)) throw Refus.invalide("La fin prévue doit suivre le début.");
        if (jdbc.update("""
            update unites set hors_service = ?, motif_hors_service = ?, hors_service_du = ?, hors_service_au = ?,
            ticket_hors_service_id = ? where id = ?
            """, horsService, horsService ? vide(motif) : null, horsService ? (du == null ? OffsetDateTime.now() : du) : null,
            horsService ? au : null, horsService ? ticketId : null, id) == 0) {
            throw Refus.introuvable("Unité");
        }
        Unite u = unite(id);
        journal.noter(horsService ? "UNITE_HORS_SERVICE" : "UNITE_EN_SERVICE", u.numero(), motif);
        return u;
    }

    // ── Tarifs ──────────────────────────────────────────────────────────────

    /**
     * {@code CRENEAU} (motels) : forfait au prix total pour {@code dureeHeures} heures (« Sieste 3 h »), proposé d'abord si
     * l'arrivée tombe dans la plage {@code plageDebut}–{@code plageFin} (plage qui peut passer minuit ; vide : toujours).
     */
    public record Tarif(Long id, Long typeId, String typeNom, Long etablissementId, String uniteDuree, BigDecimal prix,
                        String libelle, LocalDate debutSaison, LocalDate finSaison, boolean actif, Integer dureeHeures,
                        LocalTime plageDebut, LocalTime plageFin) {

        public boolean couvre(LocalTime heure) {
            if (plageDebut == null || plageFin == null) return true;
            return plageDebut.isBefore(plageFin) ? !heure.isBefore(plageDebut) && heure.isBefore(plageFin)
                : !heure.isBefore(plageDebut) || heure.isBefore(plageFin);
        }
    }

    public record TarifRequest(@NotNull Long typeId, @NotNull @Pattern(regexp = "HEURE|NUIT|SEMAINE|MOIS|CRENEAU") String uniteDuree,
                               @NotNull @PositiveOrZero BigDecimal prix, @Size(max = 100) String libelle, LocalDate debutSaison,
                               LocalDate finSaison, Boolean actif, @Min(1) @Max(24) Integer dureeHeures, LocalTime plageDebut,
                               LocalTime plageFin) { }

    public List<Tarif> tarifs(Long etablissementId) {
        return jdbc.query("""
            select ta.*, t.nom as type_nom, t.etablissement_id from tarifs ta join types_unite t on t.id = ta.type_id
            where (?::bigint is null or t.etablissement_id = ?)
            order by t.etablissement_id, t.nom, ta.unite_duree, ta.debut_saison nulls first
            """, (rs, i) -> new Tarif(rs.getLong("id"), rs.getLong("type_id"), rs.getString("type_nom"),
            rs.getLong("etablissement_id"), rs.getString("unite_duree"), rs.getBigDecimal("prix"), rs.getString("libelle"),
            date(rs.getDate("debut_saison")), date(rs.getDate("fin_saison")), rs.getBoolean("actif"),
            (Integer) rs.getObject("duree_heures"), heure(rs.getTime("plage_debut")), heure(rs.getTime("plage_fin"))),
            etablissementId, etablissementId);
    }

    @Transactional
    public Tarif enregistrerTarif(Long id, TarifRequest r) {
        boolean actif = r.actif() == null || r.actif();
        boolean creneau = "CRENEAU".equals(r.uniteDuree());
        if (creneau && r.dureeHeures() == null) throw Refus.invalide("Forfait par créneau : précisez sa durée en heures.");
        if (creneau && vide(r.libelle()) == null) throw Refus.invalide("Forfait par créneau : donnez-lui un nom (« Sieste 3 h »…).");
        Integer heures = creneau ? r.dureeHeures() : null;
        LocalTime pd = creneau ? r.plageDebut() : null;
        LocalTime pf = creneau ? r.plageFin() : null;
        if (id == null) {
            id = jdbc.queryForObject("""
                insert into tarifs (type_id, unite_duree, prix, libelle, debut_saison, fin_saison, actif, duree_heures, plage_debut, plage_fin)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, r.typeId(), r.uniteDuree(), r.prix(), vide(r.libelle()), r.debutSaison(), r.finSaison(), actif,
                heures, pd, pf);
        } else if (jdbc.update("""
            update tarifs set type_id = ?, unite_duree = ?, prix = ?, libelle = ?, debut_saison = ?, fin_saison = ?, actif = ?,
            duree_heures = ?, plage_debut = ?, plage_fin = ? where id = ?
            """, r.typeId(), r.uniteDuree(), r.prix(), vide(r.libelle()), r.debutSaison(), r.finSaison(), actif, heures, pd, pf,
            id) == 0) {
            throw Refus.introuvable("Tarif");
        }
        journal.noter("TARIF", r.uniteDuree() + " " + r.prix(), r.libelle());
        Long cle = id;
        return tarifs(null).stream().filter(t -> t.id().equals(cle)).findFirst().orElseThrow();
    }

    /**
     * Prix proposé pour une unité, une durée et une date d'arrivée : le tarif de saison qui couvre la date, sinon le tarif
     * sans saison ; null si aucun tarif n'est défini (la réception saisit alors le prix).
     */
    public BigDecimal prixPropose(Long uniteId, String uniteDuree, LocalDate date) {
        return jdbc.query("""
            select ta.prix from tarifs ta join unites u on u.type_id = ta.type_id
            where u.id = ? and ta.unite_duree = ? and ta.actif
              and (ta.debut_saison is null or ta.debut_saison <= ?) and (ta.fin_saison is null or ta.fin_saison >= ?)
            order by (ta.debut_saison is null) asc, ta.id desc limit 1
            """, rs -> rs.next() ? rs.getBigDecimal(1) : null, uniteId, uniteDuree, date, date);
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static LocalDate date(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    private static LocalTime heure(java.sql.Time t) {
        return t == null ? null : t.toLocalTime();
    }

    /**
     * Forfaits par créneau d'une unité pour une arrivée : d'abord ceux dont la plage couvre l'heure d'arrivée (« Nuit motel »
     * à 22 h), puis ceux sans plage (valables à toute heure), enfin ceux hors de leur plage ; à rang égal, les plus courts.
     */
    public List<Tarif> forfaits(Long uniteId, OffsetDateTime arrivee) {
        Long typeId = jdbc.queryForList("select type_id from unites where id = ?", Long.class, uniteId).stream().findFirst()
            .orElseThrow(() -> Refus.introuvable("Unité"));
        var local = arrivee.atZoneSameInstant(com.mbsc.hotellerie.reservation.Durees.FUSEAU);
        LocalDate jour = local.toLocalDate();
        LocalTime h = local.toLocalTime();
        return tarifs(null).stream()
            .filter(t -> t.typeId().equals(typeId) && t.actif() && "CRENEAU".equals(t.uniteDuree()))
            .filter(t -> (t.debutSaison() == null || !jour.isBefore(t.debutSaison())) && (t.finSaison() == null || !jour.isAfter(t.finSaison())))
            .sorted(java.util.Comparator.comparingInt((Tarif t) -> t.plageDebut() == null || t.plageFin() == null ? 1 : t.couvre(h) ? 0 : 2)
                .thenComparing(Tarif::dureeHeures))
            .toList();
    }

    public Tarif tarif(Long id) {
        return tarifs(null).stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow(() -> Refus.introuvable("Tarif"));
    }
}
