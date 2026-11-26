package com.mbsc.hotellerie.securite;

import java.util.Set;

/**
 * Utilisateur connecté, tel que M-FINAPP le décrit ({@code /pms/moi}) : ses rôles M-FINAPP et son niveau sur le module
 * Hôtellerie. {@code jeton} est son jeton d'accès M-FINAPP, renvoyé à M-FINAPP pour toute écriture.
 */
public record Session(Long id, String email, String nom, String prenom, Set<String> roles, String niveau, String jeton) {

    public boolean a(String... rolesDemandes) {
        for (String r : rolesDemandes) {
            if (roles.contains(r)) return true;
        }
        return false;
    }

    public String nomComplet() {
        String n = ((prenom == null ? "" : prenom) + " " + (nom == null ? "" : nom)).trim();
        return n.isEmpty() ? email : n;
    }
}
