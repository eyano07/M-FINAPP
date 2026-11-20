package com.mbsc.hotellerie.appartement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbsc.hotellerie.audit.Journal;
import com.mbsc.hotellerie.client.ClientService;
import com.mbsc.hotellerie.commun.Refus;
import com.mbsc.hotellerie.finapp.FinappClient;
import com.mbsc.hotellerie.finapp.FinappException;
import com.mbsc.hotellerie.reservation.Durees;
import com.mbsc.hotellerie.reservation.ReservationService;
import com.mbsc.hotellerie.securite.Session;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Appartements : baux de courte et moyenne durée. Un bail repose sur une réservation au mois (qui bloque le calendrier)
 * et porte un échéancier mensuel. Chaque échéance facturée devient une vente M-FINAPP à crédit (loyer, charges au
 * forfait et consommations d'eau et d'électricité relevées), réglée ensuite par la réception ; le départ ne refacture
 * jamais les loyers. Les relevés de compteurs et les états des lieux (inventaire, photos, retenues proposées sur la
 * caution) complètent le suivi du logement.
 */
@Service
@RequiredArgsConstructor
public class BailService {

    private static final Logger log = LoggerFactory.getLogger(BailService.class);
    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JdbcTemplate jdbc;
    private final Journal journal;
    private final ReservationService reservations;
    private final ClientService clients;
    private final FinappClient finapp;
    private final ObjectMapper json;

    // ── Baux ────────────────────────────────────────────────────────────────

    public record Bail(Long id, String reference, Long reservationId, String reservationReference, String reservationStatut,
                       Long etablissementId, String etablissementNom, Long uniteId, String uniteNumero, String typeNom, Long clientId,
                       String clientNom, LocalDate dateDebut, LocalDate dateFin, BigDecimal loyerMensuel, BigDecimal chargesForfait,
                       boolean refacturerCompteurs, String statut, String conditions, int echeancesAFacturer, BigDecimal duFacture,
                       LocalDate prochaineEcheance, String ameublement) { }

    private static final String SELECT = """
        select b.*, r.reference as res_ref, r.statut as res_statut, r.etablissement_id, e.nom as etab_nom, r.unite_id, u.numero as unite_numero,
               t.nom as type_nom, t.ameublement, r.client_id, trim(coalesce(c.prenom, '') || ' ' || c.nom) as client_nom, r.debut, r.fin,
               (select count(*) from echeances_bail x where x.bail_id = b.id and x.statut in ('A_FACTURER', 'EN_ATTENTE') and x.date_echeance <= current_date) as a_facturer,
               (select coalesce(sum(x.total_ttc), 0) from echeances_bail x where x.bail_id = b.id and x.statut = 'FACTUREE') as du,
               (select min(x.date_echeance) from echeances_bail x where x.bail_id = b.id and x.statut in ('A_FACTURER', 'EN_ATTENTE')) as prochaine
        from baux b join reservations r on r.id = b.reservation_id join etablissements e on e.id = r.etablissement_id
        join unites u on u.id = r.unite_id join types_unite t on t.id = u.type_id join clients c on c.id = r.client_id
        """;

    private static final RowMapper<Bail> LIGNE = (rs, i) -> new Bail(rs.getLong("id"), rs.getString("reference"), rs.getLong("reservation_id"),
        rs.getString("res_ref"), rs.getString("res_statut"), rs.getLong("etablissement_id"), rs.getString("etab_nom"), rs.getLong("unite_id"),
        rs.getString("unite_numero"), rs.getString("type_nom"), rs.getLong("client_id"), rs.getString("client_nom"),
        rs.getObject("debut", OffsetDateTime.class).atZoneSameInstant(Durees.FUSEAU).toLocalDate(),
        rs.getObject("fin", OffsetDateTime.class).atZoneSameInstant(Durees.FUSEAU).toLocalDate(), rs.getBigDecimal("loyer_mensuel"),
        rs.getBigDecimal("charges_forfait"), rs.getBoolean("refacturer_compteurs"), rs.getString("statut"), rs.getString("conditions"),
        rs.getInt("a_facturer"), rs.getBigDecimal("du"), rs.getDate("prochaine") == null ? null : rs.getDate("prochaine").toLocalDate(),
        rs.getString("ameublement"));

