package com.mbsc.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mbsc.desktop.api.ApiClient;
import com.mbsc.desktop.local.LocalDatabase;
import com.mbsc.desktop.local.OutboxDao;
import com.mbsc.desktop.local.TransactionDao;
import com.mbsc.desktop.sync.NetworkMonitor;
import com.mbsc.desktop.sync.SyncEngine;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

/**
 * Point d'entree de l'application caisse JavaFX.
 * Cable les services partages (API, base locale, moteur de synchro) et
 * demarre la surveillance reseau.
 */
public class MainApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(MainApp.class);

    private static ApiClient apiClient;
    private static NetworkMonitor networkMonitor;
    private static SyncEngine syncEngine;

    private static Stage primaryStage;

    @Override
    public void start(Stage stage) throws Exception {
        primaryStage = stage;
        log.info("Demarrage de MBSC Finapp Desktop (API={})",
            com.mbsc.desktop.config.AppConfig.API_BASE_URL);

        // Services partages
        apiClient = new ApiClient();
        syncEngine = new SyncEngine(apiClient, new OutboxDao(), new TransactionDao());
        networkMonitor = new NetworkMonitor(apiClient);

        // Au retour de la connexion, declenche une synchro en arriere-plan
        networkMonitor.setOnStatusChange(online -> {
            if (online) {
                log.info("Connexion retablie : declenchement de la synchronisation automatique");
                Thread t = new Thread(syncEngine::synchronize, "mbsc-auto-sync");
                t.setDaemon(true);
                t.start();
            }
        });
        networkMonitor.start();

        stage.setTitle("MBSC Finapp - Caisse");
        try {
            stage.getIcons().add(new Image(
                MainApp.class.getResourceAsStream("/icon.png")));
        } catch (Exception ignored) {}
        stage.setOnCloseRequest(e -> {
            log.info("Fermeture de l'application : arret du monitor et de la base locale");
            networkMonitor.stop();
            LocalDatabase.shutdown();
            Platform.exit();
        });

        showLogin();
        stage.show();
    }

    public static void showLogin() throws Exception {
        load("/fxml/login.fxml", 420, 520);
    }

    public static void showMain() throws Exception {
        load("/fxml/main.fxml", 1280, 800);
        primaryStage.setMaximized(true);
    }

    private static void load(String fxml, int w, int h) throws Exception {
        FXMLLoader loader = new FXMLLoader(MainApp.class.getResource(fxml));
        Parent root = loader.load();
        Scene scene = new Scene(root, w, h);
        scene.getStylesheets().add(
            MainApp.class.getResource("/css/classroom.css").toExternalForm());
        primaryStage.setScene(scene);
        primaryStage.centerOnScreen();
    }

    public static ApiClient apiClient() { return apiClient; }
    public static NetworkMonitor networkMonitor() { return networkMonitor; }
    public static SyncEngine syncEngine() { return syncEngine; }

    public static void main(String[] args) {
        launch(args);
    }
}
