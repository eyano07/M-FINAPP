package com.mbsc.hotellerie.securite;

import com.mbsc.hotellerie.commun.Refus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Session de la requête en cours (posée par {@link JetonFinappFilter}). */
public final class SessionCourante {

    private SessionCourante() {
    }

    public static Session exiger() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof Session s) return s;
        throw new Refus(401, "Connectez-vous.");
    }

    /** Session de la requête ; refus 403 si l'utilisateur n'a aucun des rôles demandés. */
    public static Session exiger(String... roles) {
        Session s = exiger();
        if (!s.a(roles)) {
            throw new Refus(403, "Votre rôle ne permet pas cette action dans l'hôtellerie.");
        }
        return s;
    }

    public static String auteur() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof Session s ? s.email() : "système";
    }
}