    public List<Bail> lister(Long etablissementId, String statut) {
        return jdbc.query(SELECT + """
            where (?::bigint is null or r.etablissement_id = ?) and (?::text is null or b.statut = ?)
            order by (b.statut = 'ACTIF') desc, r.debut desc limit 500
            """, LIGNE, etablissementId, etablissementId, statut, statut);
    }

    public Bail bail(Long id) {
        return jdbc.query(SELECT + " where b.id = ?", LIGNE, id).stream().findFirst().orElseThrow(() -> Refus.introuvable("Bail"));
    }

    public record Echeance(Long id, int numero, LocalDate periodeDebut, LocalDate periodeFin, LocalDate dateEcheance, BigDecimal loyer,
                           BigDecimal charges, String detailCharges, String statut, Long venteId, String venteReference,
                           BigDecimal totalTtc, OffsetDateTime factureeLe, OffsetDateTime regleeLe) { }

    public List<Echeance> echeances(Long bailId) {
        return jdbc.query("select * from echeances_bail where bail_id = ? order by numero", (rs, i) -> new Echeance(rs.getLong("id"),
            rs.getInt("numero"), rs.getDate("periode_debut").toLocalDate(), rs.getDate("periode_fin").toLocalDate(),
            rs.getDate("date_echeance").toLocalDate(), rs.getBigDecimal("loyer"), rs.getBigDecimal("charges"), rs.getString("detail_charges"),
            rs.getString("statut"), (Long) rs.getObject("vente_id"), rs.getString("vente_reference"), rs.getBigDecimal("total_ttc"),
            rs.getObject("facturee_le", OffsetDateTime.class), rs.getObject("reglee_le", OffsetDateTime.class)), bailId);
    }

    public record Detail(Bail bail, List<Echeance> echeances, List<Releve> releves) { }

    public Detail detail(Long id) {
        Bail b = bail(id);
        return new Detail(b, echeances(id), releves(b.uniteId()));
    }

    /**
     * Nouveau bail : sur une réservation au mois existante ({@code reservationId}) ou en la créant (unité, client, dates).
     * Le loyer est la base de l'échéancier ; la dernière période incomplète est facturée au prorata des jours.
     */
    public record BailRequest(Long reservationId, Long uniteId, Long clientId, @Valid ReservationService.NouveauClient nouveauClient,
                              LocalDate dateDebut, LocalDate dateFin, @NotNull @PositiveOrZero BigDecimal loyerMensuel,
                              @PositiveOrZero BigDecimal chargesForfait, Boolean refacturerCompteurs, @Size(max = 4000) String conditions) { }

