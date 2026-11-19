package com.mbsc.hotellerie.appartement;

import com.mbsc.hotellerie.audit.Journal;
import com.mbsc.hotellerie.commun.PhotoService;
import com.mbsc.hotellerie.commun.Refus;
import com.mbsc.hotellerie.reservation.ReservationService;
import com.mbsc.hotellerie.securite.Session;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Inventaire type d'un logement et états des lieux d'entrée et de sortie (un de chaque par séjour). L'entrée part de
 * l'inventaire type ; la sortie part de l'entrée, pièce par pièce, et chaque dégât ou manque porte une retenue proposée
 * sur la caution (que la réception impute au départ).
 */
@Service
@RequiredArgsConstructor
public class EtatDesLieuxService {

    private final JdbcTemplate jdbc;
    private final Journal journal;
    private final ReservationService reservations;
    private final PhotoService photos;

    // ── Inventaire type ─────────────────────────────────────────────────────

    public record ElementModele(Long id, @NotBlank @Size(max = 60) String piece, @NotBlank @Size(max = 120) String element,
                                @Min(1) Integer quantite, Integer ordre) { }

    public List<ElementModele> modele(Long uniteId) {
        return jdbc.query("select * from inventaires_modeles where unite_id = ? order by ordre, id", (rs, i) -> new ElementModele(
            rs.getLong("id"), rs.getString("piece"), rs.getString("element"), rs.getInt("quantite"), rs.getInt("ordre")), uniteId);
    }

    @Transactional
    public List<ElementModele> enregistrerModele(Long uniteId, List<@Valid ElementModele> elements) {
        if (elements.stream().anyMatch(e -> blanc(e.piece()) || blanc(e.element()))) throw Refus.invalide("Chaque élément a une pièce et un nom.");
        jdbc.update("delete from inventaires_modeles where unite_id = ?", uniteId);
        int ordre = 0;
        for (ElementModele e : elements) {
            jdbc.update("insert into inventaires_modeles (unite_id, piece, element, quantite, ordre) values (?, ?, ?, ?, ?)",
                uniteId, e.piece().strip(), e.element().strip(), e.quantite() == null ? 1 : e.quantite(), ordre++);
        }
        journal.noter("INVENTAIRE_TYPE", "unité " + uniteId, elements.size() + " élément(s)");
        return modele(uniteId);
    }

    // ── États des lieux ─────────────────────────────────────────────────────

    public record Ligne(Long id, @NotBlank @Size(max = 60) String piece, @NotBlank @Size(max = 120) String element,
                        @Min(0) Integer quantite, @NotNull @Pattern(regexp = "NEUF|BON|USAGE|ABIME|MANQUANT") String etat,
                        @Size(max = 255) String commentaire, @PositiveOrZero BigDecimal coutRetenue) { }

    public record Etat(Long id, Long reservationId, String type, OffsetDateTime dateEtat, String observations, boolean valide,
                       OffsetDateTime valideLe, String creePar, List<Ligne> lignes, BigDecimal retenue, List<PhotoService.Photo> photos) { }

    public List<Etat> etats(Long reservationId) {
        return jdbc.query("select * from etats_des_lieux where reservation_id = ? order by type", (rs, i) -> rs.getLong("id"), reservationId)
            .stream().map(this::etat).toList();
    }

    public Etat etat(Long id) {
        var lignes = jdbc.query("select * from etats_des_lieux_lignes where etat_id = ? order by ordre, id", (rs, i) -> new Ligne(rs.getLong("id"),
            rs.getString("piece"), rs.getString("element"), rs.getInt("quantite"), rs.getString("etat"), rs.getString("commentaire"),
            rs.getBigDecimal("cout_retenue")), id);
        BigDecimal retenue = lignes.stream().map(Ligne::coutRetenue).reduce(BigDecimal.ZERO, BigDecimal::add);
        return jdbc.query("select * from etats_des_lieux where id = ?", (rs, i) -> new Etat(rs.getLong("id"), rs.getLong("reservation_id"),
            rs.getString("type"), rs.getObject("date_etat", OffsetDateTime.class), rs.getString("observations"), rs.getBoolean("valide"),
            rs.getObject("valide_le", OffsetDateTime.class), rs.getString("cree_par"), lignes, retenue, photos.photos("ETAT_LIEUX", id)), id)
            .stream().findFirst().orElseThrow(() -> Refus.introuvable("État des lieux"));
    }

