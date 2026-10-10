package com.mbsc.finapp.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbsc.finapp.audit.AuditModele.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Lecture du journal d'audit ({@code audit.log} et ses archives journalières) pour l'écran d'administration :
 * filtres, pagination, synthèse chiffrée et export CSV. Lecture seule : le fichier n'est jamais modifié.
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class AuditLecteurService {

    private static final Logger log = LoggerFactory.getLogger(AuditLecteurService.class);
    /** Plafond d'événements gardés en mémoire pour une même requête (au-delà : résultat marqué « tronqué »). */
    static final int PLAFOND = 100_000;
    private static final Pattern ARCHIVE = Pattern.compile("audit-(\\d{4}-\\d{2}-\\d{2})\\.log(\\.gz)?");

    private final Path dossier;
    private final ObjectMapper json = new ObjectMapper();

    public AuditLecteurService(@Value("${app.audit.dir:./data/logs}") String dossier) {
        this.dossier = Path.of(dossier);
    }

    public record Resultat(List<Evenement> evenements, boolean tronque) {}

    /** Événements correspondant au filtre, du plus récent au plus ancien. */
    public Resultat lire(Filtre f) {
        LocalDate au = f.au() != null ? f.au() : LocalDate.now();
        LocalDate du = f.du() != null ? f.du() : au.minusDays(6);
        ZoneId zone = ZoneId.systemDefault();
        Instant debut = du.atStartOfDay(zone).toInstant();
        Instant fin = au.plusDays(1).atStartOfDay(zone).toInstant();
        List<Evenement> gardes = new ArrayList<>();
        boolean[] tronque = {false};
        for (Path fichier : fichiers(du, au)) {
            try (BufferedReader r = ouvrir(fichier)) {
                String ligne;
                while ((ligne = r.readLine()) != null) {
                    Evenement e = analyser(ligne);
                    if (e == null || e.horodatage() == null || e.horodatage().isBefore(debut) || !e.horodatage().isBefore(fin)) continue;
                    if (!correspond(e, f)) continue;
                    if (gardes.size() >= PLAFOND) {
                        tronque[0] = true;
                        break;
                    }
                    gardes.add(e);
                }
            } catch (IOException e) {
                log.warn("Lecture du journal d'audit impossible ({}) : {}", fichier.getFileName(), e.toString());
            }
        }
        gardes.sort(Comparator.comparing(Evenement::horodatage).reversed());
        return new Resultat(gardes, tronque[0]);
    }

    public Page page(Filtre f, int page, int taille) {
        Resultat r = lire(f);
        int t = Math.max(1, Math.min(taille, 200));
        int p = Math.max(0, page);
        int de = Math.min(p * t, r.evenements().size());
        int a = Math.min(de + t, r.evenements().size());
        return new Page(r.evenements().size(), p, t, r.tronque(), r.evenements().subList(de, a));
    }

    public Synthese synthese(Filtre f) {
        return synthese(f, lire(f));
    }

    public Synthese synthese(Filtre f, Resultat r) {
        List<Evenement> ev = r.evenements();
        LocalDate au = f.au() != null ? f.au() : LocalDate.now();
        LocalDate du = f.du() != null ? f.du() : au.minusDays(6);
        ZoneId zone = ZoneId.systemDefault();
        long echecs = ev.stream().filter(e -> !e.reussi()).count();
        long refusees = ev.stream().filter(e -> "AUTHENTIFICATION".equals(e.type()) && !e.reussi()).count();
        long users = ev.stream().map(Evenement::email).filter(StringUtils::hasText).distinct().count();
        TreeMap<LocalDate, long[]> jours = new TreeMap<>();
        for (Evenement e : ev) {
            long[] t = jours.computeIfAbsent(e.horodatage().atZone(zone).toLocalDate(), k -> new long[2]);
            t[0]++;
            if (!e.reussi()) t[1]++;
        }
        return new Synthese(du, au, ev.size(), echecs, refusees, users,
            ev.isEmpty() ? null : ev.get(ev.size() - 1).horodatage(), ev.isEmpty() ? null : ev.get(0).horodatage(), r.tronque(),
            comptes(ev, e -> StringUtils.hasText(e.email()) ? e.email() : "(anonyme)"),
            comptes(ev, e -> StringUtils.hasText(e.module()) ? e.module() : "authentification"),
            comptes(ev, e -> e.operation() == null ? "?" : e.operation()),
            jours.entrySet().stream().map(x -> new Jour(x.getKey(), x.getValue()[0], x.getValue()[1])).toList());
    }

    private static List<Compte> comptes(List<Evenement> ev, java.util.function.Function<Evenement, String> cle) {
        Map<String, long[]> m = new LinkedHashMap<>();
        for (Evenement e : ev) {
            long[] t = m.computeIfAbsent(cle.apply(e), k -> new long[2]);
            t[0]++;
            if (!e.reussi()) t[1]++;
        }
        return m.entrySet().stream().map(x -> new Compte(x.getKey(), x.getValue()[0], x.getValue()[1]))
            .sorted(Comparator.comparingLong(Compte::total).reversed().thenComparing(Compte::cle)).collect(Collectors.toList());
    }

    /** Export CSV (UTF-8 avec BOM, séparateur « ; » pour Excel), cellules protégées contre l'injection de formule. */
    public String exporterCsv(Filtre f) {
        Resultat r = lire(f);
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("Horodatage;Type;Utilisateur;Roles;IP;Module;Operation;Ressource;Methode;Chemin;Statut;Resultat;Duree ms;Detail\n");
        for (Evenement e : r.evenements()) {
            sb.append(String.join(";",
                cell(e.horodatage().toString()), cell(e.type()), cell(e.email()), cell(e.roles() == null ? "" : String.join(",", e.roles())),
                cell(e.ip()), cell(e.module()), cell(e.operation()), cell(e.ressourceId()), cell(e.methode()), cell(e.chemin()),
                cell(e.statut() == null ? "" : e.statut().toString()), cell(e.reussi() ? "OK" : "ECHEC"),
                cell(e.dureeMs() == null ? "" : e.dureeMs().toString()), cell(e.detail()))).append('\n');
        }
        return sb.toString();
    }

    static String cell(String v) {
        if (v == null) return "";
        String s = v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    // ---------------------------------------------------------------------

    private List<Path> fichiers(LocalDate du, LocalDate au) {
        List<Path> liste = new ArrayList<>();
        if (!Files.isDirectory(dossier)) return liste;
        try (Stream<Path> s = Files.list(dossier)) {
            s.forEach(p -> {
                Matcher m = ARCHIVE.matcher(p.getFileName().toString());
                if (m.matches()) {
                    LocalDate jour = LocalDate.parse(m.group(1));
                    if (!jour.isBefore(du) && !jour.isAfter(au)) liste.add(p);
                }
            });
        } catch (IOException e) {
            log.warn("Dossier du journal d'audit illisible : {}", e.toString());
        }
        liste.sort(Comparator.comparing(p -> p.getFileName().toString()));
        Path courant = dossier.resolve("audit.log");
        if (Files.isRegularFile(courant)) liste.add(courant);
        return liste;
    }

    private static BufferedReader ouvrir(Path p) throws IOException {
        var in = Files.newInputStream(p);
        return new BufferedReader(new InputStreamReader(p.getFileName().toString().endsWith(".gz") ? new GZIPInputStream(in) : in, StandardCharsets.UTF_8));
    }

    Evenement analyser(String ligne) {
        if (ligne == null || ligne.isBlank()) return null;
        try {
            JsonNode n = json.readTree(ligne);
            List<String> roles = new ArrayList<>();
            if (n.has("roles") && n.get("roles").isArray()) n.get("roles").forEach(x -> roles.add(x.asText()));
            return new Evenement(
                n.hasNonNull("horodatage") ? Instant.parse(n.get("horodatage").asText()) : null,
                txt(n, "type"), n.hasNonNull("utilisateurId") ? n.get("utilisateurId").asLong() : null, txt(n, "email"), roles,
                txt(n, "ip"), txt(n, "module"), txt(n, "operation"), txt(n, "ressourceId"), txt(n, "methode"), txt(n, "chemin"),
                txt(n, "requete"), n.hasNonNull("statut") ? n.get("statut").asInt() : null, n.path("reussi").asBoolean(true),
                n.hasNonNull("dureeMs") ? n.get("dureeMs").asLong() : null, txt(n, "detail"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String txt(JsonNode n, String champ) {
        return n.hasNonNull(champ) ? n.get(champ).asText() : null;
    }

    static boolean correspond(Evenement e, Filtre f) {
        if (StringUtils.hasText(f.utilisateur()) && (e.email() == null || !e.email().toLowerCase(Locale.ROOT).contains(f.utilisateur().trim().toLowerCase(Locale.ROOT)))) return false;
        if (StringUtils.hasText(f.module()) && !f.module().equalsIgnoreCase(e.module() == null ? "authentification" : e.module())) return false;
        if (StringUtils.hasText(f.operation()) && !f.operation().equalsIgnoreCase(e.operation())) return false;
        if (StringUtils.hasText(f.type()) && !f.type().equalsIgnoreCase(e.type())) return false;
        if ("OK".equalsIgnoreCase(f.resultat()) && !e.reussi()) return false;
        if ("ECHEC".equalsIgnoreCase(f.resultat()) && e.reussi()) return false;
        if (StringUtils.hasText(f.q())) {
            String q = f.q().trim().toLowerCase(Locale.ROOT);
            String hay = String.join(" ", nn(e.email()), nn(e.chemin()), nn(e.module()), nn(e.operation()), nn(e.ressourceId()), nn(e.ip()), nn(e.detail()), nn(e.requete()))
                .toLowerCase(Locale.ROOT);
            if (!hay.contains(q)) return false;
        }
        return true;
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }
}