    @Transactional
    public Detail creer(BailRequest r, Session session) {
        Long reservationId = r.reservationId();
        if (reservationId == null) {
            if (r.uniteId() == null || r.dateDebut() == null || r.dateFin() == null) {
                throw Refus.invalide("Précisez l'appartement, la date d'entrée et la date de sortie prévue.");
            }
            if (ChronoUnit.DAYS.between(r.dateDebut(), r.dateFin()) < 28) throw Refus.invalide("Un bail dure au moins un mois.");
            reservationId = reservations.creer(new ReservationService.ReservationRequest(r.uniteId(), r.clientId(), r.nouveauClient(), "MOIS",
                r.dateDebut(), r.dateFin(), null, null, r.loyerMensuel(), 1, 0, "RECEPTION", "CONFIRMEE", "Bail", null), session).id();
        }
        var res = reservations.reservation(reservationId);
        if (!java.util.Set.of("OPTION", "CONFIRMEE", "ARRIVEE").contains(res.statut())) throw Refus.conflit("Ce séjour est terminé ou annulé.");
        if (reservations.sousBail(reservationId)) throw Refus.conflit("Ce séjour porte déjà un bail.");
        if (jdbc.queryForObject("select count(*) from folio_lignes where reservation_id = ?", Long.class, reservationId) > 0) {
            log.debug("Bail sur un séjour qui a déjà des extras : ils restent au folio de départ.");
        }
        LocalDate debut = res.debut().atZoneSameInstant(Durees.FUSEAU).toLocalDate();
        LocalDate fin = res.fin().atZoneSameInstant(Durees.FUSEAU).toLocalDate();
        BigDecimal charges = r.chargesForfait() == null ? BigDecimal.ZERO : r.chargesForfait();
        String reference = "BAIL-" + Year.now(Durees.FUSEAU) + "-"
            + String.format("%05d", jdbc.queryForObject("select nextval('reference_bail_seq')", Long.class));
        Long id = jdbc.queryForObject("""
            insert into baux (reference, reservation_id, loyer_mensuel, charges_forfait, jour_echeance, refacturer_compteurs, conditions, cree_par)
            values (?, ?, ?, ?, ?, ?, ?, ?) returning id
            """, Long.class, reference, reservationId, r.loyerMensuel(), charges, Math.min(28, debut.getDayOfMonth()),
            r.refacturerCompteurs() == null || r.refacturerCompteurs(), vide(r.conditions()), session.email());
        // Le prix du séjour devient le loyer (le calendrier et le folio l'affichent ; le départ ne le refacture pas).
        jdbc.update("update reservations set prix_unitaire = ?, unite_duree = 'MOIS', maj_le = now() where id = ?", r.loyerMensuel(), reservationId);
        genererEcheances(id, debut, fin, r.loyerMensuel(), charges);
        journal.noter("BAIL_CREE", reference, "séjour " + res.reference() + ", loyer " + r.loyerMensuel() + ", du " + debut + " au " + fin);
        return detail(id);
    }

    /** Échéances mensuelles de la date d'entrée à la veille de la sortie ; la dernière, incomplète, au prorata. */
    void genererEcheances(Long bailId, LocalDate debut, LocalDate fin, BigDecimal loyer, BigDecimal charges) {
        int numero = 1;
        for (LocalDate pd = debut; pd.isBefore(fin); pd = debut.plusMonths(numero - 1L)) {
            LocalDate finMois = debut.plusMonths(numero).minusDays(1);
            LocalDate pf = finMois.isBefore(fin) ? finMois : fin.minusDays(1);
            BigDecimal part = BigDecimal.ONE;
            if (pf.isBefore(finMois)) {
                long jours = ChronoUnit.DAYS.between(pd, pf) + 1;
                long mois = ChronoUnit.DAYS.between(pd, finMois) + 1;
                part = BigDecimal.valueOf(jours).divide(BigDecimal.valueOf(mois), 6, RoundingMode.HALF_UP);
            }
            jdbc.update("""
                insert into echeances_bail (bail_id, numero, periode_debut, periode_fin, date_echeance, loyer, charges) values (?, ?, ?, ?, ?, ?, ?)
                """, bailId, numero, pd, pf, pd, loyer.multiply(part).setScale(2, RoundingMode.HALF_UP),
                charges.multiply(part).setScale(2, RoundingMode.HALF_UP));
            numero++;
            if (numero > 120) break;
        }
    }

    @Transactional
    public Bail resilier(Long id, String motif) {
        Bail b = bail(id);
        if (!"ACTIF".equals(b.statut())) throw Refus.conflit("Ce bail n'est plus actif.");
        jdbc.update("update baux set statut = 'RESILIE', termine_le = now(), conditions = concat_ws(E'\\n', conditions, ?) where id = ?",
            motif == null || motif.isBlank() ? null : "Résiliation : " + motif.strip(), id);
        jdbc.update("delete from echeances_bail where bail_id = ? and statut = 'A_FACTURER' and periode_debut > current_date", id);
        journal.noter("BAIL_RESILIE", b.reference(), motif);
        return bail(id);
    }

    // ── Facturation des échéances ──────────────────────────────────────────

