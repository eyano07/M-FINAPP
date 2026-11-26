package cd.mbsc.finapp;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * Messages Firebase envoyés par le serveur (PushService) : des « données » (titre, message, lien, id), affichées ici
 * que l'application soit ouverte ou non ; le toucher ouvre la note.
 */
public class ServiceMessages extends FirebaseMessagingService {

    @Override
    public void onNewToken(String jeton) {
        // Le site l'inscrira au compte connecté à la prochaine ouverture de l'application.
        Notifications.retenirJeton(this, jeton);
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        Map<String, String> d = message.getData();
        // Message silencieux du serveur (mouvement de trésorerie) : seulement relire le résumé des widgets.
        if ("widget".equals(d.get("type"))) {
            Widgets.rafraichir(this);
            return;
        }
        String titre = d.get("titre");
        if (titre == null || titre.isEmpty()) return;
        String lien = d.get("lien");
        int numero;
        try {
            numero = Integer.parseInt(d.getOrDefault("id", ""));
        } catch (NumberFormatException e) {
            numero = (int) (System.currentTimeMillis() & 0x7fffffff);
        }
        Notifications.afficher(this, titre, d.getOrDefault("message", ""), lien, numero);
        // Une note vient de changer d'étape : le widget « Notes de frais » se met à jour.
        Widgets.rafraichir(this);
    }
}
