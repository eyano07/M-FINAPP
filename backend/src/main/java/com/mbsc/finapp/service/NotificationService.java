package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Budget;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.Notification;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.TypeNotification;
import com.mbsc.finapp.dto.notifications.NotificationResponse;
import com.mbsc.finapp.dto.notifications.NotificationsPageResponse;
import com.mbsc.finapp.repository.NotificationRepository;
import com.mbsc.finapp.repository.UserRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Notifications applicatives generees par les transitions du workflow des
 * notes de frais, persistees puis diffusees en temps reel par SSE (une
 * connexion HTTP longue par onglet ouvert, un utilisateur pouvant en avoir
 * plusieurs).
 *
 * <p>Le flux SSE est authentifie comme n'importe quel autre endpoint : le
 * navigateur ne peut pas joindre l'en-tete {@code Authorization} a
 * {@code EventSource} nativement, le frontend ouvre donc la connexion via
 * {@code fetch} + lecture manuelle du flux (voir composable {@code
 * useNotifications}), ce qui evite d'exposer le jeton dans l'URL.</p>
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** Duree de vie d'une connexion SSE avant reconnexion cote client. */
    private static final long DUREE_EMETTEUR_MS = 30 * 60 * 1000L;

    /** Intervalle des commentaires de maintien de connexion (garde la connexion active a travers nginx/proxys). */
    private static final long INTERVALLE_HEARTBEAT_S = 20L;

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUser;
    private final MailNotificationService mail;

    /** Emetteurs SSE actifs par utilisateur (plusieurs onglets = plusieurs emetteurs). */
    private final Map<Long, List<SseEmitter>> emetteurs = new ConcurrentHashMap<>();

    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "notifications-heartbeat");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    void demarrerHeartbeat() {
        heartbeatExecutor.scheduleAtFixedRate(
            this::envoyerHeartbeats, INTERVALLE_HEARTBEAT_S, INTERVALLE_HEARTBEAT_S, TimeUnit.SECONDS);
    }

    @PreDestroy
    void arreter() {
        heartbeatExecutor.shutdownNow();
    }

    // ---------------------------------------------------------------------
    // Lecture / marquage
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public NotificationsPageResponse lister() {
        Long userId = currentUser.requireUserId();
        List<NotificationResponse> notifications = notificationRepository
            .findTop30ByDestinataireIdOrderByDateCreationDesc(userId).stream()
            .map(NotificationResponse::from)
            .toList();
        long nonLues = notificationRepository.countByDestinataireIdAndLueFalse(userId);
        return new NotificationsPageResponse(notifications, nonLues);
    }

    @Transactional
    public void marquerLue(Long id) {
        notificationRepository.marquerLue(id, currentUser.requireUserId());
    }

    @Transactional
    public void marquerToutesLues() {
        notificationRepository.marquerToutesLues(currentUser.requireUserId());
    }

    // ---------------------------------------------------------------------
    // Emission (appelee par les services metier lors des transitions)
    // ---------------------------------------------------------------------

    /** Notifie tous les utilisateurs actifs porteurs du role donne. */
    @Transactional
    public void notifierRole(RoleType role, TypeNotification type, String titre, String message,
                             String lien, NoteFrais note) {
        for (User destinataire : userRepository.findByRoles_NomAndActifTrue(role)) {
            notifierUtilisateur(destinataire, type, titre, message, lien, note);
        }
    }

    /** Notifie un utilisateur precis (ex. le createur de la note). Aucun effet si null (createur supprime, etc). */
    @Transactional
    public void notifierUtilisateur(User destinataire, TypeNotification type, String titre, String message,
                                    String lien, NoteFrais note) {
        if (destinataire == null) return;

        enregistrer(Notification.builder()
            .destinataire(destinataire)
            .type(type)
            .titre(titre)
            .message(message)
            .lien(lien)
            .noteFrais(note)
            .lue(false)
            .build());
    }

    /**
     * Notifie, au sujet d'un budget, les utilisateurs actifs des roles donnes et {@code aussi} (par exemple
     * l'auteur du budget, s'il n'a pas ces roles) : une seule notification par personne, jamais a l'auteur de
     * l'action ({@code acteur}). Le lien mene a la page du budget (a la liste des budgets sans budget).
     */
    @Transactional
    public void notifierBudget(Collection<RoleType> roles, User aussi, User acteur, TypeNotification type,
                               String titre, String message, Budget budget) {
        String lien = budget == null || budget.getId() == null ? "/budgets" : "/budgets/" + budget.getId();
        for (User destinataire : destinataires(roles, aussi, acteur)) {
            enregistrer(Notification.builder()
                .destinataire(destinataire)
                .type(type)
                .titre(titre)
                .message(message)
                .lien(lien)
                .budget(budget)
                .lue(false)
                .build());
        }
    }

    /**
     * Notifie, au sujet d'une note de frais, les utilisateurs actifs des roles donnes et {@code aussi} (par exemple
     * l'emetteur de la note) : une seule notification par personne, jamais a l'auteur de l'action ({@code acteur}). Le
     * lien mene a la page de la note.
     */
    @Transactional
    public void notifierNote(Collection<RoleType> roles, User aussi, User acteur, TypeNotification type,
                             String titre, String message, NoteFrais note) {
        notifierNote(roles, aussi, acteur, type, titre, message, note, Map.of());
    }

    /**
     * {@link #notifierNote(Collection, User, User, TypeNotification, String, String, NoteFrais)} etendue a des
     * destinataires qui ne voient pas forcement la note : les utilisateurs actifs de chaque role de {@code autres} (par
     * exemple le responsable bar, qui ne voit que ses propres notes) recoivent la meme notification, avec le lien associe a
     * leur role (par exemple le stock du bar) — sauf s'ils la recoivent deja par {@code roles} ou {@code aussi}, avec le
     * lien de la note. Toujours une seule notification par personne, jamais a l'acteur.
     */
    @Transactional
    public void notifierNote(Collection<RoleType> roles, User aussi, User acteur, TypeNotification type,
                             String titre, String message, NoteFrais note, Map<RoleType, String> autres) {
        String lienNote = note == null || note.getId() == null ? "/notes-frais" : "/notes-frais/" + note.getId();
        Map<Long, User> personnes = new LinkedHashMap<>();
        Map<Long, String> liens = new LinkedHashMap<>();
        for (User u : destinataires(roles, aussi, acteur)) {
            personnes.put(u.getId(), u);
            liens.put(u.getId(), lienNote);
        }
        for (Map.Entry<RoleType, String> e : autres.entrySet()) {
            for (User u : userRepository.findByRoles_NomAndActifTrue(e.getKey())) {
                if ((acteur == null || !u.getId().equals(acteur.getId())) && personnes.putIfAbsent(u.getId(), u) == null) {
                    liens.put(u.getId(), e.getValue());
                }
            }
        }
        for (User destinataire : personnes.values()) {
            enregistrer(Notification.builder()
                .destinataire(destinataire)
                .type(type)
                .titre(titre)
                .message(message)
                .lien(liens.get(destinataire.getId()))
                .noteFrais(note)
                .lue(false)
                .build());
        }
    }

    /**
     * Destinataires d'une notification de groupe : les utilisateurs actifs des roles donnes, plus {@code aussi} s'il est
     * actif, chacun une seule fois (un compte qui cumule deux roles n'est pas notifie deux fois), sans l'auteur de
     * l'action.
     */
    private Collection<User> destinataires(Collection<RoleType> roles, User aussi, User acteur) {
        Map<Long, User> destinataires = new LinkedHashMap<>();
        for (RoleType role : roles) {
            for (User u : userRepository.findByRoles_NomAndActifTrue(role)) {
                destinataires.putIfAbsent(u.getId(), u);
            }
        }
        if (aussi != null && aussi.isActif()) {
            destinataires.putIfAbsent(aussi.getId(), aussi);
        }
        if (acteur != null) {
            destinataires.remove(acteur.getId());
        }
        return destinataires.values();
    }

    /**
     * Enregistre, pousse en temps reel et copie par e-mail. Titre et message sont ramenes a la taille de leurs
     * colonnes (200 et 500 caracteres) : un motif de rejet ou un objet long ne doit pas faire echouer l'action metier.
     */
    private void enregistrer(Notification notif) {
        notif.setTitre(tronquer(notif.getTitre(), 200));
        notif.setMessage(tronquer(notif.getMessage(), 500));
        Notification enregistree = notificationRepository.save(notif);
        pousser(enregistree.getDestinataire().getId(), NotificationResponse.from(enregistree));
        mail.envoyerApresCommit(enregistree);
    }

    static String tronquer(String texte, int max) {
        if (texte == null) {
            return "";
        }
        String t = texte.strip();
        return t.length() <= max ? t : t.substring(0, max - 1).stripTrailing() + "…";
    }

    // ---------------------------------------------------------------------
    // Flux temps reel (SSE)
    // ---------------------------------------------------------------------

    public SseEmitter creerFlux() {
        Long userId = currentUser.requireUserId();
        SseEmitter emitter = new SseEmitter(DUREE_EMETTEUR_MS);

        emetteurs.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable retirer = () -> {
            List<SseEmitter> liste = emetteurs.get(userId);
            if (liste != null) liste.remove(emitter);
        };
        emitter.onCompletion(retirer);
        emitter.onTimeout(retirer);
        emitter.onError(e -> retirer.run());

        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name("connecte").data("ok"));
            }
        } catch (IOException e) {
            retirer.run();
        }

        return emitter;
    }

    private void pousser(Long userId, NotificationResponse payload) {
        List<SseEmitter> liste = emetteurs.get(userId);
        if (liste == null || liste.isEmpty()) return;

        for (SseEmitter emitter : liste) {
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().name("notification").data(payload));
                }
            } catch (IOException | IllegalStateException e) {
                liste.remove(emitter);
            }
        }
    }

    private void envoyerHeartbeats() {
        emetteurs.forEach((userId, liste) -> liste.removeIf(emitter -> {
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                }
                return false;
            } catch (IOException | IllegalStateException e) {
                return true;
            }
        }));
    }
}
