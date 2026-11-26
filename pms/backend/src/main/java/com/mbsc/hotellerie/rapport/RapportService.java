package com.mbsc.hotellerie.rapport;

import com.mbsc.hotellerie.commun.Refus;
import com.mbsc.hotellerie.reservation.Durees;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rapports de l'hébergement sur une période (jours inclus), en USD hors taxes :
 * <ul>
 *   <li>taux d'occupation = nuits vendues / nuits disponibles (unités actives × jours) ;</li>
 *   <li>prix moyen (ADR) = chiffre d'affaires des nuitées / nuits vendues ;</li>
 *   <li>revenu par unité disponible (RevPAR) = chiffre d'affaires des nuitées / nuits disponibles ;</li>
 *   <li>chiffre d'affaires par établissement, par canal et par unité, séjours courts (heure, forfait) et extras compris.</li>
 * </ul>
 * Un séjour à la nuit, à la semaine ou au mois répartit son montant au prorata des nuits ; un séjour court compte le
 * jour de son arrivée. Réalisé : séjours arrivés ou partis ; prévisionnel : réservations confirmées et options en plus.
 * Les écritures comptables restent celles de M-FINAPP (journal des ventes, analytique 986).
 */
@Service
@RequiredArgsConstructor
public class RapportService {

    private static final BigDecimal CENT = BigDecimal.valueOf(100);
    private final JdbcTemplate jdbc;

    public record Indicateurs(long unites, long nuitsDisponibles, long nuitsVendues, BigDecimal occupation, BigDecimal caNuitees,
                              BigDecimal adr, BigDecimal revpar, long sejoursCourts, BigDecimal caCourts, BigDecimal caExtras,
                              BigDecimal caTotal, long sejours) { }

    public record ParEtablissement(Long id, String nom, String type, Indicateurs indicateurs) { }

    public record ParCanal(String canal, long sejours, long nuits, BigDecimal ca, BigDecimal part) { }

    public record ParUnite(Long id, String numero, String typeNom, String etablissementNom, long nuits, BigDecimal occupation, BigDecimal ca) { }

    public record ParJour(LocalDate jour, long occupees, BigDecimal occupation, BigDecimal ca) { }

    public record Synthese(LocalDate du, LocalDate au, boolean previsionnel, Indicateurs total, List<ParEtablissement> etablissements,
                           List<ParCanal> canaux, List<ParUnite> unites, List<ParJour> jours) { }

    private record Unite(Long id, Long etablissementId, String numero, String typeNom, String etablissementNom) { }

    private record Sejour(Long id, Long uniteId, Long etablissementId, String canal, String uniteDuree, BigDecimal prix,
                          OffsetDateTime debut, OffsetDateTime fin, BigDecimal loyerBail) { }

    public Synthese synthese(LocalDate du, LocalDate au, Long etablissementId, boolean previsionnel) {
        if (du == null || au == null || au.isBefore(du)) throw Refus.invalide("Période invalide.");
        if (ChronoUnit.DAYS.between(du, au) > 731) throw Refus.invalide("Deux ans au plus par rapport.");
        long jours = ChronoUnit.DAYS.between(du, au) + 1;
        OffsetDateTime debut = du.atStartOfDay(Durees.FUSEAU).toOffsetDateTime();
        OffsetDateTime fin = au.plusDays(1).atStartOfDay(Durees.FUSEAU).toOffsetDateTime();
        String statuts = previsionnel ? "('ARRIVEE', 'PARTIE', 'CONFIRMEE', 'OPTION')" : "('ARRIVEE', 'PARTIE')";

        List<Unite> unites = jdbc.query("""
            select u.id, u.etablissement_id, u.numero, t.nom, e.nom from unites u join types_unite t on t.id = u.type_id
            join etablissements e on e.id = u.etablissement_id where u.actif and e.actif and (?::bigint is null or u.etablissement_id = ?)
            order by e.nom, u.ordre, u.numero
            """, (rs, i) -> new Unite(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5)), etablissementId, etablissementId);
        Map<Long, String[]> etabs = new LinkedHashMap<>();
        jdbc.query("select id, nom, type from etablissements where actif and (?::bigint is null or id = ?) order by nom",
            rs -> { etabs.put(rs.getLong(1), new String[] {rs.getString(2), rs.getString(3)}); }, etablissementId, etablissementId);

