package com.mbsc.desktop.config;

import java.util.prefs.Preferences;

/**
 * Nom d'affichage du role CAISSIER, personnalisable par l'administrateur
 * (ex. "Caissier" -> "Operateur de caisse"). Recupere a la connexion puis
 * memorise localement pour rester disponible hors ligne.
 */
public final class RoleLabels {

    private static final String CLE = "role.libelle.CAISSIER";
    private static final String DEFAUT = "Caissier";
    private static final Preferences PREFS = Preferences.userNodeForPackage(RoleLabels.class);

    private static volatile String caissier = PREFS.get(CLE, DEFAUT);

    private RoleLabels() {
    }

    public static String caissier() {
        return caissier;
    }

    public static void definirCaissier(String libelle) {
        if (libelle == null || libelle.isBlank()) {
            return;
        }
        caissier = libelle.trim();
        try {
            PREFS.put(CLE, caissier);
        } catch (RuntimeException ignore) {
            // Memorisation locale best-effort.
        }
    }
}
