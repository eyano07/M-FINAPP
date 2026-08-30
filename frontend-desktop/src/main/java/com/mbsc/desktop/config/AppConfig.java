package com.mbsc.desktop.config;

/**
 * Configuration centrale de l'application desktop.
 * L'URL de l'API peut etre surchargee via -Dmbsc.api.url=...
 */
public final class AppConfig {

    public static final String API_BASE_URL =
        System.getProperty("mbsc.api.url", "http://localhost/api");

    /** Intervalle de verification reseau (ms). */
    public static final long NETWORK_CHECK_INTERVAL_MS = 10_000;

    /** Taille de lot pour le push de l'outbox. */
    public static final int SYNC_BATCH_SIZE = 50;

    private AppConfig() {}
}