        List<Sejour> sejours = jdbc.query("""
            select r.id, r.unite_id, r.etablissement_id, r.canal, r.unite_duree, r.prix_unitaire, r.debut, r.fin, b.loyer_mensuel
            from reservations r left join baux b on b.reservation_id = r.id
            where r.statut in\s""" + statuts + """
             and r.debut < ? and r.fin > ? and (?::bigint is null or r.etablissement_id = ?)
            """, (rs, i) -> new Sejour(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getBigDecimal(6),
            rs.getObject(7, OffsetDateTime.class), rs.getObject(8, OffsetDateTime.class), rs.getBigDecimal(9)), fin, debut, etablissementId, etablissementId);

        // Extras (folio) datés dans la période, par séjour.
        Map<Long, BigDecimal> extras = new LinkedHashMap<>();
        jdbc.query("""
            select l.reservation_id, sum(round(l.quantite * l.prix_unitaire, 2)) from folio_lignes l join reservations r on r.id = l.reservation_id
            where r.statut in\s""" + statuts + """
             and l.date_ligne between ? and ? and (?::bigint is null or r.etablissement_id = ?) group by l.reservation_id
            """, rs -> { extras.put(rs.getLong(1), rs.getBigDecimal(2)); }, du, au, etablissementId, etablissementId);
        Map<Long, Long> etabDuSejourExtra = new LinkedHashMap<>();
        Map<Long, String> canalDuSejourExtra = new LinkedHashMap<>();
        if (!extras.isEmpty()) {
            // Identifiants numériques lus en base : la liste se compose sans risque d'injection.
            String ids = extras.keySet().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
            jdbc.query("select id, etablissement_id, canal from reservations where id in (" + ids + ")",
                rs -> { etabDuSejourExtra.put(rs.getLong(1), rs.getLong(2)); canalDuSejourExtra.put(rs.getLong(1), rs.getString(3)); });
        }

        // Accumulateurs.
        Map<Long, Acc> parEtab = new LinkedHashMap<>();
        for (Long id : etabs.keySet()) parEtab.put(id, new Acc());
        Map<String, Acc> parCanal = new LinkedHashMap<>();
        Map<Long, Acc> parUnite = new LinkedHashMap<>();
        for (Unite u : unites) {
            parUnite.put(u.id(), new Acc());
            parEtab.computeIfAbsent(u.etablissementId(), k -> new Acc()).unites++;
        }
        Map<LocalDate, long[]> occupeesParJour = new LinkedHashMap<>();
        Map<LocalDate, BigDecimal> caParJour = new LinkedHashMap<>();
        for (LocalDate d = du; !d.isAfter(au); d = d.plusDays(1)) {
            occupeesParJour.put(d, new long[] {0});
            caParJour.put(d, BigDecimal.ZERO);
        }

        for (Sejour s : sejours) {
            Acc e = parEtab.computeIfAbsent(s.etablissementId(), k -> new Acc());
            Acc c = parCanal.computeIfAbsent(s.canal(), k -> new Acc());
            Acc u = parUnite.get(s.uniteId());
            LocalDate arrivee = s.debut().atZoneSameInstant(Durees.FUSEAU).toLocalDate();
            boolean court = "HEURE".equals(s.uniteDuree()) || "CRENEAU".equals(s.uniteDuree());
            BigDecimal total = s.loyerBail() != null ? null : Durees.ht(s.prix(), BigDecimal.valueOf(Durees.quantite(s.uniteDuree(), s.debut(), s.fin())));
            if (court) {
                if (arrivee.isBefore(du) || arrivee.isAfter(au)) continue;
                e.courts++; e.caCourts = e.caCourts.add(total); e.sejours++;
                c.sejours++; c.ca = c.ca.add(total);
                if (u != null) u.ca = u.ca.add(total);
                caParJour.merge(arrivee, total, BigDecimal::add);
                continue;
            }
            LocalDate depart = s.fin().atZoneSameInstant(Durees.FUSEAU).toLocalDate();
            long nuitsTotales = Math.max(1, ChronoUnit.DAYS.between(arrivee, depart));
            // Bail : loyer mensuel ramené à la nuit (30 jours) ; sinon le montant du séjour réparti sur ses nuits.
            BigDecimal parNuit = s.loyerBail() != null ? s.loyerBail().divide(BigDecimal.valueOf(30), 6, RoundingMode.HALF_UP)
                : total.divide(BigDecimal.valueOf(nuitsTotales), 6, RoundingMode.HALF_UP);
            long nuits = 0;
            for (LocalDate d = arrivee.isBefore(du) ? du : arrivee; d.isBefore(depart) && !d.isAfter(au); d = d.plusDays(1)) {
                nuits++;
                occupeesParJour.get(d)[0]++;
                caParJour.merge(d, parNuit, BigDecimal::add);
            }
            if (nuits == 0) continue;
            BigDecimal ca = parNuit.multiply(BigDecimal.valueOf(nuits));
            e.nuits += nuits; e.caNuitees = e.caNuitees.add(ca); e.sejours++;
            c.sejours++; c.nuits += nuits; c.ca = c.ca.add(ca);
            if (u != null) { u.nuits += nuits; u.ca = u.ca.add(ca); }
        }
        for (var x : extras.entrySet()) {
            Long etab = etabDuSejourExtra.get(x.getKey());
            parEtab.computeIfAbsent(etab, k -> new Acc()).caExtras = parEtab.get(etab).caExtras.add(x.getValue());
            parCanal.computeIfAbsent(canalDuSejourExtra.get(x.getKey()), k -> new Acc()).ca =
                parCanal.get(canalDuSejourExtra.get(x.getKey())).ca.add(x.getValue());
        }