    /**
     * Facture une échéance : vente M-FINAPP à crédit (loyer, charges au forfait, consommations relevées depuis la dernière
     * facturation) au nom du client lié à M-FINAPP. M-FINAPP injoignable : l'échéance attend (même clé : jamais deux ventes).
     */
    @Transactional(noRollbackFor = FinappException.class)
    public Echeance facturer(Long bailId, Long echeanceId, Session session) {
        Bail b = bail(bailId);
        Echeance e = echeance(bailId, echeanceId);
        if (!java.util.Set.of("A_FACTURER", "EN_ATTENTE").contains(e.statut())) throw Refus.conflit("Échéance déjà facturée.");
        Map<String, Object> demande;
        if ("EN_ATTENTE".equals(e.statut())) {
            demande = lire(jdbc.queryForObject("select demande from echeances_bail where id = ?", String.class, echeanceId));
        } else {
            var client = clients.client(b.clientId());
            if (client.finappClientId() == null) {
                throw Refus.invalide("Le loyer est facturé à crédit : liez d'abord le client à M-FINAPP (fiche client › Lier à M-FINAPP).");
            }
            Consommation conso = b.refacturerCompteurs() ? consommation(b, e) : Consommation.AUCUNE;
            BigDecimal charges = e.charges().add(conso.montant());
            List<String> detail = new ArrayList<>();
            if (e.charges().signum() > 0) detail.add("forfait " + e.charges());
            detail.addAll(conso.lignes());
            List<Map<String, Object>> lignes = new ArrayList<>();
            String periode = e.periodeDebut().format(JOUR) + " au " + e.periodeFin().format(JOUR);
            if (e.loyer().signum() > 0) {
                lignes.add(ligne("MOIS", e.loyer(), "Loyer du " + periode + " — " + b.typeNom() + " " + b.uniteNumero()));
            }
            if (charges.signum() > 0) {
                lignes.add(ligne("CHARGES", charges, "Charges du " + periode + " — " + String.join(" ; ", detail)));
            }
            if (lignes.isEmpty()) throw Refus.invalide("Rien à facturer sur cette échéance.");
            demande = new LinkedHashMap<>();
            demande.put("cle", "PMS-ECH-" + b.reference() + "-" + e.numero());
            demande.put("reference", b.reference() + "/" + e.numero());
            demande.put("clientId", client.finappClientId());
            demande.put("date", LocalDate.now(Durees.FUSEAU).toString());
            demande.put("mode", "CREDIT");
            demande.put("lignes", lignes);
            demande.put("acompteImpute", BigDecimal.ZERO);
            demande.put("cautionImputee", BigDecimal.ZERO);
            jdbc.update("update echeances_bail set charges = ?, detail_charges = ?, cle = ?, demande = ?, statut = 'EN_ATTENTE' where id = ?",
                charges, detail.isEmpty() ? null : String.join(" ; ", detail), demande.get("cle"), ecrire(demande), echeanceId);
            for (Long releve : conso.releves()) jdbc.update("update releves_compteurs set echeance_id = ? where id = ?", echeanceId, releve);
        }
        try {
            JsonNode folio = finapp.signe("POST", "/pms/folios", demande, session.jeton());
            jdbc.update("""
                update echeances_bail set statut = 'FACTUREE', vente_id = ?, vente_reference = ?, total_ttc = ?, facturee_le = now(), demande = null
                where id = ?
                """, folio.path("venteId").asLong(), folio.path("venteReference").asText(null), new BigDecimal(folio.path("totalTtc").asText("0")),
                echeanceId);
            journal.noter("ECHEANCE_FACTUREE", b.reference() + "/" + e.numero(), "vente " + folio.path("venteReference").asText()
                + ", " + folio.path("totalTtc").asText() + " TTC");
        } catch (FinappException x) {
            if (!x.injoignable()) {
                throw Refus.invalide("M-FINAPP a refusé la facture : " + x.getMessage());
            }
            log.warn("Échéance {}/{} en attente : {}", b.reference(), e.numero(), x.getMessage());
        }
        return echeance(bailId, echeanceId);
    }

    public record ReglementRequest(@NotNull @Pattern(regexp = "CAISSE|BANQUE|MOBILE_MONEY") String mode, Long etablissementId) { }

