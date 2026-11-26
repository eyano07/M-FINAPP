package com.mbsc.desktop.controller;

import com.mbsc.desktop.MainApp;
import com.mbsc.desktop.api.AuthDtos;
import com.mbsc.desktop.service.SessionContext;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/**
 * Ecran de connexion. L'authentification necessite une connexion ;
 * une fois le caissier connecte une premiere fois, le jeton permet la synchro.
 */
public class LoginController {

    @FXML private TextField emailField;
    @FXML private PasswordField passwordField;
    @FXML private Button loginButton;
    @FXML private Label errorLabel;
    @FXML private Label statusLabel;

    @FXML
    private void initialize() {
        emailField.setText("caissier@mbsc.cd");
        boolean online = MainApp.networkMonitor().isOnline();
        statusLabel.setText(online ? "Backend disponible" : "Verification de la connexion...");
    }

    @FXML
    private void onLogin() {
        showError(null);
        loginButton.setDisable(true);
        statusLabel.setText("Connexion en cours...");

        final String email = emailField.getText().trim();
        final String pwd = passwordField.getText();

        connecter(email, pwd, null);
    }

    /** Connexion ; si le serveur demande le code de double authentification, l'ecran le demande puis reessaie. */
    private void connecter(String email, String pwd, String code) {
        Thread t = new Thread(() -> {
            try {
                AuthDtos.AuthResponse auth =
                    MainApp.apiClient().login(email, pwd, code);
                SessionContext.setUser(auth.user());
                MainApp.apiClient().chargerLibellesRoles();

                Platform.runLater(() -> {
                    try {
                        MainApp.showMain();
                    } catch (Exception e) {
                        showError("Erreur d'ouverture: " + e.getMessage());
                        loginButton.setDisable(false);
                    }
                });
            } catch (com.mbsc.desktop.api.ApiClient.DoubleAuthRequiseException e) {
                Platform.runLater(() -> {
                    javafx.scene.control.TextInputDialog dialogue = new javafx.scene.control.TextInputDialog();
                    dialogue.setTitle("Double authentification");
                    dialogue.setHeaderText(e.getMessage());
                    dialogue.setContentText("Code à 6 chiffres :");
                    dialogue.showAndWait().map(String::trim).filter(s -> !s.isEmpty()).ifPresentOrElse(
                        saisi -> connecter(email, pwd, saisi),
                        () -> {
                            statusLabel.setText("");
                            loginButton.setDisable(false);
                        });
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    showError("Echec de connexion : " + e.getMessage());
                    statusLabel.setText("");
                    loginButton.setDisable(false);
                });
            }
        }, "mbsc-login");
        t.setDaemon(true);
        t.start();
    }

    private void showError(String msg) {
        boolean has = msg != null;
        errorLabel.setText(has ? msg : "");
        errorLabel.setVisible(has);
        errorLabel.setManaged(has);
    }
}
