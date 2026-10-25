package com.mbsc.finapp.service.instance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbsc.finapp.audit.AuditService;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.TypeActionDistante;
import com.mbsc.finapp.domain.enums.TypeNotification;
import com.mbsc.finapp.dto.instance.ActionDistanteTransfert;
import com.mbsc.finapp.dto.instance.ResultatActionDistante;
import com.mbsc.finapp.dto.notes.ActionWorkflowRequest;
import com.mbsc.finapp.dto.notes.NoteFraisDetailResponse;
import com.mbsc.finapp.dto.notes.NoteFraisRequest;
import com.mbsc.finapp.dto.notes.PrioriteRequest;
import com.mbsc.finapp.repository.NoteFraisRepository;
import com.mbsc.finapp.repository.UserRepository;
import com.mbsc.finapp.security.UserPrincipal;
import com.mbsc.finapp.service.AdminService;
import com.mbsc.finapp.service.NoteFraisService;
import com.mbsc.finapp.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Serveur local : applique les actions faites sur la copie en ligne (voir {@link ActionsDistantesService}), au nom de
 * leur auteur et par les memes methodes qu'au guichet (memes regles, memes droits — ceux du serveur local). Une action
 * sur une note est refusee si la note a change depuis que l'auteur l'a vue (statut ou date de modification). Chaque
 * action n'est appliquee qu'une fois ({@code actions_distantes_appliquees}), son auteur est prevenu par une notification
 * et le journal d'audit en garde la trace.
 */
@Service
public class ActionsDistantesApplicateur {

    private static final Logger log = LoggerFactory.getLogger(ActionsDistantesApplicateur.class);

