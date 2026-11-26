package com.mbsc.finapp.service.mail;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

/** Traduit un échec d'envoi SMTP en message clair pour l'administrateur. */
public final class ErreurMail {

    private ErreurMail() {}

    public static String expliquer(Throwable e) {
        String types = "";
        String msgs = "";
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            types += t.getClass().getName() + " ";
            msgs += (t.getMessage() == null ? "" : t.getMessage()) + " | ";
            if (t.getCause() == t) break;
        }
        String tl = types.toLowerCase(Locale.ROOT);
        String ml = msgs.toLowerCase(Locale.ROOT);
        if (tl.contains("authenticationfailed") || ml.contains("535") || ml.contains("authentication failed")
            || ml.contains("username and password not accepted")) {
            return "Identifiant ou mot de passe refusé par le serveur (pour Gmail, utilisez un mot de passe d'application).";
        }
        if (tl.contains(UnknownHostException.class.getName().toLowerCase(Locale.ROOT)) || ml.contains("unknown host")) {
            return "Serveur introuvable : vérifiez le nom du serveur SMTP.";
        }
        if (tl.contains(SocketTimeoutException.class.getName().toLowerCase(Locale.ROOT)) || ml.contains("timed out")) {
            return "Le serveur SMTP ne répond pas (délai dépassé) : vérifiez le serveur, le port et le pare-feu.";
        }
        if (tl.contains(ConnectException.class.getName().toLowerCase(Locale.ROOT)) || tl.contains("mailconnectexception")
            || ml.contains("connection refused") || ml.contains("could not connect")) {
            return "Connexion impossible : vérifiez le serveur et le port (587 STARTTLS, 465 SSL, 25 sans chiffrement).";
        }
        if (tl.contains("ssl") || ml.contains("pkix") || ml.contains("starttls") || ml.contains("handshake")) {
            return "Échec du chiffrement TLS : essayez une autre sécurité (STARTTLS pour le port 587, SSL pour le 465).";
        }
        if (ml.contains("sender") || ml.contains("not owned") || ml.contains("550") || ml.contains("553") || ml.contains("relay")) {
            return "Le serveur refuse l'adresse d'expédition ou le relais : utilisez une adresse que ce compte est autorisé à utiliser.";
        }
        String brut = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return "Envoi impossible : " + (brut.length() > 200 ? brut.substring(0, 200) : brut);
    }
}
