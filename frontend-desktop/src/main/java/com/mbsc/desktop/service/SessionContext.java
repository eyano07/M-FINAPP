package com.mbsc.desktop.service;

import com.mbsc.desktop.api.AuthDtos;

/**
 * Conserve la session utilisateur courante (caissier authentifie).
 */
public final class SessionContext {

    private static AuthDtos.UserInfo currentUser;

    private SessionContext() {}

    public static void setUser(AuthDtos.UserInfo user) {
        currentUser = user;
    }

    public static AuthDtos.UserInfo getUser() {
        return currentUser;
    }

    public static String email() {
        return currentUser != null ? currentUser.email() : "inconnu";
    }

    public static String displayName() {
        if (currentUser == null) return "";
        String full = (safe(currentUser.prenom()) + " " + safe(currentUser.nom())).trim();
        return full.isEmpty() ? currentUser.email() : full;
    }

    public static void clear() {
        currentUser = null;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