    private final ReplicationClient client;
    private final NoteFraisService notes;
    private final NoteFraisRepository noteRepository;
    private final UserRepository userRepository;
    private final AdminService admin;
    private final NotificationService notifications;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public ActionsDistantesApplicateur(ReplicationClient client, NoteFraisService notes, NoteFraisRepository noteRepository,
                                       UserRepository userRepository, AdminService admin, NotificationService notifications,
                                       AuditService audit, JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager tm) {
        this.client = client;
        this.notes = notes;
        this.noteRepository = noteRepository;
        this.userRepository = userRepository;
        this.admin = admin;
        this.notifications = notifications;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Recupere les actions en attente sur le serveur en ligne, les applique et renvoie les resultats. */
    public int traiter() throws IOException {
        ReplicationClient.Reponse r = client.envoyer("GET", "/replication/actions", null, null, Duration.ofSeconds(60));
        if (!r.ok()) {
            throw new IllegalStateException("Refus du serveur en ligne (actions faites en ligne, " + r.statut() + ") : " + r.texte());
        }
        List<ActionDistanteTransfert> actions = json.readValue(r.corps(), new TypeReference<>() {});
        if (actions.isEmpty()) return 0;
        List<ResultatActionDistante> resultats = new ArrayList<>();
        for (ActionDistanteTransfert a : actions) {
            resultats.add(appliquer(a));
        }
        ReplicationClient.Reponse retour = client.envoyer("POST", "/replication/actions/resultats", json.writeValueAsBytes(resultats),
            "application/json", Duration.ofSeconds(60));
        if (!retour.ok()) {
            throw new IllegalStateException("Résultats des actions faites en ligne non transmis (" + retour.statut() + ").");
        }
        return actions.size();
    }

    /** Applique une action (une seule fois) et renvoie son resultat. */
    ResultatActionDistante appliquer(ActionDistanteTransfert a) {
        List<ResultatActionDistante> deja = jdbc.query("select statut, message, resultat_note_id from actions_distantes_appliquees "
            + "where uuid = ?::uuid", (rs, i) -> new ResultatActionDistante(a.uuid(), rs.getString(1), rs.getString(2),
            (Long) rs.getObject(3)), a.uuid());
        if (!deja.isEmpty()) return deja.get(0);

        TypeActionDistante type = TypeActionDistante.valueOf(a.type());
        User auteur = userRepository.findById(a.auteurId()).orElse(null);
        ResultatActionDistante resultat;
        if (auteur == null || !auteur.isActif()) {
            resultat = refus(a, "Compte de l'auteur introuvable ou désactivé sur le serveur principal.");
            noter(a, resultat);
        } else {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(auteur), null, new UserPrincipal(auteur).getAuthorities()));
            try {
                resultat = tx.execute(s -> {
                    ResultatActionDistante ok = executer(a, type);
                    noter(a, ok);   // dans la meme transaction que l'action : appliquee une seule fois
                    return ok;
                });
            } catch (Refus e) {
                resultat = refus(a, e.getMessage());
                noter(a, resultat);
            } catch (AccessDeniedException e) {
                resultat = refus(a, "Droits insuffisants sur le serveur principal pour cette action.");
                noter(a, resultat);
            } catch (RuntimeException e) {
                resultat = refus(a, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                noter(a, resultat);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
        prevenir(auteur, a, type, resultat);
        tracer(a, type, resultat);
        log.info("Action faite en ligne {} ({}) : {} {}", a.uuid(), a.resume(), resultat.statut(), resultat.message());
        return resultat;
    }

    private ResultatActionDistante executer(ActionDistanteTransfert a, TypeActionDistante type) {
        switch (type) {
            case CREER_NOTE -> {
                JsonNode contenu = a.contenu();
                NoteFraisRequest req;
                try {
                    req = json.treeToValue(contenu.path("note"), NoteFraisRequest.class);
                } catch (IOException e) {
                    throw new Refus("Contenu de la note illisible.");
                }
                NoteFraisDetailResponse creee = notes.creer(req);
                for (ActionDistanteTransfert.Fichier f : a.fichiers()) {
                    notes.ajouterPieceJointe(creee.id(), telecharger(a.uuid(), f));
                }
                if (contenu.path("soumettre").asBoolean(false)) {
                    notes.soumettre(creee.id(), null);
                }
                return new ResultatActionDistante(a.uuid(), "APPLIQUEE", "Note " + creee.reference() + " créée.", creee.id());
            }
            case ACTIVER_UTILISATEUR -> {
                boolean actif = a.contenu().path("actif").asBoolean();
                admin.definirActif(a.cibleId(), actif);
                return new ResultatActionDistante(a.uuid(), "APPLIQUEE", actif ? "Compte réactivé." : "Compte désactivé.", null);
            }
            default -> {
                NoteFrais note = noteRepository.findById(a.noteId())
                    .orElseThrow(() -> new Refus("Note introuvable sur le serveur principal."));
                boolean memeStatut = Objects.equals(note.getStatut().name(), a.statutNoteVu());
                boolean memeVersion = a.dateMajNoteVue() == null || note.getDateMaj() == null
                    || note.getDateMaj().truncatedTo(ChronoUnit.MILLIS).equals(a.dateMajNoteVue().truncatedTo(ChronoUnit.MILLIS));
                if (!memeStatut || !memeVersion) {
                    throw new Refus("La note " + note.getReference() + " a changé sur le serveur principal depuis votre action "
                        + "(statut actuel : " + note.getStatut().name() + ") : action non appliquée, revoyez la note.");
                }
                Object corps = type == TypeActionDistante.PRIORITE
                    ? json.convertValue(a.contenu(), PrioriteRequest.class)
                    : json.convertValue(a.contenu(), ActionWorkflowRequest.class);
                NoteFraisDetailResponse apres = ActionsDistantesService.executer(notes, type, a.noteId(), corps);
                return new ResultatActionDistante(a.uuid(), "APPLIQUEE", type.libelle() + " — " + apres.reference() + " : appliqué.",
                    a.noteId());
            }
        }
    }

    private FichierEnMemoire telecharger(String uuid, ActionDistanteTransfert.Fichier f) {
        try {
            HttpResponse<java.io.InputStream> r = client.ouvrir("/replication/actions/" + uuid + "/fichiers/" + f.numero(),
                Duration.ofMinutes(5));
            if (r.statusCode() != 200) {
                throw new Refus("Pièce jointe « " + f.nom() + " » non récupérée (" + r.statusCode() + ").");
            }
            try (var in = r.body()) {
                return new FichierEnMemoire(f.nom(), f.typeMime(), in.readAllBytes());
            }
        } catch (IOException e) {
            throw new Refus("Pièce jointe « " + f.nom() + " » non récupérée : " + e.getMessage());
        }
    }

    private void noter(ActionDistanteTransfert a, ResultatActionDistante r) {
        jdbc.update("insert into actions_distantes_appliquees (uuid, type, statut, message, resultat_note_id) "
            + "values (?::uuid, ?, ?, ?, ?) on conflict (uuid) do nothing", a.uuid(), a.type(), r.statut(),
            r.message() == null ? null : (r.message().length() > 1000 ? r.message().substring(0, 1000) : r.message()),
            r.resultatNoteId());
    }

    private static ResultatActionDistante refus(ActionDistanteTransfert a, String message) {
        return new ResultatActionDistante(a.uuid(), "REFUSEE", message, null);
    }

    /** Notification a l'auteur (elle arrive aussi sur la copie en ligne par la synchronisation). */
    private void prevenir(User auteur, ActionDistanteTransfert a, TypeActionDistante type, ResultatActionDistante r) {
        if (auteur == null) return;
        try {
            boolean ok = "APPLIQUEE".equals(r.statut());
            Long noteId = r.resultatNoteId() != null ? r.resultatNoteId() : a.noteId();
            NoteFrais note = noteId == null ? null : noteRepository.findById(noteId).orElse(null);
            tx.executeWithoutResult(s -> notifications.notifierUtilisateur(auteur,
                ok ? TypeNotification.ACTION_EN_LIGNE_APPLIQUEE : TypeNotification.ACTION_EN_LIGNE_REFUSEE,
                ok ? "Action faite en ligne appliquée" : "Action faite en ligne refusée",
                limiter((ok ? "" : "Refus : ") + a.resume() + " — " + r.message(), 500),
                note == null ? null : "/notes-frais/" + note.getId(), note));
        } catch (RuntimeException e) {
            log.warn("Notification de l'action faite en ligne impossible : {}", e.toString());
        }
    }

    private void tracer(ActionDistanteTransfert a, TypeActionDistante type, ResultatActionDistante r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "ACTION_EN_LIGNE");
        m.put("operation", type.name());
        m.put("module", "NOTES_FRAIS");
        m.put("utilisateurId", a.auteurId());
        m.put("email", a.auteurIdentifiant());
        m.put("ressourceId", a.noteId() != null ? String.valueOf(a.noteId())
            : (r.resultatNoteId() != null ? String.valueOf(r.resultatNoteId()) : null));
        m.put("reussi", "APPLIQUEE".equals(r.statut()));
        m.put("detail", limiter("Action faite sur le serveur en ligne le " + a.creeLe() + " : " + a.resume() + " — " + r.message(), 900));
        audit.enregistrer(m);
    }

    private static String limiter(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    /** Refus metier d'une action (note changee, piece jointe absente...). */
    static final class Refus extends RuntimeException {
        Refus(String message) {
            super(message);
        }
    }
}
