package com.mbsc.finapp.service.instance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mbsc.finapp.audit.AuditService;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.TypeActionDistante;
import com.mbsc.finapp.dto.admin.UserResponse;
import com.mbsc.finapp.dto.instance.ActionDistanteResponse;
import com.mbsc.finapp.dto.instance.ActionDistanteTransfert;
import com.mbsc.finapp.dto.instance.ResultatActionDistante;
import com.mbsc.finapp.dto.notes.ActionWorkflowRequest;
import com.mbsc.finapp.dto.notes.NoteFraisDetailResponse;
import com.mbsc.finapp.dto.notes.NoteFraisRequest;
import com.mbsc.finapp.dto.notes.PrioriteRequest;
import com.mbsc.finapp.repository.NoteFraisRepository;
import com.mbsc.finapp.repository.UserRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import com.mbsc.finapp.service.NoteFraisService;
import com.mbsc.finapp.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Copie en ligne d'un serveur local (mode MIROIR) : actions mises en attente puis appliquees par le serveur local
 * (voir {@link ActionsDistantesApplicateur}) — validations DFIN / DA, soumission d'un brouillon, creation d'une note de
 * frais, activation / desactivation d'un compte par l'administrateur.
 *
 * <p>Avant d'etre mise en attente, chaque action est ESSAYEE ici avec les memes regles qu'au serveur local (memes
 * methodes, memes droits) dans une transaction toujours annulee : une action impossible est refusee tout de suite, avec
 * le meme message ; les courriels ne partent qu'apres une validation de transaction, donc jamais lors d'un essai. Le
 * serveur local refait ensuite l'action pour de bon, et la refuse si la note a change entre-temps.</p>
 */
@Service
public class ActionsDistantesService {

    private static final Logger log = LoggerFactory.getLogger(ActionsDistantesService.class);
    private static final String DOSSIER_FICHIERS = "actions-distantes/";

    private final InstanceService instance;
    private final NoteFraisService notes;
    private final NoteFraisRepository noteRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUser;
    private final StorageService storage;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate essai;
    private final TransactionTemplate tx;

