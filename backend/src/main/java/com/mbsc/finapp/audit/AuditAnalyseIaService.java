package com.mbsc.finapp.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbsc.finapp.audit.AuditLecteurService.Resultat;
import com.mbsc.finapp.audit.AuditModele.*;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.service.ChatGptClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Agent IA du journal d'audit : répond en français clair à une question de l'administrateur à partir des
 * événements de la période (synthèse chiffrée complète + échantillon d'événements pertinents). L'IA ne reçoit que
 * des données du journal (pas de contenu de requête, pas de mots de passe) et doit s'en tenir à ces données.
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AuditAnalyseIaService {

    private static final Logger log = LoggerFactory.getLogger(AuditAnalyseIaService.class);
    private static final DateTimeFormatter HEURE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_CARACTERES_EVENEMENTS = 45_000;

    private static final String SYSTEME = """
        Tu es un auditeur interne qui aide l'administrateur d'une application de gestion (comptabilité SYSCOHADA, caisse, \
        banque, ventes, RH/paie, budgets). Tu analyses le journal d'audit des actions des utilisateurs.
        Règles : réponds en français, simplement, sans jargon technique, avec des chiffres précis tirés UNIQUEMENT des données \
        fournies. N'invente rien : si une information n'est pas dans les données, dis-le. Nomme les utilisateurs par leur e-mail. \
        Chaque événement porte une ACTION EN CLAIR (« Création d'une vente », « Import d'un journal comptable — remplace toutes \
        les écritures existantes »...) : c'est elle que tu cites pour dire ce que l'utilisateur a fait. N'emploie jamais les codes \
        techniques (CREER, MODIFIER, admin/import...) ni les chemins dans ta réponse, sauf si on te les demande. Quand plusieurs \
        événements se suivent (analyse puis import, deux appels à quelques secondes d'écart), regroupe-les en une seule phrase \
        (« a analysé puis importé... »). Le module et l'opération techniques sont donnés entre parenthèses, pour information. \
        Un échec (statut 4xx/5xx) peut être une tentative refusée par manque de droits ou une erreur de saisie : \
        ne conclus à un comportement suspect que si les données le montrent (répétition, horaires inhabituels, droits refusés, \
        échecs de connexion), et présente-le comme un point à vérifier, jamais comme une accusation. \
        Le journal n'indique pas les valeurs modifiées, seulement qui a fait quoi, quand et sur quelle ressource : signale cette limite si la question la touche. \
        Le terminal (Ordinateur, Mobile, Tablette, Application) et le système (Windows, Android, iOS, macOS, Linux...) sont déduits de l'en-tête du \
        navigateur : ils sont indicatifs, l'utilisateur peut les modifier, et ils valent « Inconnu » quand l'information manque (notamment pour les \
        connexions enregistrées avant l'introduction de cette donnée). \
        Mise en forme : « reponse » est une synthèse courte (3 à 6 phrases, ou quelques puces commençant par « - »), sans titres \
        ni tableaux ; mets les chiffres clés en gras avec **…**. Les chiffres déjà affichés par l'écran (totaux, échecs, \
        utilisateurs actifs) ne sont à répéter que s'ils répondent à la question. Détaille les constats un par un dans \
        « pointsCles » (une phrase chacun) et place les points à vérifier dans « alertes » (liste vide s'il n'y en a pas).""";

    private static final Map<String, Object> SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
            "reponse", Map.of("type", "string"),
            "pointsCles", Map.of("type", "array", "items", Map.of("type", "string")),
            "alertes", Map.of("type", "array", "items", Map.of("type", "string")),
            "limites", Map.of("type", "string")),
        "required", List.of("reponse", "pointsCles", "alertes", "limites"),
        "additionalProperties", false);

    private record ReponseJson(String reponse, List<String> pointsCles, List<String> alertes, String limites) {}

    public record ReponseAudit(String reponse, List<String> pointsCles, List<String> alertes, String limites,
                               long evenementsPeriode, int evenementsTransmis, boolean echantillon) {}

    private final AuditLecteurService lecteur;
    private final ChatGptClient ia;
    private final ObjectMapper json;

    public ReponseAudit interroger(String question, LocalDate du, LocalDate au) {
        if (!StringUtils.hasText(question)) throw new TransitionInvalideException("Posez une question.");
        if (!ia.disponible()) {
            throw new TransitionInvalideException("L'IA n'est pas configurée : ajoutez une clé dans Administration › Intelligence artificielle.");
        }
        Filtre periode = new Filtre(du, au, null, null, null, null, null, null);
        Resultat tout = lecteur.lire(periode);
        Synthese synthese = lecteur.synthese(periode, tout);
        if (tout.evenements().isEmpty()) {
            return new ReponseAudit("Aucune action n'a été enregistrée sur cette période.", List.of(), List.of(),
                "Élargissez la période pour analyser davantage d'activité.", 0, 0, false);
        }
        List<Evenement> pertinents = selectionner(question, tout.evenements(), synthese);
        boolean echantillon = pertinents.size() < tout.evenements().size();

        StringBuilder sb = new StringBuilder();
        sb.append("PÉRIODE : ").append(synthese.du()).append(" au ").append(synthese.au()).append('\n');
        sb.append("QUESTION : ").append(question.strip()).append("\n\n");
        sb.append("SYNTHÈSE CHIFFRÉE (toute la période") .append(synthese.tronque() ? ", tronquée au plafond de lecture" : "").append(") :\n");
        sb.append("- actions enregistrées : ").append(synthese.total()).append(", dont échecs : ").append(synthese.echecs())
            .append(", connexions refusées : ").append(synthese.connexionsRefusees())
            .append(", utilisateurs actifs : ").append(synthese.utilisateursActifs()).append('\n');
        sb.append("- par utilisateur (actions/échecs) : ").append(liste(synthese.parUtilisateur(), 25)).append('\n');
        sb.append("- par action (actions/échecs) : ").append(liste(parAction(tout.evenements()), 30)).append('\n');
        sb.append("- par module : ").append(liste(synthese.parModule(), 20)).append('\n');
        sb.append("- par opération : ").append(liste(synthese.parOperation(), 20)).append('\n');
        sb.append("- par terminal : ").append(liste(synthese.parTerminal(), 10)).append('\n');
        sb.append("- par système : ").append(liste(synthese.parSysteme(), 10)).append('\n');
        sb.append("- par jour : ").append(synthese.parJour().stream().limit(62)
            .map(j -> j.jour() + "=" + j.total() + "/" + j.echecs()).collect(Collectors.joining(", "))).append("\n\n");
        sb.append("ÉVÉNEMENTS").append(echantillon ? " (échantillon pertinent de " + pertinents.size() + " sur " + tout.evenements().size() + ")" : "")
            .append(" — format : date heure | utilisateur | rôles | ACTION EN CLAIR #ressource (module opération) | statut | IP | terminal système | détail :\n");
        ZoneId zone = ZoneId.systemDefault();
        int transmis = 0;
        for (Evenement e : pertinents) {
            String l = e.horodatage().atZone(zone).format(HEURE) + " | " + nn(e.email()) + " | " + (e.roles() == null ? "" : String.join(",", e.roles()))
                + " | " + nn(e.libelle()) + (e.ressourceId() != null ? " #" + e.ressourceId() : "")
                + " (" + (e.module() == null ? "authentification" : e.module()) + " " + nn(e.operation()) + ")"
                + " | " + (e.statut() == null ? "-" : e.statut()) + " | " + nn(e.ip())
                + " | " + nn(e.terminal()) + " " + nn(e.systeme())
                + (e.detail() != null ? " | " + e.detail() : "") + '\n';
            if (sb.length() + l.length() > MAX_CARACTERES_EVENEMENTS + 6000) break;
            sb.append(l);
            transmis++;
        }
        try {
            String contenu = ia.jsonAnalyse(SYSTEME, sb.toString(), 3500, SCHEMA);
            ReponseJson r = json.readValue(nettoyer(contenu), ReponseJson.class);
            if (!StringUtils.hasText(r.reponse())) throw new IllegalStateException("réponse vide");
            return new ReponseAudit(r.reponse(), r.pointsCles() == null ? List.of() : r.pointsCles(),
                r.alertes() == null ? List.of() : r.alertes(), r.limites(), tout.evenements().size(), transmis, echantillon || transmis < pertinents.size());
        } catch (Exception e) {
            log.warn("Analyse IA du journal d'audit impossible : {}", e.toString());
            throw new TransitionInvalideException("L'agent IA n'a pas pu répondre pour le moment. Réessayez dans quelques instants.");
        }
    }

    /**
     * Choisit les événements à transmettre : ceux de l'utilisateur ou du module cités dans la question ; à défaut,
     * tous les échecs, les connexions, puis les actions les plus récentes.
     */
    List<Evenement> selectionner(String question, List<Evenement> tous, Synthese synthese) {
        String q = question.toLowerCase(Locale.ROOT);
        Set<String> users = synthese.parUtilisateur().stream().map(Compte::cle).filter(c -> {
            String low = c.toLowerCase(Locale.ROOT);
            String local = low.contains("@") ? low.substring(0, low.indexOf('@')) : low;
            return q.contains(low) || (local.length() >= 4 && q.contains(local));
        }).collect(Collectors.toSet());
        Set<String> modules = synthese.parModule().stream().map(Compte::cle).filter(m -> m.length() >= 4 && q.contains(m.toLowerCase(Locale.ROOT)))
            .collect(Collectors.toSet());
        Set<String> terminaux = citees(q, synthese.parTerminal());
        Set<String> systemes = citees(q, synthese.parSysteme());
        List<Evenement> cible = tous;
        if (!users.isEmpty() || !modules.isEmpty() || !terminaux.isEmpty() || !systemes.isEmpty()) {
            cible = tous.stream().filter(e -> (users.isEmpty() || users.contains(nn(e.email())))
                && (modules.isEmpty() || modules.contains(e.module() == null ? "authentification" : e.module()))
                && (terminaux.isEmpty() || terminaux.contains(e.terminal()))
                && (systemes.isEmpty() || systemes.contains(e.systeme()))).toList();
            if (!cible.isEmpty()) return limiter(cible, 400);
        }
        List<Evenement> choix = new ArrayList<>();
        tous.stream().filter(e -> !e.reussi()).limit(120).forEach(choix::add);
        tous.stream().filter(e -> "AUTHENTIFICATION".equals(e.type()) && e.reussi()).limit(60).forEach(choix::add);
        Set<Evenement> deja = Set.copyOf(choix);
        tous.stream().filter(e -> !deja.contains(e)).limit(220).forEach(choix::add);
        choix.sort((a, b) -> b.horodatage().compareTo(a.horodatage()));
        return limiter(choix, 400);
    }

    /**
     * Terminaux ou systèmes cités dans la question, comme mots entiers (pluriel accepté) : « mobiles » vise Mobile, mais
     * « ios » ne se trouve pas dans « curiosité ». « Inconnu » et « Application » sont ignorés : ce sont des mots courants.
     */
    static Set<String> citees(String question, List<Compte> comptes) {
        return comptes.stream().map(Compte::cle)
            .filter(c -> !AgentUtilisateur.INCONNU.equals(c) && !AgentUtilisateur.APPLICATION.equals(c))
            .filter(c -> Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(c.toLowerCase(Locale.ROOT)) + "s?(?![\\p{L}\\p{N}])")
                .matcher(question).find())
            .collect(Collectors.toSet());
    }

    /** Nombre d'actions et d'échecs par action en clair, de la plus fréquente à la moins fréquente. */
    static List<Compte> parAction(List<Evenement> evenements) {
        Map<String, long[]> m = new java.util.LinkedHashMap<>();
        for (Evenement e : evenements) {
            long[] t = m.computeIfAbsent(StringUtils.hasText(e.libelle()) ? e.libelle() : "?", k -> new long[2]);
            t[0]++;
            if (!e.reussi()) t[1]++;
        }
        return m.entrySet().stream().map(x -> new Compte(x.getKey(), x.getValue()[0], x.getValue()[1]))
            .sorted(java.util.Comparator.comparingLong(Compte::total).reversed().thenComparing(Compte::cle)).collect(Collectors.toList());
    }

    private static List<Evenement> limiter(List<Evenement> l, int max) {
        return l.size() <= max ? l : l.subList(0, max);
    }

    private static String liste(List<Compte> comptes, int max) {
        return comptes.stream().limit(max).map(c -> c.cle() + "=" + c.total() + "/" + c.echecs()).collect(Collectors.joining(", "));
    }

    private static String nettoyer(String c) {
        String s = c == null ? "" : c.strip();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "");
        }
        return s;
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }
}