    /** Règlement d'une échéance facturée (créance du client soldée dans M-FINAPP). */
    @Transactional
    public Echeance regler(Long bailId, Long echeanceId, ReglementRequest r, Session session) {
        Bail b = bail(bailId);
        Echeance e = echeance(bailId, echeanceId);
        if (!"FACTUREE".equals(e.statut())) throw Refus.conflit("Seule une échéance facturée et non réglée se règle.");
        Map<String, Object> corps = new LinkedHashMap<>();
        String cle = "PMS-ECH-REG-" + b.reference() + "-" + e.numero();
        corps.put("cle", cle);
        corps.put("mode", r.mode());
        corps.put("etablissementId", r.etablissementId());
        corps.put("date", LocalDate.now(Durees.FUSEAU).toString());
        corps.put("acompteImpute", BigDecimal.ZERO);
        try {
            finapp.signe("POST", "/pms/folios/" + e.venteId() + "/reglement", corps, session.jeton());
        } catch (FinappException x) {
            throw x.injoignable() ? Refus.conflit("M-FINAPP est injoignable : réessayez le règlement dans un instant.")
                : Refus.invalide("M-FINAPP a refusé le règlement : " + x.getMessage());
        }
        jdbc.update("update echeances_bail set statut = 'REGLEE', reglement_cle = ?, reglee_le = now() where id = ?", cle, echeanceId);
        journal.noter("ECHEANCE_REGLEE", b.reference() + "/" + e.numero(), e.totalTtc() + " " + r.mode());
        return echeance(bailId, echeanceId);
    }

    private Echeance echeance(Long bailId, Long id) {
        return echeances(bailId).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow(() -> Refus.introuvable("Échéance"));
    }