    public ActionsDistantesService(InstanceService instance, NoteFraisService notes, NoteFraisRepository noteRepository,
                                   UserRepository userRepository, CurrentUserProvider currentUser, StorageService storage,
                                   JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager tm) {
        this.instance = instance;
        this.notes = notes;
        this.noteRepository = noteRepository;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
        this.storage = storage;
        this.jdbc = jdbc;
        this.json = json;
        this.essai = new TransactionTemplate(tm);
        this.essai.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---------------------------------------------------------------------
    // Enregistrement (copie en ligne)
    // ---------------------------------------------------------------------

    /** Action sur une note existante : essayee, puis mise en attente ; renvoie la note telle qu'elle est ici. */
    public NoteFraisDetailResponse enregistrerActionNote(Long noteId, TypeActionDistante type, Object corps) {
        exigerMiroir();
        User auteur = currentUser.requireUser();
        // Une seule action en attente par note : la premiere change la note (statut, date de mise a jour), le serveur
        // local refuserait donc la suivante.
        String dejaEnAttente = jdbc.query("select resume, auteur_identifiant from actions_distantes where note_id = ? "
            + "and statut = 'EN_ATTENTE' order by cree_le limit 1",
            rs -> rs.next() ? rs.getString(1) + " (" + rs.getString(2) + ")" : null, noteId);
        if (dejaEnAttente != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Une action faite en ligne attend déjà la synchronisation "
                + "pour cette note : " + dejaEnAttente + ". Attendez qu'elle soit appliquée par le serveur local.");
        }
        essayer(() -> executer(notes, type, noteId, corps));
        NoteFrais note = noteRepository.findById(noteId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Note introuvable."));
        String resume = type.libelle() + " — " + note.getReference();
        inserer(UUID.randomUUID(), type, noteId, null, json.valueToTree(corps == null ? Map.of() : corps), null, auteur,
            note.getStatut().name(), note.getDateMaj(), resume);
        log.info("Action en ligne mise en attente : {} par {}", resume, auteur.getEmail());
        AuditService.detailler(resume + " — faite sur la copie en ligne, mise en attente : appliquée par le serveur local "
            + "à la synchronisation suivante.");
        return notes.consulter(noteId);
    }

    /**
     * Nouvelle note : essayee (creation, et soumission si demandee) puis mise en attente avec ses pieces jointes, gardees
     * ici jusqu'a ce que le serveur local la cree avec sa vraie reference.
     */
    public ActionDistanteResponse enregistrerCreationNote(NoteFraisRequest req, List<MultipartFile> fichiers, boolean soumettre) {
        exigerMiroir();
        User auteur = currentUser.requireUser();
        List<MultipartFile> pieces = fichiers == null ? List.of() : fichiers.stream().filter(f -> f != null && !f.isEmpty()).toList();
        pieces.forEach(NoteFraisService::verifierPieceJointe);
        essayer(() -> {
            NoteFraisDetailResponse creee = notes.creer(req);
            if (soumettre) notes.soumettre(creee.id(), null);
            return creee;
        });
        UUID uuid = UUID.randomUUID();
        ArrayNode descriptions = json.createArrayNode();
        int numero = 0;
        for (MultipartFile f : pieces) {
            numero++;
            String chemin = DOSSIER_FICHIERS + uuid + "/" + numero;
            try (InputStream in = f.getInputStream()) {
                storage.ecrireA(chemin, in);
            } catch (IOException e) {
                throw new UncheckedIOException("Pièce jointe illisible", e);
            }
            descriptions.addObject().put("numero", numero).put("nom", f.getOriginalFilename() == null ? "fichier" : f.getOriginalFilename())
                .put("typeMime", f.getContentType()).put("taille", f.getSize());
        }
        ObjectNode contenu = json.createObjectNode();
        contenu.set("note", json.valueToTree(req));
        contenu.put("soumettre", soumettre);
        String resume = "Nouvelle note : " + (req.objet() == null ? "" : req.objet());
        inserer(uuid, TypeActionDistante.CREER_NOTE, null, null, contenu, descriptions, auteur, null, null, resume);
        log.info("Nouvelle note en ligne mise en attente ({} piece(s) jointe(s)) par {}", pieces.size(), auteur.getEmail());
        AuditService.detailler(resume + " (" + pieces.size() + " pièce(s) jointe(s)" + (soumettre ? ", soumise" : "")
            + ") — mise en attente : créée par le serveur local à la synchronisation suivante.");
        return lire(uuid);
    }

    /**
     * ADMIN : active ou desactive un compte. Effet IMMEDIAT ici (connexion bloquee, sessions fermees) ; le serveur local
     * l'applique a la synchronisation suivante, et la recopie ne peut pas l'annuler d'ici la (ReplicationApplicateur).
     */
    public UserResponse enregistrerActivation(Long userId) {
        exigerMiroir();
        User admin = currentUser.requireUser();
        if (admin.getRoles().stream().noneMatch(r -> r.getNom() == RoleType.ADMIN)) {
            throw new AccessDeniedException("Réservé à l'administrateur.");
        }
        User cible = userRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable."));
        boolean actif = !cible.isActif();
        tx.executeWithoutResult(s -> jdbc.update("update users set actif = ?, version_jetons = version_jetons + ? where id = ?",
            actif, actif ? 0 : 1, userId));
        String resume = (actif ? "Réactivation" : "Désactivation") + " du compte " + cible.getEmail();
        inserer(UUID.randomUUID(), TypeActionDistante.ACTIVER_UTILISATEUR, null, userId,
            json.createObjectNode().put("actif", actif), null, admin, null, null, resume);
        log.info("{} sur la copie en ligne par {} (relayee au serveur local)", resume, admin.getEmail());
        AuditService.detailler(resume + " — effet immédiat sur la copie en ligne, appliqué au serveur local à la synchronisation suivante.");
        return UserResponse.from(userRepository.findById(userId).orElseThrow());
    }

    /** Actions en attente (et recentes) sur une note, pour sa fiche. */
    public List<ActionDistanteResponse> pourNote(Long noteId) {
        notes.consulter(noteId);   // memes regles de visibilite que la note elle-meme
        return jdbc.query("select * from actions_distantes where note_id = ? and (statut = 'EN_ATTENTE' or traite_le > now() - "
            + "interval '2 days') order by cree_le", (rs, i) -> reponse(rs), noteId);
    }

    /** Mes actions en attente et celles traitees ces 7 derniers jours (nouvelles notes comprises). */
    public List<ActionDistanteResponse> miennes() {
        Long moi = currentUser.requireUserId();
        return jdbc.query("select * from actions_distantes where auteur_id = ? and (statut = 'EN_ATTENTE' or traite_le > now() - "
            + "interval '7 days') order by cree_le desc", (rs, i) -> reponse(rs), moi);
    }

    // ---------------------------------------------------------------------
    // Transfert vers le serveur local (appels signes)
    // ---------------------------------------------------------------------

    /** Actions en attente, dans l'ordre ou elles ont ete faites. */
    public List<ActionDistanteTransfert> enAttente() {
        return jdbc.query("select * from actions_distantes where statut = 'EN_ATTENTE' order by cree_le limit 200", (rs, i) -> {
            try {
                List<ActionDistanteTransfert.Fichier> fichiers = new ArrayList<>();
                String f = rs.getString("fichiers");
                if (f != null) {
                    for (JsonNode n : json.readTree(f)) {
                        fichiers.add(new ActionDistanteTransfert.Fichier(n.path("numero").asInt(), n.path("nom").asText(),
                            n.path("typeMime").asText(null), n.path("taille").asLong()));
                    }
                }
                Timestamp vue = rs.getTimestamp("date_maj_note_vue");
                return new ActionDistanteTransfert(rs.getString("uuid"), rs.getString("type"), (Long) rs.getObject("note_id"),
                    (Long) rs.getObject("cible_id"), rs.getString("contenu") == null ? null : json.readTree(rs.getString("contenu")),
                    fichiers, rs.getLong("auteur_id"), rs.getString("auteur_identifiant"), rs.getString("statut_note_vu"),
                    vue == null ? null : vue.toInstant(), rs.getString("resume"), rs.getTimestamp("cree_le").toInstant());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** Contenu d'une piece jointe d'une nouvelle note en attente. */
    public void ecrireFichier(String uuid, int numero, OutputStream sortie) throws IOException {
        String chemin = DOSSIER_FICHIERS + UUID.fromString(uuid) + "/" + numero;
        try (InputStream in = storage.charger(chemin).getInputStream()) {
            in.transferTo(sortie);
        }
    }

    /** Resultats renvoyes par le serveur local : l'action quitte la file (appliquee ou refusee). */
    public void noterResultats(List<ResultatActionDistante> resultats) {
        for (ResultatActionDistante r : resultats) {
            String statut = "APPLIQUEE".equals(r.statut()) ? "APPLIQUEE" : "REFUSEE";
            jdbc.update("update actions_distantes set statut = ?, message = ?, resultat_note_id = ?, traite_le = now() "
                + "where uuid = ?::uuid and statut = 'EN_ATTENTE'", statut, tronquer(r.message()), r.resultatNoteId(), r.uuid());
        }
    }

    /** Reprise de la main par le serveur en ligne : ce qui attendait le serveur local ne lui parviendra plus. */
    public int abandonnerEnAttente() {
        return jdbc.update("update actions_distantes set statut = 'REFUSEE', traite_le = now(), message = 'Le serveur en ligne "
            + "a repris la main avant que le serveur local ne reçoive cette action : refaites-la.' where statut = 'EN_ATTENTE'");
    }

    // ---------------------------------------------------------------------

    /** Execute l'action sur une note par la meme methode qu'au guichet (ici en essai, au serveur local pour de bon). */
    static NoteFraisDetailResponse executer(NoteFraisService notes, TypeActionDistante type, Long noteId, Object corps) {
        ActionWorkflowRequest action = corps instanceof ActionWorkflowRequest a ? a : null;
        return switch (type) {
            case VERIFIER -> notes.verifier(noteId, action);
            case TRANSMETTRE -> notes.transmettre(noteId, action);
            case VALIDER -> notes.valider(noteId, action);
            case REJETER -> notes.rejeter(noteId, action);
            case OBSERVATION -> notes.ajouterObservation(noteId, action);
            case SOUMETTRE -> notes.soumettre(noteId, action);
            case PRIORITE -> notes.definirPriorite(noteId, (PrioriteRequest) corps);
            default -> throw new IllegalArgumentException("Action sans note : " + type);
        };
    }

    /** Execute dans une transaction toujours annulee : erreurs metier et refus de droits remontent tels quels. */
    private <T> T essayer(Supplier<T> action) {
        return essai.execute(s -> {
            s.setRollbackOnly();
            return action.get();
        });
    }

    private void inserer(UUID uuid, TypeActionDistante type, Long noteId, Long cibleId, JsonNode contenu, JsonNode fichiers,
                         User auteur, String statutNoteVu, Instant dateMajNoteVue, String resume) {
        tx.executeWithoutResult(s -> jdbc.update("insert into actions_distantes (uuid, type, note_id, cible_id, contenu, fichiers, "
                + "auteur_id, auteur_identifiant, statut_note_vu, date_maj_note_vue, resume) "
                + "values (?::uuid, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)",
            uuid.toString(), type.name(), noteId, cibleId, contenu == null ? null : contenu.toString(),
            fichiers == null ? null : fichiers.toString(), auteur.getId(), auteur.getEmail(), statutNoteVu,
            dateMajNoteVue == null ? null : Timestamp.from(dateMajNoteVue), tronquer300(resume)));
    }

    private ActionDistanteResponse lire(UUID uuid) {
        return jdbc.queryForObject("select * from actions_distantes where uuid = ?::uuid", (rs, i) -> reponse(rs), uuid.toString());
    }

    private static ActionDistanteResponse reponse(java.sql.ResultSet rs) throws java.sql.SQLException {
        TypeActionDistante type = TypeActionDistante.valueOf(rs.getString("type"));
        Timestamp traite = rs.getTimestamp("traite_le");
        return new ActionDistanteResponse(rs.getString("uuid"), type.name(), type.libelle(), (Long) rs.getObject("note_id"),
            rs.getString("resume"), rs.getString("statut"), rs.getString("message"), (Long) rs.getObject("resultat_note_id"),
            rs.getString("auteur_identifiant"), rs.getTimestamp("cree_le").toInstant(), traite == null ? null : traite.toInstant());
    }

    private void exigerMiroir() {
        if (!instance.estMiroir()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Réservé à la copie en ligne d'un serveur local.");
        }
    }

    private static String tronquer(String s) {
        return s == null ? null : (s.length() > 1000 ? s.substring(0, 1000) : s);
    }

    private static String tronquer300(String s) {
        return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) : s);
    }
}
