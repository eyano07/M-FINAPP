package com.mbsc.hotellerie.motel;

import com.mbsc.hotellerie.audit.Journal;
import com.mbsc.hotellerie.commun.Refus;
import com.mbsc.hotellerie.finapp.FinappException;
import com.mbsc.hotellerie.parametrage.ParametrageService;
import com.mbsc.hotellerie.reservation.Durees;
import com.mbsc.hotellerie.reservation.ReservationService;
import com.mbsc.hotellerie.securite.Session;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Motels : arrivée express (réservation, arrivée, véhicule et paiement d'avance en un geste), forfaits par créneau,
 * places de parking et véhicules des clients. Une place n'accueille qu'un véhicule présent à la fois ; le départ du
 * client libère sa place.
 */
@Service
@RequiredArgsConstructor
public class MotelService {

    private static final String CLIENT_DE_PASSAGE = "Client de passage";

    private final JdbcTemplate jdbc;
    private final Journal journal;
    private final ParametrageService parametrage;
    private final ReservationService reservations;
    private final PlatformTransactionManager transactions;

    // ── Places de parking ──────────────────────────────────────────────────

    public record Place(Long id, Long etablissementId, String etablissementNom, String numero, boolean couverte, Long uniteId,
                        String uniteNumero, String notes, boolean actif, Long vehiculeId, String immatriculation, String reservationReference) { }

    public List<Place> places(Long etablissementId) {
        return jdbc.query("""
            select p.*, e.nom as etab_nom, u.numero as unite_numero, v.id as vehicule_id, v.immatriculation, r.reference
            from places_parking p join etablissements e on e.id = p.etablissement_id left join unites u on u.id = p.unite_id
            left join vehicules v on v.place_id = p.id and v.sortie_le is null left join reservations r on r.id = v.reservation_id
            where (?::bigint is null or p.etablissement_id = ?) order by e.nom, p.numero
            """, (rs, i) -> new Place(rs.getLong("id"), rs.getLong("etablissement_id"), rs.getString("etab_nom"), rs.getString("numero"),
            rs.getBoolean("couverte"), (Long) rs.getObject("unite_id"), rs.getString("unite_numero"), rs.getString("notes"),
            rs.getBoolean("actif"), (Long) rs.getObject("vehicule_id"), rs.getString("immatriculation"), rs.getString("reference")),
            etablissementId, etablissementId);
    }

    public record PlaceRequest(@NotNull Long etablissementId, @NotBlank @Size(max = 20) String numero, Boolean couverte, Long uniteId,
                               @Size(max = 255) String notes, Boolean actif) { }

    @Transactional
    public Place enregistrerPlace(Long id, PlaceRequest r) {
        if (r.uniteId() != null && !parametrage.unite(r.uniteId()).etablissementId().equals(r.etablissementId())) {
            throw Refus.invalide("Le garage attitré doit appartenir au même établissement.");
        }
        boolean actif = r.actif() == null || r.actif();
        if (id == null) {
            id = jdbc.queryForObject("""
                insert into places_parking (etablissement_id, numero, couverte, unite_id, notes, actif) values (?, ?, ?, ?, ?, ?) returning id
                """, Long.class, r.etablissementId(), r.numero().strip(), Boolean.TRUE.equals(r.couverte()), r.uniteId(), vide(r.notes()), actif);
        } else if (jdbc.update("""
            update places_parking set etablissement_id = ?, numero = ?, couverte = ?, unite_id = ?, notes = ?, actif = ? where id = ?
            """, r.etablissementId(), r.numero().strip(), Boolean.TRUE.equals(r.couverte()), r.uniteId(), vide(r.notes()), actif, id) == 0) {
            throw Refus.introuvable("Place");
        }
        journal.noter("PLACE_PARKING", r.numero(), actif ? "active" : "désactivée");
        Long cle = id;
        return places(null).stream().filter(p -> p.id().equals(cle)).findFirst().orElseThrow();
    }

    // ── Véhicules ───────────────────────────────────────────────────────────

    public record Vehicule(Long id, Long reservationId, String reservationReference, String client, String uniteNumero,
                           String immatriculation, String marque, String couleur, Long placeId, String placeNumero,
                           OffsetDateTime entreeLe, OffsetDateTime sortieLe) { }

    /** Véhicules présents (ou ceux d'un séjour). */
    public List<Vehicule> vehicules(Long etablissementId, Long reservationId) {
        return jdbc.query("""
            select v.*, r.reference, trim(coalesce(c.prenom, '') || ' ' || c.nom) as client, u.numero as unite_numero, p.numero as place_numero
            from vehicules v join reservations r on r.id = v.reservation_id join clients c on c.id = r.client_id
            join unites u on u.id = r.unite_id left join places_parking p on p.id = v.place_id
            where (?::bigint is not null and v.reservation_id = ?) or (?::bigint is null and v.sortie_le is null
              and (?::bigint is null or r.etablissement_id = ?))
            order by v.entree_le desc
            """, (rs, i) -> new Vehicule(rs.getLong("id"), rs.getLong("reservation_id"), rs.getString("reference"), rs.getString("client"),
            rs.getString("unite_numero"), rs.getString("immatriculation"), rs.getString("marque"), rs.getString("couleur"),
            (Long) rs.getObject("place_id"), rs.getString("place_numero"), rs.getObject("entree_le", OffsetDateTime.class),
            rs.getObject("sortie_le", OffsetDateTime.class)), reservationId, reservationId, reservationId, etablissementId, etablissementId);
    }

    public record VehiculeRequest(@NotBlank @Size(max = 20) String immatriculation, @Size(max = 60) String marque,
                                  @Size(max = 40) String couleur, Long placeId) { }

    @Transactional
    public Vehicule ajouterVehicule(Long reservationId, VehiculeRequest v, Session session) {
        var r = reservations.reservation(reservationId);
        if (!java.util.Set.of("OPTION", "CONFIRMEE", "ARRIVEE").contains(r.statut())) throw Refus.conflit("Ce séjour est terminé.");
        Long placeId = v.placeId();
        if (placeId != null) {
            var p = places(r.etablissementId()).stream().filter(x -> x.id().equals(v.placeId())).findFirst()
                .orElseThrow(() -> Refus.invalide("Cette place n'appartient pas à l'établissement du séjour."));
            if (!p.actif()) throw Refus.conflit("La place " + p.numero() + " est désactivée.");
            if (p.vehiculeId() != null) throw Refus.conflit("La place " + p.numero() + " est déjà occupée (" + p.immatriculation() + ").");
        }
        Long id = jdbc.queryForObject("""
            insert into vehicules (reservation_id, immatriculation, marque, couleur, place_id, cree_par) values (?, ?, ?, ?, ?, ?) returning id
            """, Long.class, reservationId, v.immatriculation().strip().toUpperCase(), vide(v.marque()), vide(v.couleur()), placeId, session.email());
        journal.noter("VEHICULE_ENTRE", r.reference(), v.immatriculation().toUpperCase() + (placeId == null ? "" : ", place " + placeId));
        return vehicules(null, reservationId).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void sortirVehicule(Long id) {
        if (jdbc.update("update vehicules set sortie_le = now() where id = ? and sortie_le is null", id) == 0) {
            throw Refus.conflit("Ce véhicule est déjà sorti.");
        }
        journal.noter("VEHICULE_SORTI", "véhicule " + id, null);
    }

    // ── Arrivée express ────────────────────────────────────────────────────

    /**
     * Arrivée express : {@code CRENEAU} (forfait, {@code tarifId}), {@code HEURE} ({@code heures}) ou {@code NUIT}. Client
     * facultatif : sans nom, la fiche commune « Client de passage ». {@code paiementMode} : le séjour est payé d'avance
     * (acompte du montant TTC, imputé au départ).
     */
    public record ExpressRequest(@NotNull Long uniteId, @NotNull @Pattern(regexp = "CRENEAU|HEURE|NUIT") String uniteDuree, Long tarifId,
                                 @Min(1) @Max(24) Integer heures, Long clientId, @Size(max = 120) String clientNom,
                                 @Size(max = 40) String clientTelephone, @Size(max = 20) String immatriculation,
                                 @Size(max = 60) String marque, @Size(max = 40) String couleur, Long placeId,
                                 @Size(max = 40) String codeAcces, @Pattern(regexp = "CAISSE|BANQUE|MOBILE_MONEY") String paiementMode,
                                 Long paiementEtablissementId, Boolean forcer) { }

    public record Express(ReservationService.Reservation reservation, String message, boolean paye) { }

    /**
     * Trois temps indépendants : séjour (créé et arrivé), véhicule, paiement. Un refus de paiement (M-FINAPP injoignable)
     * n'annule pas l'arrivée : le message le dit et l'acompte se reprend depuis le folio.
     */
    public Express express(ExpressRequest e, Session session) {
        // Création et arrivée dans une seule transaction : une arrivée refusée (unité pas prête) ne laisse pas de réservation.
        var reservation = new TransactionTemplate(transactions).execute(tx -> sejourExpress(e, session));
        String message = "Arrivée enregistrée : unité " + reservation.uniteNumero() + ", départ " + reservation.fin()
            .atZoneSameInstant(Durees.FUSEAU).toLocalTime().truncatedTo(ChronoUnit.MINUTES) + ".";
        if (e.immatriculation() != null && !e.immatriculation().isBlank()) {
            try {
                ajouterVehicule(reservation.id(), new VehiculeRequest(e.immatriculation(), e.marque(), e.couleur(), placePour(e, reservation)), session);
            } catch (Refus r) {
                message += " Véhicule non enregistré : " + r.getMessage();
            }
        }
        boolean paye = false;
        if (e.paiementMode() != null) {
            try {
                var folio = reservations.folio(reservation.id(), session);
                if (folio.resteAPayer().signum() > 0) {
                    reservations.fonds(reservation.id(), new ReservationService.FondsRequest("ACOMPTE", folio.resteAPayer(), e.paiementMode(),
                        e.paiementEtablissementId(), "PMS-EXPRESS-" + reservation.reference()), session);
                    message += " Payé d'avance : " + folio.resteAPayer() + " " + folio.devise() + ".";
                    paye = true;
                }
            } catch (FinappException | Refus x) {
                message += " Paiement non enregistré (" + x.getMessage() + ") : encaissez l'acompte depuis le folio.";
            }
        }
        return new Express(reservations.reservation(reservation.id()), message, paye);
    }

    private ReservationService.Reservation sejourExpress(ExpressRequest e, Session session) {
        OffsetDateTime maintenant = OffsetDateTime.now(Durees.FUSEAU).truncatedTo(ChronoUnit.MINUTES);
        Long clientId = e.clientId();
        ReservationService.NouveauClient nouveau = null;
        if (clientId == null) {
            if (e.clientNom() != null && !e.clientNom().isBlank()) {
                nouveau = new ReservationService.NouveauClient(e.clientNom().strip(), null, vide(e.clientTelephone()), null);
            } else {
                clientId = clientDePassage();
            }
        }
        ReservationService.ReservationRequest req = switch (e.uniteDuree()) {
            case "CRENEAU" -> new ReservationService.ReservationRequest(e.uniteId(), clientId, nouveau, "CRENEAU", null, null, maintenant, null,
                null, 1, 0, "RECEPTION", "CONFIRMEE", "Arrivée express", e.tarifId());
            case "HEURE" -> {
                int h = e.heures() == null ? 2 : e.heures();
                yield new ReservationService.ReservationRequest(e.uniteId(), clientId, nouveau, "HEURE", null, null, maintenant,
                    maintenant.plusHours(h), null, 1, 0, "RECEPTION", "CONFIRMEE", "Arrivée express", null);
            }
            default -> {
                // Nuit : arrivée après minuit et avant l'heure de départ = la nuit en cours (commencée la veille).
                Long etabId = parametrage.unite(e.uniteId()).etablissementId();
                LocalTime depart = parametrage.etablissements().stream().filter(x -> x.id().equals(etabId)).findFirst()
                    .orElseThrow(() -> Refus.introuvable("Établissement")).heureDepart();
                LocalDate jour = maintenant.toLocalDate();
                LocalDate arrivee = maintenant.toLocalTime().isBefore(depart) ? jour.minusDays(1) : jour;
                yield new ReservationService.ReservationRequest(e.uniteId(), clientId, nouveau, "NUIT", arrivee, arrivee.plusDays(1), null, null,
                    null, 1, 0, "RECEPTION", "CONFIRMEE", "Arrivée express", null);
            }
        };
        var r = reservations.creer(req, session, true);
        reservations.arriver(r.id(), new ReservationService.ArriveeRequest(vide(e.codeAcces()), e.forcer()));
        journal.noter("ARRIVEE_EXPRESS", r.reference(), "unité " + r.uniteNumero() + ", " + e.uniteDuree());
        return reservations.reservation(r.id());
    }

    /** Place demandée, sinon le garage attitré de l'unité s'il est libre. */
    private Long placePour(ExpressRequest e, ReservationService.Reservation r) {
        if (e.placeId() != null) return e.placeId();
        return places(r.etablissementId()).stream().filter(p -> r.uniteId().equals(p.uniteId()) && p.actif() && p.vehiculeId() == null)
            .map(Place::id).findFirst().orElse(null);
    }

    private Long clientDePassage() {
        return jdbc.queryForList("select id from clients where nom = ? and telephone is null and email is null order by id limit 1",
            Long.class, CLIENT_DE_PASSAGE).stream().findFirst()
            .orElseGet(() -> jdbc.queryForObject("insert into clients (nom, notes) values (?, 'Fiche commune des arrivées express sans nom.') returning id",
                Long.class, CLIENT_DE_PASSAGE));
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