    private static Map<String, Object> ligne(String nature, BigDecimal prix, String designation) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hebergement", nature);
        m.put("quantite", BigDecimal.ONE);
        m.put("prixUnitaire", prix);
        m.put("designation", designation.length() > 200 ? designation.substring(0, 197) + "…" : designation);
        return m;
    }

    // ── Compteurs ───────────────────────────────────────────────────────────

    public record TarifCompteur(Long etablissementId, String type, BigDecimal prixUnite) { }

    public List<TarifCompteur> tarifsCompteurs(Long etablissementId) {
        return jdbc.query("select * from tarifs_compteurs where (?::bigint is null or etablissement_id = ?) order by etablissement_id, type",
            (rs, i) -> new TarifCompteur(rs.getLong("etablissement_id"), rs.getString("type"), rs.getBigDecimal("prix_unite")),
            etablissementId, etablissementId);
    }

    public record TarifCompteurRequest(@NotNull Long etablissementId, @NotNull @Pattern(regexp = "EAU|ELECTRICITE") String type,
                                       @NotNull @PositiveOrZero BigDecimal prixUnite) { }

    @Transactional
    public TarifCompteur enregistrerTarifCompteur(TarifCompteurRequest r) {
        jdbc.update("""
            insert into tarifs_compteurs (etablissement_id, type, prix_unite) values (?, ?, ?)
            on conflict (etablissement_id, type) do update set prix_unite = excluded.prix_unite
            """, r.etablissementId(), r.type(), r.prixUnite());
        journal.noter("TARIF_COMPTEUR", r.type(), r.prixUnite().toPlainString());
        return new TarifCompteur(r.etablissementId(), r.type(), r.prixUnite());
    }

    public record Releve(Long id, Long uniteId, String type, LocalDate date, BigDecimal index, Long echeanceId, String notes,
                         String creePar, OffsetDateTime creeLe) { }

    public List<Releve> releves(Long uniteId) {
        return jdbc.query("select * from releves_compteurs where unite_id = ? order by type, date_releve", (rs, i) -> new Releve(rs.getLong("id"),
            rs.getLong("unite_id"), rs.getString("type"), rs.getDate("date_releve").toLocalDate(), rs.getBigDecimal("index_valeur"),
            (Long) rs.getObject("echeance_id"), rs.getString("notes"), rs.getString("cree_par"), rs.getObject("cree_le", OffsetDateTime.class)), uniteId);
    }

    public record ReleveRequest(@NotNull @Pattern(regexp = "EAU|ELECTRICITE") String type, @NotNull LocalDate date,
                                @NotNull @PositiveOrZero BigDecimal index, @Size(max = 255) String notes) { }

    @Transactional
    public Releve releve(Long uniteId, ReleveRequest r, Session session) {
        if (r.date().isAfter(LocalDate.now(Durees.FUSEAU))) throw Refus.invalide("Un relevé ne se date pas dans le futur.");
        BigDecimal avant = jdbc.query("""
            select index_valeur from releves_compteurs where unite_id = ? and type = ? and date_releve < ? order by date_releve desc limit 1
            """, rs -> rs.next() ? rs.getBigDecimal(1) : null, uniteId, r.type(), r.date());
        if (avant != null && r.index().compareTo(avant) < 0) {
            throw Refus.invalide("Index inférieur au relevé précédent (" + avant.stripTrailingZeros().toPlainString() + ") : vérifiez la saisie.");
        }
        Long id;
        try {
            id = jdbc.queryForObject("""
                insert into releves_compteurs (unite_id, type, date_releve, index_valeur, notes, cree_par) values (?, ?, ?, ?, ?, ?) returning id
                """, Long.class, uniteId, r.type(), r.date(), r.index(), vide(r.notes()), session.email());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw Refus.conflit("Un relevé de ce compteur existe déjà à cette date.");
        }
        journal.noter("RELEVE_COMPTEUR", "unité " + uniteId, r.type() + " " + r.index() + " au " + r.date());
        return releves(uniteId).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void supprimerReleve(Long id) {
        if (jdbc.update("delete from releves_compteurs where id = ? and echeance_id is null", id) == 0) {
            throw Refus.conflit("Relevé déjà facturé (ou introuvable) : il ne se retire plus.");
        }
        journal.noter("RELEVE_RETIRE", "relevé " + id, null);
    }

    record Consommation(BigDecimal montant, List<String> lignes, List<Long> releves) {
        static final Consommation AUCUNE = new Consommation(BigDecimal.ZERO, List.of(), List.of());
    }

    /**
     * Consommation à refacturer jusqu'à la fin de la période : dernier relevé de la période moins le relevé de référence
     * (le dernier déjà facturé, sinon le premier relevé du bail, celui de l'entrée).
     */
    Consommation consommation(Bail b, Echeance e) {
        BigDecimal total = BigDecimal.ZERO;
        List<String> lignes = new ArrayList<>();
        List<Long> marques = new ArrayList<>();
        for (String type : List.of("EAU", "ELECTRICITE")) {
            List<Releve> liste = releves(b.uniteId()).stream()
                .filter(r -> r.type().equals(type) && !r.date().isBefore(b.dateDebut().minusDays(7)) && !r.date().isAfter(e.periodeFin()))
                .toList();
            if (liste.size() < 1) continue;
            Releve reference = liste.stream().filter(r -> r.echeanceId() != null).reduce((a, x) -> x).orElse(liste.get(0));
            Releve dernier = liste.get(liste.size() - 1);
            if (!dernier.date().isAfter(reference.date())) continue;
            BigDecimal q = dernier.index().subtract(reference.index());
            BigDecimal prix = tarifsCompteurs(b.etablissementId()).stream().filter(t -> t.type().equals(type)).map(TarifCompteur::prixUnite)
                .findFirst().orElse(null);
            String unite = "EAU".equals(type) ? "m³" : "kWh";
            String nom = "EAU".equals(type) ? "eau" : "électricité";
            if (prix == null) {
                lignes.add(nom + " " + q.stripTrailingZeros().toPlainString() + " " + unite + " (sans tarif : non facturée)");
                continue;
            }
            BigDecimal montant = q.multiply(prix).setScale(2, RoundingMode.HALF_UP);
            total = total.add(montant);
            lignes.add(nom + " " + q.stripTrailingZeros().toPlainString() + " " + unite + " × " + prix.stripTrailingZeros().toPlainString()
                + " = " + montant);
            for (Releve r : liste) if (r.echeanceId() == null && !r.date().isAfter(dernier.date())) marques.add(r.id());
        }
        return new Consommation(total, lignes, marques);
    }

    // ── Outils ──────────────────────────────────────────────────────────────

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
            throw new IllegalStateException("Demande de facture illisible.", e);
        }
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