        Acc tout = new Acc();
        List<ParEtablissement> listeEtabs = new ArrayList<>();
        for (var x : parEtab.entrySet()) {
            Acc a = x.getValue();
            tout.ajouter(a);
            String[] info = etabs.getOrDefault(x.getKey(), new String[] {"—", null});
            listeEtabs.add(new ParEtablissement(x.getKey(), info[0], info[1], a.indicateurs(jours)));
        }
        BigDecimal caTotalCanaux = parCanal.values().stream().map(a -> a.ca).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<ParCanal> canaux = parCanal.entrySet().stream()
            .map(x -> new ParCanal(x.getKey(), x.getValue().sejours, x.getValue().nuits, deux(x.getValue().ca), pourcent(x.getValue().ca, caTotalCanaux)))
            .sorted(Comparator.comparing(ParCanal::ca).reversed()).toList();
        List<ParUnite> listeUnites = unites.stream().map(u -> {
            Acc a = parUnite.get(u.id());
            return new ParUnite(u.id(), u.numero(), u.typeNom(), u.etablissementNom(), a.nuits, pourcent(BigDecimal.valueOf(a.nuits), BigDecimal.valueOf(jours)), deux(a.ca));
        }).toList();
        long nbUnites = unites.size();
        List<ParJour> parJour = occupeesParJour.entrySet().stream()
            .map(x -> new ParJour(x.getKey(), x.getValue()[0], pourcent(BigDecimal.valueOf(x.getValue()[0]), BigDecimal.valueOf(nbUnites)), deux(caParJour.get(x.getKey()))))
            .toList();
        return new Synthese(du, au, previsionnel, tout.indicateurs(jours), listeEtabs, canaux, listeUnites, parJour);
    }

    private static final class Acc {
        long unites, nuits, courts, sejours;
        BigDecimal caNuitees = BigDecimal.ZERO, caCourts = BigDecimal.ZERO, caExtras = BigDecimal.ZERO, ca = BigDecimal.ZERO;

        void ajouter(Acc a) {
            unites += a.unites; nuits += a.nuits; courts += a.courts; sejours += a.sejours;
            caNuitees = caNuitees.add(a.caNuitees); caCourts = caCourts.add(a.caCourts); caExtras = caExtras.add(a.caExtras);
        }

        Indicateurs indicateurs(long jours) {
            long dispo = unites * jours;
            return new Indicateurs(unites, dispo, nuits, pourcent(BigDecimal.valueOf(nuits), BigDecimal.valueOf(dispo)), deux(caNuitees),
                nuits == 0 ? BigDecimal.ZERO : deux(caNuitees.divide(BigDecimal.valueOf(nuits), 6, RoundingMode.HALF_UP)),
                dispo == 0 ? BigDecimal.ZERO : deux(caNuitees.divide(BigDecimal.valueOf(dispo), 6, RoundingMode.HALF_UP)),
                courts, deux(caCourts), deux(caExtras), deux(caNuitees.add(caCourts).add(caExtras)), sejours);
        }
    }

    private static BigDecimal deux(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal pourcent(BigDecimal part, BigDecimal tout) {
        return tout.signum() == 0 ? BigDecimal.ZERO : part.multiply(CENT).divide(tout, 1, RoundingMode.HALF_UP);
    }
}
