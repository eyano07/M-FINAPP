package com.mbsc.finapp.security;

import com.mbsc.finapp.domain.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Acces utilitaire a l'utilisateur authentifie dans le contexte de securite.
 * Centralise la lecture du {@link SecurityContextHolder} pour eviter de la
 * dupliquer dans chaque service.
 */
@Component
public class CurrentUserProvider {

    /**
     * @return le {@link UserPrincipal} de la requete courante.
     * @throws IllegalStateException si aucun utilisateur n'est authentifie
     *         (ne devrait jamais arriver derriere un endpoint securise).
     */
    public UserPrincipal requirePrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            throw new IllegalStateException("Aucun utilisateur authentifie dans le contexte");
        }
        return principal;
    }

    /** @return l'entite metier {@link User} de l'utilisateur courant. */
    public User requireUser() {
        return requirePrincipal().getDomainUser();
    }

    /** @return l'identifiant de l'utilisateur courant. */
    public Long requireUserId() {
        return requirePrincipal().getId();
    }
}
