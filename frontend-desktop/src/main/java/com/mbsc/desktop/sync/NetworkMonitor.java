package com.mbsc.desktop.sync;

import com.mbsc.desktop.api.ApiClient;
import com.mbsc.desktop.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Surveille periodiquement la disponibilite du backend.
 * Notifie les abonnes a chaque changement d'etat ONLINE/OFFLINE.
 */
public class NetworkMonitor {

    private static final Logger log = LoggerFactory.getLogger(NetworkMonitor.class);

    private final ApiClient apiClient;
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mbsc-network-monitor");
            t.setDaemon(true);
            return t;
        });

    private final AtomicBoolean online = new AtomicBoolean(false);
    private Consumer<Boolean> onStatusChange = s -> {};

    public NetworkMonitor(ApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public void setOnStatusChange(Consumer<Boolean> listener) {
        this.onStatusChange = listener;
    }

    public boolean isOnline() {
        return online.get();
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::check, 0,
            AppConfig.NETWORK_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void check() {
        boolean reachable = apiClient.ping();
        boolean previous = online.getAndSet(reachable);
        if (reachable != previous) {
            log.info("Changement d'etat reseau : {}", reachable ? "EN LIGNE" : "HORS-LIGNE");
            onStatusChange.accept(reachable);
        }
    }

    public void stop() {
        scheduler.shutdownNow();
    }
}
