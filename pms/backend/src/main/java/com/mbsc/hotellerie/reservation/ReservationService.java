package com.mbsc.hotellerie.reservation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbsc.hotellerie.audit.Journal;
import com.mbsc.hotellerie.client.ClientService;
import com.mbsc.hotellerie.commun.Refus;
import com.mbsc.hotellerie.finapp.FinappClient;
import com.mbsc.hotellerie.finapp.FinappException;
import com.mbsc.hotellerie.menage.MenageService;
import com.mbsc.hotellerie.parametrage.ParametrageService;
import com.mbsc.hotellerie.securite.Session;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Réservations et séjours : création (contrôle de surréservation par la base), déplacement dans le calendrier,
 * arrivée, extras du folio, acomptes et cautions (pièces de M-FINAPP), départ et encaissement du folio par M-FINAPP.
 *
 * <p>Départ alors que M-FINAPP est injoignable : le départ est enregistré, l'unité passe « à nettoyer », et
 * l'encaissement reste EN_ATTENTE avec sa clé d'idempotence ; « Encaisser » le rejoue plus tard sans risque de double
 * vente.</p>
 */
@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    static final Set<String> ACTIVES = Set.of("OPTION", "CONFIRMEE", "ARRIVEE");

    private final JdbcTemplate jdbc;
    private final Journal journal;
    private final ParametrageService parametrage;
    private final ClientService clients;
    private final FinappClient finapp;
    private final ReferentielService referentiel;
    private final MenageService menage;
    private final ObjectMapper json;

    // ── Lecture ─────────────────────────────────────────────────────────────

    public record Reservation(Long id, String reference, Long etablissementId, String etablissementNom, Long uniteId,
                              String uniteNumero, String typeNom, Long clientId, String clientNom, String clientTelephone,
                              OffsetDateTime debut, OffsetDateTime fin, String uniteDuree, BigDecimal prixUnitaire, int adultes,
                              int enfants, String canal, String statut, String codeAcces, String notes, OffsetDateTime arriveeLe,
                              OffsetDateTime departLe, Long venteId, String venteReference, String encaissement,
                              String motifAnnulation, String creePar) { }

    private static final String SELECT = """
        select r.*, e.nom as etab_nom, u.numero as unite_numero, t.nom as type_nom,
               trim(coalesce(c.prenom, '') || ' ' || c.nom) as client_nom, c.telephone as client_tel
        from reservations r join etablissements e on e.id = r.etablissement_id join unites u on u.id = r.unite_id
        join types_unite t on t.id = u.type_id join clients c on c.id = r.client_id
        """;

    private static final RowMapper<Reservation> LIGNE = (rs, i) -> new Reservation(rs.getLong("id"), rs.getString("reference"),
        rs.getLong("etablissement_id"), rs.getString("etab_nom"), rs.getLong("unite_id"), rs.getString("unite_numero"),
        rs.getString("type_nom"), rs.getLong("client_id"), rs.getString("client_nom"), rs.getString("client_tel"),
        rs.getObject("debut", OffsetDateTime.class), rs.getObject("fin", OffsetDateTime.class), rs.getString("unite_duree"),
        rs.getBigDecimal("prix_unitaire"), rs.getInt("adultes"), rs.getInt("enfants"), rs.getString("canal"),
        rs.getString("statut"), rs.getString("code_acces"), rs.getString("notes"), rs.getObject("arrivee_le", OffsetDateTime.class),
        rs.getObject("depart_le", OffsetDateTime.class), (Long) rs.getObject("vente_id"), rs.getString("vente_reference"),
        rs.getString("encaissement"), rs.getString("motif_annulation"), rs.getString("cree_par"));

    /** Réservations qui touchent la période (calendrier, listes). */
    public List<Reservation> lister(OffsetDateTime du, OffsetDateTime au, Long etablissementId, String statut, String q) {
        String motif = q == null || q.isBlank() ? null : "%" + q.strip().toLowerCase() + "%";
        return jdbc.query(SELECT + """
            where (?::timestamptz is null or r.fin > ?) and (?::timestamptz is null or r.debut < ?)
              and (?::bigint is null or r.etablissement_id = ?) and (?::text is null or r.statut = ?)
              and (?::text is null or lower(r.reference || ' ' || c.nom || ' ' || coalesce(c.prenom, '') || ' ' || u.numero) like ?)
            order by r.debut, u.numero limit 1000
            """, LIGNE, du, du, au, au, etablissementId, etablissementId, statut, statut, motif, motif);
    }

    public Reservation reservation(Long id) {
        return jdbc.query(SELECT + " where r.id = ?", LIGNE, id).stream().findFirst()
            .orElseThrow(() -> Refus.introuvable("Réservation"));
    }

    // ── Création, modification ─────────────────────────────────────────────

    public record NouveauClient(@NotBlank @Size(max = 120) String nom, @Size(max = 120) String prenom,
                                @Size(max = 40) String telephone, @Size(max = 150) String email) { }

    /**
     * {@code dateArrivee}/{@code dateDepart} pour un séjour à la nuit, à la semaine ou au mois (heures de l'établissement) ;
     * {@code debut}/{@code fin} pour un séjour à l'heure. Prix vide : tarif de l'unité.
     */
    public record ReservationRequest(@NotNull Long uniteId, Long clientId, @Valid NouveauClient nouveauClient,
                                     @NotNull @Pattern(regexp = "HEURE|NUIT|SEMAINE|MOIS") String uniteDuree,
                                     LocalDate dateArrivee, LocalDate dateDepart, OffsetDateTime debut, OffsetDateTime fin,
                                     @PositiveOrZero BigDecimal prixUnitaire, @Min(0) Integer adultes, @Min(0) Integer enfants,
                                     @Pattern(regexp = "RECEPTION|TELEPHONE|DIRECT|BOOKING|AIRBNB|EXPEDIA|AUTRE") String canal,
                                     @Pattern(regexp = "OPTION|CONFIRMEE") String statut, @Size(max = 2000) String notes) { }

    @Transactional
    public Reservation creer(ReservationRequest r, Session session) {
        var unite = parametrage.unite(r.uniteId());
        exigerDisponible(unite);
        Long clientId = r.clientId();
        if (clientId == null) {
            if (r.nouveauClient() == null) throw Refus.invalide("Choisissez le client ou saisissez son nom.");
            var nc = r.nouveauClient();
            clientId = clients.enregistrer(null, new ClientService.ClientRequest(nc.nom(), nc.prenom(), nc.telephone(), nc.email(),
                null, null, null, null, null, null, null, null)).id();
        } else {
            clients.client(clientId);
        }
        OffsetDateTime[] periode = periode(unite.etablissementId(), r.uniteDuree(), r.dateArrivee(), r.dateDepart(), r.debut(), r.fin());
        BigDecimal prix = prix(r.prixUnitaire(), unite.id(), r.uniteDuree(), periode[0]);
        String reference = "RES-" + Year.now(Durees.FUSEAU) + "-"
            + String.format("%06d", jdbc.queryForObject("select nextval('reference_reservation_seq')", Long.class));
        Long id = jdbc.queryForObject("""
            insert into reservations (reference, etablissement_id, unite_id, client_id, debut, fin, unite_duree, prix_unitaire,
            adultes, enfants, canal, statut, notes, cree_par) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
            """, Long.class, reference, unite.etablissementId(), unite.id(), clientId, periode[0], periode[1], r.uniteDuree(), prix,
            r.adultes() == null ? 1 : r.adultes(), r.enfants() == null ? 0 : r.enfants(), r.canal() == null ? "RECEPTION" : r.canal(),
            r.statut() == null ? "CONFIRMEE" : r.statut(), vide(r.notes()), session.email());
        journal.noter("RESERVATION_CREEE", reference, "unité " + unite.numero() + ", " + periode[0] + " → " + periode[1]);
        return reservation(id);
    }

    public record ModificationRequest(Long uniteId, LocalDate dateArrivee, LocalDate dateDepart, OffsetDateTime debut,
                                      OffsetDateTime fin, @PositiveOrZero BigDecimal prixUnitaire, @Min(0) Integer adultes,
                                      @Min(0) Integer enfants, @Size(max = 2000) String notes, Long clientId) { }

    /** Dates, unité (délogement), prix, occupants, notes. Le calendrier s'en sert pour le glisser-déposer. */
    @Transactional
    public Reservation modifier(Long id, ModificationRequest m) {
        Reservation r = reservation(id);
        if (!ACTIVES.contains(r.statut())) throw Refus.conflit("Seule une réservation en cours ou à venir se modifie.");
        Long uniteId = m.uniteId() == null ? r.uniteId() : m.uniteId();
        var unite = parametrage.unite(uniteId);
        if (!uniteId.equals(r.uniteId())) exigerDisponible(unite);
        if (!unite.etablissementId().equals(r.etablissementId())) {
            throw Refus.invalide("Le déplacement se fait dans le même établissement.");
        }
        OffsetDateTime debut = r.debut();
        OffsetDateTime fin = r.fin();
        if (m.dateArrivee() != null || m.dateDepart() != null || m.debut() != null || m.fin() != null) {
            OffsetDateTime[] p = periode(unite.etablissementId(), r.uniteDuree(),
                m.dateArrivee() != null ? m.dateArrivee() : debut.atZoneSameInstant(Durees.FUSEAU).toLocalDate(),
                m.dateDepart() != null ? m.dateDepart() : fin.atZoneSameInstant(Durees.FUSEAU).toLocalDate(),
                m.debut() != null ? m.debut() : debut, m.fin() != null ? m.fin() : fin);
            debut = "ARRIVEE".equals(r.statut()) ? r.debut() : p[0];
            fin = p[1];
        }
        if (m.clientId() != null) clients.client(m.clientId());
        jdbc.update("""
            update reservations set unite_id = ?, debut = ?, fin = ?, prix_unitaire = coalesce(?, prix_unitaire),
            adultes = coalesce(?, adultes), enfants = coalesce(?, enfants), notes = coalesce(?, notes),
            client_id = coalesce(?, client_id), maj_le = now() where id = ?
            """, uniteId, debut, fin, m.prixUnitaire(), m.adultes(), m.enfants(), m.notes(), m.clientId(), id);
        if (!uniteId.equals(r.uniteId()) && "ARRIVEE".equals(r.statut())) {
            jdbc.update("update unites set etat_menage = 'A_NETTOYER' where id = ?", r.uniteId());
            menage.creerTache(r.uniteId(), id, "DEPART", "Délogement de " + r.clientNom());
        }
        journal.noter("RESERVATION_MODIFIEE", r.reference(), "unité " + unite.numero() + ", " + debut + " → " + fin);
        return reservation(id);
    }

    @Transactional
    public Reservation changerStatut(Long id, String statut, String motif) {
        Reservation r = reservation(id);
        switch (statut) {
            case "CONFIRMEE" -> {
                if (!"OPTION".equals(r.statut())) throw Refus.conflit("Seule une option se confirme.");
            }
            case "ANNULEE" -> {
                if (!Set.of("OPTION", "CONFIRMEE").contains(r.statut())) throw Refus.conflit("Seule une réservation pas encore arrivée s'annule.");
                if (aDesFonds(id)) throw Refus.conflit("Un acompte ou une caution a été encaissé : remboursez-le d'abord.");
            }
            case "NO_SHOW" -> {
                if (!"CONFIRMEE".equals(r.statut())) throw Refus.conflit("Seule une réservation confirmée passe en non-présentation.");
            }
            default -> throw Refus.invalide("Statut non modifiable ici : " + statut);
        }
        jdbc.update("update reservations set statut = ?, motif_annulation = ?, maj_le = now() where id = ?", statut, vide(motif), id);
        journal.noter("RESERVATION_" + statut, r.reference(), motif);
        return reservation(id);
    }

    // ── Arrivée ─────────────────────────────────────────────────────────────

    public record ArriveeRequest(@Size(max = 40) String codeAcces, Boolean forcer) { }

    @Transactional
    public Reservation arriver(Long id, ArriveeRequest a) {
        Reservation r = reservation(id);
        if (!Set.of("OPTION", "CONFIRMEE").contains(r.statut())) throw Refus.conflit("Cette réservation n'attend pas d'arrivée.");
        var unite = parametrage.unite(r.uniteId());
        if (unite.horsService()) throw Refus.conflit("L'unité " + unite.numero() + " est hors service : attribuez-en une autre.");
        if (!"PROPRE".equals(unite.etatMenage()) && !Boolean.TRUE.equals(a.forcer())) {
            throw Refus.conflit("L'unité " + unite.numero() + " n'est pas encore propre (" + unite.etatMenage()
                + ") : attendez le ménage ou confirmez l'arrivée quand même.");
        }
        jdbc.update("update reservations set statut = 'ARRIVEE', arrivee_le = now(), code_acces = ?, maj_le = now() where id = ?",
            vide(a.codeAcces()), id);
        journal.noter("ARRIVEE", r.reference(), "unité " + unite.numero() + ", client " + r.clientNom());
        return reservation(id);
    }

    // ── Folio ───────────────────────────────────────────────────────────────

    public record LigneFolio(Long id, String nature, Long articleId, String designation, BigDecimal quantite,
                             BigDecimal prixUnitaire, BigDecimal montantHt, BigDecimal tva, boolean stocke, LocalDate date) { }

    public record Fonds(Long id, String type, BigDecimal montant, String mode, String pieceReference, OffsetDateTime creeLe,
                        String creePar) { }

    public record Folio(Reservation reservation, List<LigneFolio> lignes, List<Fonds> fonds, BigDecimal totalHt,
                        BigDecimal totalTva, BigDecimal totalTtc, BigDecimal acomptes, BigDecimal cautions, BigDecimal resteAPayer,
                        BigDecimal tauxTva, String devise) { }

    public Folio folio(Long id, Session session) {
        Reservation r = reservation(id);
        BigDecimal taux = tauxTvaSansEchec(session);
        List<LigneFolio> lignes = new ArrayList<>();
        long q = Durees.quantite(r.uniteDuree(), r.debut(), r.fin());
        BigDecimal ht = Durees.ht(r.prixUnitaire(), BigDecimal.valueOf(q));
        lignes.add(new LigneFolio(null, "HEBERGEMENT", null, designationSejour(r, q), BigDecimal.valueOf(q), r.prixUnitaire(), ht,
            Durees.tva(ht, taux, true), false, r.debut().atZoneSameInstant(Durees.FUSEAU).toLocalDate()));
        jdbc.query("select * from folio_lignes where reservation_id = ? order by date_ligne, id", rs -> {
            BigDecimal qte = rs.getBigDecimal("quantite");
            BigDecimal prix = rs.getBigDecimal("prix_unitaire");
            BigDecimal m = Durees.ht(prix, qte);
            Long articleId = (Long) rs.getObject("article_id");
            lignes.add(new LigneFolio(rs.getLong("id"), "EXTRA", articleId, rs.getString("designation"), qte, prix, m,
                Durees.tva(m, taux, soumisSansEchec(session, articleId)), rs.getBoolean("stocke"), rs.getDate("date_ligne").toLocalDate()));
        }, id);
        List<Fonds> fonds = fonds(id);
        BigDecimal totalHt = lignes.stream().map(LigneFolio::montantHt).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalTva = lignes.stream().map(LigneFolio::tva).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ttc = totalHt.add(totalTva);
        // Fonds encore détenus : reçus, moins rendus, moins la part imputée sur le folio au départ.
        BigDecimal[] imputes = jdbc.queryForObject("select acompte_impute, caution_retenue from reservations where id = ?",
            (rs, i) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)}, id);
        BigDecimal acomptes = somme(fonds, "ACOMPTE").subtract(somme(fonds, "REMB_ACOMPTE")).subtract(imputes[0]);
        BigDecimal cautions = somme(fonds, "CAUTION").subtract(somme(fonds, "RESTITUTION")).subtract(imputes[1]);
        BigDecimal reste = "PARTIE".equals(r.statut()) && "ENCAISSE".equals(r.encaissement()) ? BigDecimal.ZERO
            : ttc.subtract(acomptes).max(BigDecimal.ZERO);
        return new Folio(r, lignes, fonds, totalHt, totalTva, ttc, acomptes, cautions, reste, taux, "USD");
    }

    public record ExtraRequest(Long articleId, @Size(max = 40) String articleCode, @NotBlank @Size(max = 200) String designation,
                               @NotNull @Positive BigDecimal quantite, @NotNull @PositiveOrZero BigDecimal prixUnitaire,
                               Boolean stocke, LocalDate date) { }

    @Transactional
    public Folio ajouterExtra(Long id, ExtraRequest e, Session session) {
        Reservation r = reservation(id);
        if (!Set.of("CONFIRMEE", "ARRIVEE").contains(r.statut())) throw Refus.conflit("Les extras s'ajoutent pendant le séjour.");
        jdbc.update("""
            insert into folio_lignes (reservation_id, article_id, article_code, designation, quantite, prix_unitaire, stocke, date_ligne, cree_par)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, e.articleId(), vide(e.articleCode()), e.designation().strip(), e.quantite(), e.prixUnitaire(),
            Boolean.TRUE.equals(e.stocke()), e.date() == null ? LocalDate.now(Durees.FUSEAU) : e.date(), session.email());
        journal.noter("EXTRA_AJOUTE", r.reference(), e.quantite() + " × " + e.designation() + " à " + e.prixUnitaire());
        return folio(id, session);
    }

    @Transactional
    public Folio retirerExtra(Long id, Long ligneId, Session session) {
        Reservation r = reservation(id);
        if ("PARTIE".equals(r.statut())) throw Refus.conflit("Le folio est clos : il est devenu une vente M-FINAPP.");
        if (jdbc.update("delete from folio_lignes where id = ? and reservation_id = ?", ligneId, id) == 0) {
            throw Refus.introuvable("Ligne du folio");
        }
        journal.noter("EXTRA_RETIRE", r.reference(), "ligne " + ligneId);
        return folio(id, session);
    }

    // ── Acomptes et cautions (pièces de M-FINAPP) ───────────────────────────

    public record FondsRequest(@NotNull @Pattern(regexp = "ACOMPTE|CAUTION|RESTITUTION|REMB_ACOMPTE") String type,
                               @NotNull @Positive BigDecimal montant, @NotNull @Pattern(regexp = "CAISSE|BANQUE|MOBILE_MONEY") String mode,
                               Long etablissementId, @Size(max = 80) String cle) { }

    @Transactional
    public Folio fonds(Long id, FondsRequest f, Session session) {
        Reservation r = reservation(id);
        if (Set.of("ANNULEE", "NO_SHOW").contains(r.statut()) && Set.of("ACOMPTE", "CAUTION").contains(f.type())) {
            throw Refus.conflit("Réservation annulée : plus d'encaissement.");
        }
        Folio avant = folio(id, session);
        if ("RESTITUTION".equals(f.type()) && f.montant().compareTo(avant.cautions()) > 0) {
            throw Refus.invalide("On ne restitue pas plus que la caution détenue (" + avant.cautions() + ").");
        }
        if ("REMB_ACOMPTE".equals(f.type()) && f.montant().compareTo(avant.acomptes()) > 0) {
            throw Refus.invalide("On ne rembourse pas plus que les acomptes reçus (" + avant.acomptes() + ").");
        }
        String cle = f.cle() == null || f.cle().isBlank() ? "PMS-" + f.type() + "-" + UUID.randomUUID() : f.cle();
        if (jdbc.queryForObject("select count(*) from fonds where cle = ?", Long.class, cle) > 0) {
            return folio(id, session);
        }
        var client = clients.client(r.clientId());
        Map<String, Object> corps = new HashMap<>();
        corps.put("cle", cle);
        corps.put("clientId", client.finappClientId());
        corps.put("clientNom", client.nomComplet());
        corps.put("montant", f.montant());
        corps.put("mode", f.mode());
        corps.put("etablissementId", f.etablissementId());
        corps.put("date", LocalDate.now(Durees.FUSEAU).toString());
        corps.put("reference", r.reference());
        String chemin = switch (f.type()) {
            case "ACOMPTE" -> "/pms/acomptes";
            case "CAUTION" -> "/pms/cautions";
            case "RESTITUTION" -> "/pms/cautions/restitutions";
            default -> "/pms/acomptes/remboursements";
        };
        JsonNode piece = finapp.signe("POST", chemin, corps, session.jeton());
        jdbc.update("""
            insert into fonds (reservation_id, type, montant, mode, etablissement_id, piece_reference, cle, cree_par)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """, id, f.type(), f.montant(), f.mode(), f.etablissementId(), piece.path("pieceReference").asText(null), cle, session.email());
        journal.noter("FONDS_" + f.type(), r.reference(), f.montant() + " " + f.mode() + ", pièce " + piece.path("pieceReference").asText());
        return folio(id, session);
    }

    // ── Départ ──────────────────────────────────────────────────────────────

    /**
     * Départ : le folio devient une vente M-FINAPP réglée par la réception. {@code mode} CREDIT : séjour facturé (créance
     * ouverte, client M-FINAPP obligatoire). {@code acompteImpute} vide : tous les acomptes reçus, dans la limite du dû.
     */
    public record DepartRequest(@NotNull @Pattern(regexp = "CAISSE|BANQUE|MOBILE_MONEY|CREDIT") String mode, Long etablissementId,
                                Long entrepotId, @PositiveOrZero BigDecimal acompteImpute, @PositiveOrZero BigDecimal cautionRetenue,
                                Boolean restituerCaution, @Size(max = 80) String cle) { }

    public record Depart(Reservation reservation, String message, boolean encaisse) { }

    @Transactional(noRollbackFor = FinappException.class)
    public Depart partir(Long id, DepartRequest d, Session session) {
        Reservation r = reservation(id);
        if (!"ARRIVEE".equals(r.statut())) throw Refus.conflit("Seul un client arrivé peut partir.");
        Folio f = folio(id, session);
        var client = clients.client(r.clientId());
        if ("CREDIT".equals(d.mode()) && client.finappClientId() == null) {
            throw Refus.invalide("Séjour facturé : liez d'abord le client à M-FINAPP (fiche client › Lier à M-FINAPP).");
        }
        BigDecimal acompte = d.acompteImpute() != null ? d.acompteImpute() : f.acomptes().min(f.totalTtc());
        BigDecimal caution = d.cautionRetenue() == null ? BigDecimal.ZERO : d.cautionRetenue();
        if ("CREDIT".equals(d.mode())) {
            acompte = BigDecimal.ZERO;
            caution = BigDecimal.ZERO;
        }
        if (acompte.compareTo(f.acomptes()) > 0) throw Refus.invalide("Acompte imputé supérieur aux acomptes reçus.");
        if (caution.compareTo(f.cautions()) > 0) throw Refus.invalide("Caution retenue supérieure à la caution détenue.");
        if (f.lignes().stream().anyMatch(LigneFolio::stocke) && d.entrepotId() == null) {
            throw Refus.invalide("Des extras sortent du stock : précisez l'entrepôt (minibar, bar…).");
        }
        String cle = d.cle() == null || d.cle().isBlank() ? "PMS-FOLIO-" + r.reference() + "-" + UUID.randomUUID() : d.cle();
        Map<String, Object> demande = demandeEncaissement(r, f, client, d, acompte, caution, cle);
        // Le départ est acquis dès maintenant (unité à nettoyer) ; l'encaissement suit.
        jdbc.update("""
            update reservations set statut = 'PARTIE', depart_le = now(), encaissement = 'EN_ATTENTE', cle_encaissement = ?,
            encaissement_demande = ?, maj_le = now() where id = ?
            """, cle, ecrire(demande), id);
        jdbc.update("update unites set etat_menage = 'A_NETTOYER' where id = ?", r.uniteId());
        menage.creerTache(r.uniteId(), id, "DEPART", "Départ de " + r.clientNom());
        journal.noter("DEPART", r.reference(), "unité " + r.uniteNumero() + " à nettoyer");
        String message = encaisserDemande(id, demande, session);
        boolean encaisse = !"EN_ATTENTE".equals(reservation(id).encaissement());
        // Caution restante : rendue au client (même canal), si demandé et si l'encaissement est passé.
        BigDecimal resteCaution = f.cautions().subtract(caution);
        if (encaisse && Boolean.TRUE.equals(d.restituerCaution()) && resteCaution.signum() > 0 && !"CREDIT".equals(d.mode())) {
            try {
                fonds(id, new FondsRequest("RESTITUTION", resteCaution, d.mode(), d.etablissementId(), cle + "-RC"), session);
                message += " Caution restante rendue : " + resteCaution + ".";
            } catch (FinappException | Refus e) {
                message += " Caution non rendue (" + e.getMessage() + ") : rendez-la depuis le folio.";
            }
        }
        return new Depart(reservation(id), message, encaisse);
    }

    /** Rejoue un encaissement resté EN_ATTENTE (M-FINAPP était injoignable), avec la même clé : jamais deux ventes. */
    @Transactional(noRollbackFor = FinappException.class)
    public Depart encaisser(Long id, Session session) {
        Reservation r = reservation(id);
        if (!"EN_ATTENTE".equals(r.encaissement())) throw Refus.conflit("Aucun encaissement en attente pour cette réservation.");
        String texte = jdbc.queryForObject("select encaissement_demande from reservations where id = ?", String.class, id);
        Map<String, Object> demande = lire(texte);
        String message = encaisserDemande(id, demande, session);
        return new Depart(reservation(id), message, !"EN_ATTENTE".equals(reservation(id).encaissement()));
    }

    private String encaisserDemande(Long id, Map<String, Object> demande, Session session) {
        try {
            JsonNode folio = finapp.signe("POST", "/pms/folios", demande, session.jeton());
            String etat = "CREDIT".equals(demande.get("mode")) ? "CREDIT" : "ENCAISSE";
            jdbc.update("""
                update reservations set encaissement = ?, vente_id = ?, vente_reference = ?, encaissement_demande = null,
                acompte_impute = ?, caution_retenue = ?, maj_le = now() where id = ?
                """, etat, folio.path("venteId").asLong(), folio.path("venteReference").asText(null),
                montant(demande.get("acompteImpute")), montant(demande.get("cautionImputee")), id);
            journal.noter("FOLIO_" + etat, (String) demande.get("reference"), "vente " + folio.path("venteReference").asText()
                + ", " + folio.path("totalTtc").asText() + " TTC");
            return "CREDIT".equals(etat)
                ? "Folio facturé à crédit : vente " + folio.path("venteReference").asText() + " (créance ouverte dans M-FINAPP)."
                : "Folio encaissé : vente " + folio.path("venteReference").asText() + ", " + folio.path("totalTtc").asText() + " TTC.";
        } catch (FinappException e) {
            if (e.injoignable()) {
                log.warn("Encaissement de {} en attente : {}", demande.get("reference"), e.getMessage());
                return "Départ enregistré, mais M-FINAPP est injoignable : l'encaissement est en attente. Cliquez « Encaisser » "
                    + "dès que M-FINAPP répond (aucun risque de double vente).";
            }
            // Refus métier de M-FINAPP (droits, règle comptable) : le départ reste enregistré, l'encaissement en attente.
            return "Départ enregistré, encaissement refusé par M-FINAPP : " + e.getMessage();
        }
    }

    private Map<String, Object> demandeEncaissement(Reservation r, Folio f, ClientService.Client client, DepartRequest d,
                                                     BigDecimal acompte, BigDecimal caution, String cle) {
        List<Map<String, Object>> lignes = new ArrayList<>();
        for (LigneFolio l : f.lignes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            if ("HEBERGEMENT".equals(l.nature())) m.put("hebergement", r.uniteDuree());
            else m.put("articleId", l.articleId());
            m.put("quantite", l.quantite());
            m.put("prixUnitaire", l.prixUnitaire());
            m.put("designation", l.designation());
            lignes.add(m);
        }
        if (lignes.stream().anyMatch(m -> !m.containsKey("hebergement") && m.get("articleId") == null)) {
            throw Refus.invalide("Un extra n'est pas rattaché à un article de M-FINAPP : retirez-le et ajoutez-le depuis la liste des articles.");
        }
        Map<String, Object> demande = new LinkedHashMap<>();
        demande.put("cle", cle);
        demande.put("reference", r.reference());
        demande.put("clientId", client.finappClientId());
        demande.put("clientNom", client.finappClientId() == null ? client.nomComplet() : null);
        demande.put("date", LocalDate.now(Durees.FUSEAU).toString());
        demande.put("mode", d.mode());
        demande.put("etablissementId", d.etablissementId());
        demande.put("entrepotId", d.entrepotId());
        demande.put("lignes", lignes);
        demande.put("acompteImpute", acompte);
        demande.put("cautionImputee", caution);
        return demande;
    }

    // ── Outils ──────────────────────────────────────────────────────────────

    private void exigerDisponible(ParametrageService.Unite u) {
        if (!u.actif()) throw Refus.conflit("L'unité " + u.numero() + " est désactivée.");
        if (u.horsService()) throw Refus.conflit("L'unité " + u.numero() + " est hors service" + (u.motifHorsService() == null ? "." : " : " + u.motifHorsService() + "."));
    }

    private OffsetDateTime[] periode(Long etablissementId, String uniteDuree, LocalDate arrivee, LocalDate depart,
                                     OffsetDateTime debut, OffsetDateTime fin) {
        if ("HEURE".equals(uniteDuree)) {
            if (debut == null || fin == null) throw Refus.invalide("Séjour à l'heure : précisez l'heure d'arrivée et de départ.");
            if (!fin.isAfter(debut)) throw Refus.invalide("Le départ doit suivre l'arrivée.");
            return new OffsetDateTime[] {debut, fin};
        }
        if (arrivee == null || depart == null) throw Refus.invalide("Précisez les dates d'arrivée et de départ.");
        if (!depart.isAfter(arrivee)) throw Refus.invalide("Le départ doit être au moins le lendemain de l'arrivée.");
        LocalTime[] heures = jdbc.query("select heure_arrivee, heure_depart from etablissements where id = ?",
            rs -> rs.next() ? new LocalTime[] {rs.getTime(1).toLocalTime(), rs.getTime(2).toLocalTime()} : null, etablissementId);
        if (heures == null) throw Refus.introuvable("Établissement");
        return new OffsetDateTime[] {
            arrivee.atTime(heures[0]).atZone(Durees.FUSEAU).toOffsetDateTime(),
            depart.atTime(heures[1]).atZone(Durees.FUSEAU).toOffsetDateTime()};
    }

    private BigDecimal prix(BigDecimal saisi, Long uniteId, String uniteDuree, OffsetDateTime debut) {
        if (saisi != null) return saisi;
        BigDecimal p = parametrage.prixPropose(uniteId, uniteDuree, debut.atZoneSameInstant(Durees.FUSEAU).toLocalDate());
        if (p == null) throw Refus.invalide("Aucun tarif « " + uniteDuree + " » pour cette unité : saisissez le prix.");
        return p;
    }

    private static String designationSejour(Reservation r, long q) {
        var d = r.debut().atZoneSameInstant(Durees.FUSEAU);
        var f = r.fin().atZoneSameInstant(Durees.FUSEAU);
        String periode = "HEURE".equals(r.uniteDuree())
            ? String.format("%02d/%02d %02d:%02d → %02d:%02d", d.getDayOfMonth(), d.getMonthValue(), d.getHour(), d.getMinute(),
                f.getHour(), f.getMinute())
            : String.format("%02d/%02d → %02d/%02d/%d", d.getDayOfMonth(), d.getMonthValue(), f.getDayOfMonth(), f.getMonthValue(), f.getYear());
        return "Hébergement " + r.typeNom() + " " + r.uniteNumero() + " — " + Durees.libelle(r.uniteDuree(), q) + " (" + periode + ")";
    }

    private List<Fonds> fonds(Long reservationId) {
        return jdbc.query("select * from fonds where reservation_id = ? order by cree_le", (rs, i) -> new Fonds(rs.getLong("id"),
            rs.getString("type"), rs.getBigDecimal("montant"), rs.getString("mode"), rs.getString("piece_reference"),
            rs.getObject("cree_le", OffsetDateTime.class), rs.getString("cree_par")), reservationId);
    }

    private boolean aDesFonds(Long id) {
        List<Fonds> f = fonds(id);
        return somme(f, "ACOMPTE").subtract(somme(f, "REMB_ACOMPTE")).signum() > 0
            || somme(f, "CAUTION").subtract(somme(f, "RESTITUTION")).signum() > 0;
    }

    private static BigDecimal somme(List<Fonds> f, String type) {
        return f.stream().filter(x -> type.equals(x.type())).map(Fonds::montant).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal tauxTvaSansEchec(Session session) {
        try {
            return referentiel.tauxTva(session);
        } catch (FinappException e) {
            return BigDecimal.ZERO;
        }
    }

    private boolean soumisSansEchec(Session session, Long articleId) {
        try {
            return referentiel.soumisTva(session, articleId);
        } catch (FinappException e) {
            return true;
        }
    }

    private static BigDecimal montant(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
    }

    private String ecrire(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lire(String texte) {
        try {
            return json.readValue(texte, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("Demande d'encaissement illisible.", e);
        }
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
