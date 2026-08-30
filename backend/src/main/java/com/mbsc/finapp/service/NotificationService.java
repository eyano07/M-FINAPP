package com.mbsc.finapp.service;

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

        Notification notif = Notification.builder()
            .destinataire(destinataire)
            .type(type)
            .titre(titre)
            .message(message)
            .lien(lien)
            .noteFrais(note)
            .lue(false)
            .build();
        notif = notificationRepository.save(notif);

        pousser(destinataire.getId(), NotificationResponse.from(notif));
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