    /** Entrée : depuis l'inventaire type de l'unité. Sortie : depuis l'entrée (à défaut, l'inventaire type). */
    @Transactional
    public Etat ouvrir(Long reservationId, String type, Session session) {
        if (!"ENTREE".equals(type) && !"SORTIE".equals(type)) throw Refus.invalide("Type d'état des lieux : ENTREE ou SORTIE.");
        var r = reservations.reservation(reservationId);
        if (!jdbc.queryForList("select 1 from etats_des_lieux where reservation_id = ? and type = ?", Integer.class, reservationId, type).isEmpty()) {
            throw Refus.conflit("L'état des lieux " + ("ENTREE".equals(type) ? "d'entrée" : "de sortie") + " existe déjà pour ce séjour.");
        }
        Long id = jdbc.queryForObject("insert into etats_des_lieux (reservation_id, type, cree_par) values (?, ?, ?) returning id",
            Long.class, reservationId, type, session.email());
        Long entree = "SORTIE".equals(type) ? jdbc.queryForList("select id from etats_des_lieux where reservation_id = ? and type = 'ENTREE'",
            Long.class, reservationId).stream().findFirst().orElse(null) : null;
        if (entree != null) {
            jdbc.update("""
                insert into etats_des_lieux_lignes (etat_id, piece, element, quantite, etat, ordre)
                select ?, piece, element, quantite, case when etat = 'NEUF' then 'BON' else etat end, ordre from etats_des_lieux_lignes where etat_id = ?
                """, id, entree);
        } else {
            jdbc.update("""
                insert into etats_des_lieux_lignes (etat_id, piece, element, quantite, etat, ordre)
                select ?, piece, element, quantite, 'BON', ordre from inventaires_modeles where unite_id = ?
                """, id, r.uniteId());
        }
        journal.noter("ETAT_LIEUX_" + type, r.reference(), "ouvert");
        return etat(id);
    }

    public record EtatRequest(@Size(max = 4000) String observations, @NotNull List<@Valid Ligne> lignes) { }

    @Transactional
    public Etat enregistrer(Long id, EtatRequest r) {
        Etat e = etat(id);
        if (e.valide()) throw Refus.conflit("État des lieux validé : il ne se modifie plus.");
        if (r.lignes().stream().anyMatch(l -> blanc(l.piece()) || blanc(l.element()) || l.etat() == null)) {
            throw Refus.invalide("Chaque ligne a une pièce, un élément et un état.");
        }
        jdbc.update("update etats_des_lieux set observations = ? where id = ?", r.observations() == null || r.observations().isBlank()
            ? null : r.observations().strip(), id);
        jdbc.update("delete from etats_des_lieux_lignes where etat_id = ?", id);
        int ordre = 0;
        for (Ligne l : r.lignes()) {
            BigDecimal retenue = "ENTREE".equals(e.type()) || l.coutRetenue() == null ? BigDecimal.ZERO : l.coutRetenue();
            jdbc.update("""
                insert into etats_des_lieux_lignes (etat_id, piece, element, quantite, etat, commentaire, cout_retenue, ordre)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, l.piece().strip(), l.element().strip(), l.quantite() == null ? 1 : l.quantite(), l.etat(),
                l.commentaire() == null || l.commentaire().isBlank() ? null : l.commentaire().strip(), retenue, ordre++);
        }
        return etat(id);
    }

    @Transactional
    public Etat valider(Long id, Session session) {
        Etat e = etat(id);
        if (e.valide()) return e;
        if (e.lignes().isEmpty()) throw Refus.invalide("Ajoutez au moins un élément avant de valider.");
        jdbc.update("update etats_des_lieux set valide = true, valide_le = now(), date_etat = now() where id = ?", id);
        journal.noter("ETAT_LIEUX_VALIDE", "séjour " + e.reservationId(), e.type() + (e.retenue().signum() > 0 ? ", retenue proposée " + e.retenue() : ""));
        return etat(id);
    }

    private static boolean blanc(String s) {
        return s == null || s.isBlank();
    }
}
