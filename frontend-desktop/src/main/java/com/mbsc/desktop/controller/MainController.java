package com.mbsc.desktop.controller;

import java.io.File;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mbsc.desktop.MainApp;
import com.mbsc.desktop.api.NoteDtos;
import com.mbsc.desktop.model.LocalEcriture;
import com.mbsc.desktop.model.LocalTransaction;
import com.mbsc.desktop.model.SensTransaction;
import com.mbsc.desktop.service.CaisseService;
import com.mbsc.desktop.service.ExcelExporter;
import com.mbsc.desktop.service.PrintService;
import com.mbsc.desktop.service.RecuService;
import com.mbsc.desktop.service.SessionContext;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * Controleur de l'ecran principal de la caisse.
 * Gere le decaissement (note obligatoire, auto-rempli), l'encaissement libre,
 * l'historique et le grand livre.
 */
public class MainController {

    @FXML private Label connStatus;
    @FXML private Label syncBadge;
    @FXML private Label notesBadge;
    @FXML private Label userLabel;
    @FXML private Label feedbackLabel;

    // Champs de recherche
    @FXML private TextField notesSearch;
    @FXML private TextField histoSearch;
    @FXML private TextField glSearch;

    // DatePickers filtres date
    @FXML private DatePicker histoDateDu;
    @FXML private DatePicker histoDateAu;
    @FXML private DatePicker glDateDu;
    @FXML private DatePicker glDateAu;

    // Cards de synthese (dashboard)
    @FXML private Label soldeLabel;
    @FXML private Label notesAttenteLabel;
    @FXML private Label notesPayeesLabel;

    // Champs DECAISSEMENT (readonly, auto-remplis par selection dans notesTable)
    @FXML private TextField noteRefField;
    @FXML private TextField objetField;
    @FXML private TextField compteField;
    @FXML private TextField montantField;

    // Champs ENCAISSEMENT (libres)
    @FXML private TextField encLibelleField;
    @FXML private TextField encCompteField;
    @FXML private TextField encMontantField;

    @FXML private TableView<LocalTransaction> table;
    @FXML private TableColumn<LocalTransaction, String> colSens;

    @FXML private TableView<NoteDtos.NoteAPayer> notesTable;
    @FXML private TableColumn<NoteDtos.NoteAPayer, String> colNotePriorite;
    @FXML private TableColumn<NoteDtos.NoteAPayer, String> colNoteRef;
    @FXML private TableColumn<NoteDtos.NoteAPayer, String> colNoteObjet;
    @FXML private TableColumn<NoteDtos.NoteAPayer, String> colNoteMontant;

    @FXML private TableView<LocalEcriture> grandLivreTable;
    @FXML private TableColumn<LocalEcriture, String> colGlNumero;
    @FXML private TableColumn<LocalEcriture, String> colGlDate;
    @FXML private TableColumn<LocalEcriture, String> colGlCompte;
    @FXML private TableColumn<LocalEcriture, String> colGlLibelle;
    @FXML private TableColumn<LocalEcriture, String> colGlCaissier;
    @FXML private TableColumn<LocalEcriture, String> colGlDebit;
    @FXML private TableColumn<LocalEcriture, String> colGlCredit;

    private final ObservableList<LocalTransaction> data = FXCollections.observableArrayList();
    private final ObservableList<NoteDtos.NoteAPayer> notesData = FXCollections.observableArrayList();
    private final ObservableList<LocalEcriture> grandLivreData = FXCollections.observableArrayList();

    /** Taux de change CDF → USD courant (fixé par l'admin, rechargé avec les notes). */
    private volatile java.math.BigDecimal tauxChange = java.math.BigDecimal.ONE;

    private FilteredList<LocalTransaction> filteredData;
    private FilteredList<NoteDtos.NoteAPayer> filteredNotes;
    private FilteredList<LocalEcriture> filteredGl;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final NumberFormat MONTANT_FMT;
    static {
        MONTANT_FMT = NumberFormat.getNumberInstance(Locale.FRENCH);
        MONTANT_FMT.setMinimumFractionDigits(2);
        MONTANT_FMT.setMaximumFractionDigits(2);
        MONTANT_FMT.setGroupingUsed(true);
    }

    /** Ordre de tri des priorites : HAUTE en premier, sans priorite en dernier. */
    private static final Map<String, Integer> ORDRE_PRIORITE =
        Map.of("HAUTE", 0, "MOYENNE", 1, "BASSE", 2);

    /** Note selectionnee dans la table, utilisee pour le decaissement. */
    private NoteDtos.NoteAPayer noteSelectionnee = null;

    private CaisseService caisseService;
    private final RecuService recuService = new RecuService();

    @FXML
    private void initialize() {
        caisseService = new CaisseService(MainApp.apiClient().mapper());
        userLabel.setText(SessionContext.displayName());

        // ── FilteredLists pour la recherche en direct ─────────────────────
        filteredData  = new FilteredList<>(data, p -> true);
        filteredNotes = new FilteredList<>(notesData, p -> true);
        filteredGl    = new FilteredList<>(grandLivreData, p -> true);

        table.setItems(filteredData);
        notesTable.setItems(filteredNotes);
        grandLivreTable.setItems(filteredGl);

        // Recherche : historique (texte + dates)
        histoSearch.textProperty().addListener((obs, old, val) -> applyHistoFilter());
        histoDateDu.valueProperty().addListener((obs, old, val) -> applyHistoFilter());
        histoDateAu.valueProperty().addListener((obs, old, val) -> applyHistoFilter());

        // Recherche : notes à payer
        notesSearch.textProperty().addListener((obs, old, val) ->
            filteredNotes.setPredicate(n -> {
                if (val == null || val.isBlank()) return true;
                String f = val.toLowerCase();
                return (n.reference() != null && n.reference().toLowerCase().contains(f))
                    || (n.objet()     != null && n.objet().toLowerCase().contains(f))
                    || (n.priorite()  != null && n.priorite().toLowerCase().contains(f));
            }));

        // Recherche : grand livre (texte + dates)
        glSearch.textProperty().addListener((obs, old, val) -> applyGlFilter());
        glDateDu.valueProperty().addListener((obs, old, val) -> applyGlFilter());
        glDateAu.valueProperty().addListener((obs, old, val) -> applyGlFilter());

        // Colonne Sens de l'historique
        colSens.setCellValueFactory(c ->
            new SimpleStringProperty(
                c.getValue().getSens() == SensTransaction.ENCAISSEMENT
                    ? "Encaissement" : "Decaissement"));

        // Table des notes à payer
        colNotePriorite.setCellValueFactory(c ->
            new SimpleStringProperty(c.getValue().priorite() == null ? "-" : c.getValue().priorite()));
        colNotePriorite.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null) { setText(null); setGraphic(null); setStyle(""); return; }
                Label badge = new Label(val);
                String cls = switch (val) {
                    case "HAUTE"   -> "badge-haute";
                    case "MOYENNE" -> "badge-moyenne";
                    case "BASSE"   -> "badge-basse";
                    default        -> "badge-basse";
                };
                badge.getStyleClass().add(cls);
                badge.getStylesheets().add(MainApp.class.getResource("/css/classroom.css").toExternalForm());
                setGraphic(badge); setText(null); setStyle("-fx-alignment: CENTER;");
            }
        });
        colNoteRef.setCellValueFactory(c ->
            new SimpleStringProperty(c.getValue().reference()));
        colNoteObjet.setCellValueFactory(c ->
            new SimpleStringProperty(c.getValue().objet() == null ? "" : c.getValue().objet()));
        colNoteMontant.setCellValueFactory(c -> {
            if (c.getValue().montant() == null) return new SimpleStringProperty("");
            java.math.BigDecimal montant = c.getValue().montant();
            String devise = c.getValue().devise();
            // Si le montant est en CDF (FC), convertir en USD avec le taux du jour
            if ("CDF".equalsIgnoreCase(devise) || "FC".equalsIgnoreCase(devise)) {
                java.math.BigDecimal taux = tauxChange;
                if (taux != null && taux.signum() > 0) {
                    montant = montant.divide(taux, 2, java.math.RoundingMode.HALF_UP);
                }
            }
            return new SimpleStringProperty(MONTANT_FMT.format(montant) + " $");
        });

        // Clic sur une note -> auto-remplissage
        notesTable.getSelectionModel().selectedItemProperty().addListener(
            (obs, ancienne, nouvelle) -> {
                if (nouvelle != null) chargerDansFormulaire(nouvelle);
            });

        // Table du grand livre
        colGlNumero.setCellValueFactory(c -> new SimpleStringProperty(""));
        colGlNumero.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : String.valueOf(getIndex() + 1));
            }
        });
        colGlDate.setCellValueFactory(c -> new SimpleStringProperty(
            c.getValue().getDateEcriture() == null ? ""
                : c.getValue().getDateEcriture().format(DATE_FMT)));
        colGlCompte.setCellValueFactory(c ->
            new SimpleStringProperty(c.getValue().getNumeroCompte()));
        colGlLibelle.setCellValueFactory(c ->
            new SimpleStringProperty(c.getValue().getLibelle()));
        colGlCaissier.setCellValueFactory(c -> {
            LocalTransaction t = c.getValue().getTransaction();
            if (t == null || t.getCaissierEmail() == null) return new SimpleStringProperty("");
            // Afficher le nom complet si disponible (email sinon)
            String email = t.getCaissierEmail();
            int at = email.indexOf('@');
            return new SimpleStringProperty(at > 0 ? email.substring(0, at) : email);
        });
        colGlDebit.setCellValueFactory(c ->
            new SimpleStringProperty(fmtMontant(c.getValue().getDebit())));
        colGlCredit.setCellValueFactory(c ->
            new SimpleStringProperty(fmtMontant(c.getValue().getCredit())));

        // Réseau
        MainApp.networkMonitor().setOnStatusChange(online ->
            Platform.runLater(() -> {
                updateConnStatus(online);
                if (online) chargerNotes();
            }));
        updateConnStatus(MainApp.networkMonitor().isOnline());

        MainApp.syncEngine().setOnSyncComplete(() ->
            Platform.runLater(() -> {
                refreshData();
                chargerNotes();
            }));

        // ── Double-clic sur ligne → popup détail ────────────────────────
        table.setRowFactory(tv -> {
            TableRow<LocalTransaction> row = new TableRow<>();
            row.setOnMouseClicked(evt -> {
                if (evt.getClickCount() == 2 && !row.isEmpty())
                    afficherDetailTransaction(row.getItem());
            });
            return row;
        });

        grandLivreTable.setRowFactory(tv -> {
            TableRow<LocalEcriture> row = new TableRow<>();
            row.setOnMouseClicked(evt -> {
                if (evt.getClickCount() == 2 && !row.isEmpty())
                    afficherDetailEcriture(row.getItem());
            });
            return row;
        });

        refreshData();
        if (MainApp.networkMonitor().isOnline()) {
            chargerNotes();
        }
    }

    // -------------------------------------------------------------------------
    // Decaissement (note de frais obligatoire)
    // -------------------------------------------------------------------------

    @FXML
    private void onPayer() {
        feedback(null, false);
        if (noteSelectionnee == null) {
            feedback("Selectionnez d'abord une note a payer dans le tableau.", true);
            return;
        }
        try {
            String noteRef    = noteSelectionnee.reference();
            String objet      = objetField.getText() == null ? "" : objetField.getText().trim();
            String compte     = compteField.getText() == null ? "6011" : compteField.getText().trim();
            String montantRaw = montantField.getText() == null ? "" : montantField.getText().trim();

            BigDecimal montant = new BigDecimal(montantRaw.replace(" ", "").replace(",", "."));
            if (montant.signum() <= 0) {
                feedback("Le montant doit etre positif.", true);
                return;
            }

            LocalTransaction tx = caisseService.enregistrerPaiement(noteRef, objet, montant, compte);
            feedback("Paiement enregistre : " + tx.getReference()
                + " (recu " + tx.getNumeroRecu() + ").", false);
            clearDecaissementForm();
            refreshData();
            proposerRecu(tx);

            if (MainApp.networkMonitor().isOnline()) lancerSync();

        } catch (NumberFormatException nfe) {
            feedback("Montant invalide.", true);
        } catch (Exception e) {
            feedback("Erreur : " + e.getMessage(), true);
        }
    }

    // -------------------------------------------------------------------------
    // Encaissement (libre)
    // -------------------------------------------------------------------------

    @FXML
    private void onEncaisser() {
        feedback(null, false);
        try {
            String libelle = encLibelleField.getText() == null ? "" : encLibelleField.getText().trim();
            String compte  = encCompteField.getText()  == null ? "" : encCompteField.getText().trim();
            String montantRaw = encMontantField.getText() == null ? "" : encMontantField.getText().trim();

            if (libelle.isEmpty()) { feedback("Le libelle est obligatoire.", true); return; }
            if (compte.isEmpty())  { feedback("Le compte est obligatoire.", true);  return; }

            BigDecimal montant = new BigDecimal(montantRaw.replace(" ", "").replace(",", "."));
            if (montant.signum() <= 0) { feedback("Le montant doit etre positif.", true); return; }

            LocalTransaction tx = caisseService.enregistrerEncaissement(libelle, montant, compte);
            feedback("Encaissement enregistre : " + tx.getReference()
                + " (recu " + tx.getNumeroRecu() + ").", false);
            clearEncaissementForm();
            refreshData();
            proposerRecu(tx);

            if (MainApp.networkMonitor().isOnline()) lancerSync();

        } catch (NumberFormatException nfe) {
            feedback("Montant invalide.", true);
        } catch (Exception e) {
            feedback("Erreur : " + e.getMessage(), true);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Pre-remplit les champs de decaissement a partir d'une note selectionnee. */
    private void chargerDansFormulaire(NoteDtos.NoteAPayer note) {
        noteSelectionnee = note;
        noteRefField.setText(note.reference());
        objetField.setText(note.objet() == null ? "" : note.objet());
        if (note.montant() != null) {
            java.math.BigDecimal montantUsd = note.montant();
            String devise = note.devise();
            // Convertir CDF → USD avec le taux du jour défini par l'admin
            if ("CDF".equalsIgnoreCase(devise) || "FC".equalsIgnoreCase(devise)) {
                java.math.BigDecimal taux = tauxChange;
                if (taux != null && taux.signum() > 0) {
                    montantUsd = montantUsd.divide(taux, 2, java.math.RoundingMode.HALF_UP);
                }
            }
            montantField.setText(montantUsd.toPlainString());
        }
        // Depuis les notes multi-lignes, il n'y a plus de compte unique cote
        // entete : la ventilation exacte (par ligne) n'est visible que sur
        // l'application web. En saisie hors-ligne, le caissier confirme ou
        // ajuste manuellement le compte propose par defaut.
        compteField.setText("6011");
        if (note.nombreLignes() != null && note.nombreLignes() > 1) {
            feedback("Note " + note.reference() + " selectionnee ("
                + note.nombreLignes() + " lignes de depense : verifiez le compte sur l'appli web).", false);
        } else {
            feedback("Note " + note.reference() + " selectionnee.", false);
        }
    }

    private void proposerRecu(LocalTransaction tx) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Enregistrer le recu PDF");
        chooser.setInitialFileName(tx.getNumeroRecu() + ".pdf");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("PDF", "*.pdf"));
        File dest = chooser.showSaveDialog(table.getScene().getWindow());
        if (dest != null) {
            recuService.genererRecu(tx, dest);
            feedback("Recu enregistre : " + dest.getName(), false);
        }
    }

    @FXML
    private void onSync() {
        if (!MainApp.networkMonitor().isOnline()) {
            feedback("Synchronisation impossible : hors-ligne.", true);
            return;
        }
        lancerSync();
    }

    private void lancerSync() {
        Thread t = new Thread(() -> MainApp.syncEngine().synchronize(), "mbsc-manual-sync");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void onRefresh() { refreshData(); }

    @FXML
    private void onRefreshNotes() {
        if (!MainApp.networkMonitor().isOnline()) {
            feedback("Liste des notes indisponible : hors-ligne.", true);
            return;
        }
        chargerNotes();
    }

    /** Charge les notes transmises a la caisse depuis le backend (thread dedie). */
    private void chargerNotes() {
        if (!MainApp.apiClient().isAuthenticated()) return;
        Thread t = new Thread(() -> {
            try {
                // Récupère le taux du jour en même temps que les notes
                try {
                    java.math.BigDecimal taux = MainApp.apiClient().getTauxChange();
                    if (taux != null && taux.signum() > 0) tauxChange = taux;
                } catch (Exception ignored) { /* on garde le taux précédent */ }

                List<NoteDtos.NoteAPayer> notes = MainApp.apiClient().listerNotesAPayer();
                List<NoteDtos.NoteAPayer> triees = notes.stream()
                    .sorted(Comparator.comparingInt(n ->
                        ORDRE_PRIORITE.getOrDefault(n.priorite(), 3)))
                    .toList();
                Platform.runLater(() -> {
                    notesData.setAll(triees);
                    // Force le recalcul des cellules avec le nouveau taux
                    notesTable.refresh();
                    long haute = triees.stream()
                        .filter(n -> "HAUTE".equals(n.priorite())).count();
                    // Badge top bar : total des notes en attente
                    notesBadge.setText(triees.size() + " note" + (triees.size() > 1 ? "s" : ""));
                    // Card : priorité HAUTE uniquement
                    notesAttenteLabel.setText(String.valueOf(haute));
                });
            } catch (Exception e) {
                Platform.runLater(() ->
                    feedback("Impossible de charger les notes : " + e.getMessage(), true));
            }
        }, "mbsc-charger-notes");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void onLogout() {
        SessionContext.clear();
        try { MainApp.showLogin(); } catch (Exception ignored) {}
    }

    private void refreshData() {
        data.setAll(caisseService.historique());
        grandLivreData.setAll(caisseService.grandLivre());
        long pending = caisseService.enAttenteSync();
        syncBadge.setText(pending + " sync");

        // Mise à jour des cards de synthèse (solde local en attendant le backend)
        BigDecimal soldeLocal = caisseService.solde();
        soldeLabel.setText(MONTANT_FMT.format(soldeLocal) + " $");
        // Rafraîchir le badge notes et la card HAUTE si les notes sont déjà chargées
        long haute = notesData.stream().filter(n -> "HAUTE".equals(n.priorite())).count();
        notesAttenteLabel.setText(String.valueOf(haute));
        notesBadge.setText(notesData.size() + " note" + (notesData.size() > 1 ? "s" : ""));
        notesPayeesLabel.setText(String.valueOf(caisseService.notesPayeesAujourdhui()));

        // Si en ligne : récupère le vrai solde depuis le backend (comme l'app web)
        if (MainApp.networkMonitor().isOnline() && MainApp.apiClient().isAuthenticated()) {
            Thread t = new Thread(() -> {
                try {
                    java.math.BigDecimal soldeCdf  = MainApp.apiClient().getSolde571();
                    java.math.BigDecimal taux      = MainApp.apiClient().getTauxChange();
                    if (soldeCdf != null && taux != null && taux.signum() > 0) {
                        java.math.BigDecimal soldeUsd = soldeCdf.divide(taux, 2, java.math.RoundingMode.HALF_UP);
                        Platform.runLater(() -> soldeLabel.setText(MONTANT_FMT.format(soldeUsd) + " $"));
                    } else if (soldeCdf != null) {
                        // taux indisponible : afficher en FC
                        Platform.runLater(() -> soldeLabel.setText(MONTANT_FMT.format(soldeCdf) + " FC"));
                    }
                } catch (Exception e) {
                    // silencieux : on garde le solde local affiché
                }
            }, "mbsc-solde-fetch");
            t.setDaemon(true);
            t.start();
        }
    }

    private static String fmtMontant(BigDecimal v) {
        if (v == null || v.signum() == 0) return "";
        return v.toPlainString();
    }

    private void updateConnStatus(boolean online) {
        connStatus.setText(online ? "En ligne" : "Hors-ligne");
        connStatus.getStyleClass().removeAll("status-online", "status-offline");
        connStatus.getStyleClass().add(online ? "status-online" : "status-offline");
    }

    private void clearDecaissementForm() {
        noteSelectionnee = null;
        noteRefField.clear();
        objetField.clear();
        montantField.clear();
        compteField.clear();
        notesTable.getSelectionModel().clearSelection();
    }

    private void clearEncaissementForm() {
        encLibelleField.clear();
        encCompteField.clear();
        encMontantField.clear();
    }

    private void feedback(String msg, boolean error) {
        feedbackLabel.setText(msg == null ? "" : msg);
        feedbackLabel.getStyleClass().removeAll("feedback-error", "feedback-ok");
        if (msg != null) {
            feedbackLabel.getStyleClass().add(error ? "feedback-error" : "feedback-ok");
        }
    }

    // =========================================================================
    // Filtres combinés (texte + date)
    // =========================================================================

    private void applyHistoFilter() {
        String txt = histoSearch.getText();
        java.time.LocalDate du = histoDateDu.getValue();
        java.time.LocalDate au = histoDateAu.getValue();
        filteredData.setPredicate(tx -> {
            // Filtre texte
            if (txt != null && !txt.isBlank()) {
                String f = txt.toLowerCase();
                boolean matchText = (tx.getReference()     != null && tx.getReference().toLowerCase().contains(f))
                                 || (tx.getNoteReference() != null && tx.getNoteReference().toLowerCase().contains(f))
                                 || (tx.getSens()          != null && tx.getSens().name().toLowerCase().contains(f));
                if (!matchText) return false;
            }
            // Filtre date
            if ((du != null || au != null) && tx.getDateOperation() != null) {
                java.time.LocalDate d = tx.getDateOperation().atZone(ZoneId.systemDefault()).toLocalDate();
                if (du != null && d.isBefore(du)) return false;
                if (au != null && d.isAfter(au))  return false;
            }
            return true;
        });
    }

    private void applyGlFilter() {
        String txt = glSearch.getText();
        java.time.LocalDate du = glDateDu.getValue();
        java.time.LocalDate au = glDateAu.getValue();
        filteredGl.setPredicate(e -> {
            // Filtre texte
            if (txt != null && !txt.isBlank()) {
                String f = txt.toLowerCase();
                String caissier = e.getTransaction() != null && e.getTransaction().getCaissierEmail() != null
                    ? e.getTransaction().getCaissierEmail().toLowerCase() : "";
                boolean matchText = (e.getNumeroCompte() != null && e.getNumeroCompte().toLowerCase().contains(f))
                                 || (e.getLibelle()      != null && e.getLibelle().toLowerCase().contains(f))
                                 || caissier.contains(f);
                if (!matchText) return false;
            }
            // Filtre date
            if ((du != null || au != null) && e.getDateEcriture() != null) {
                if (du != null && e.getDateEcriture().isBefore(du)) return false;
                if (au != null && e.getDateEcriture().isAfter(au))  return false;
            }
            return true;
        });
    }

    // =========================================================================
    // Impression PDF
    // =========================================================================

    @FXML
    private void onPrintHisto() {
        FileChooser ch = new FileChooser();
        ch.setTitle("Enregistrer le document à imprimer");
        ch.setInitialFileName("historique_transactions.pdf");
        ch.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF (*.pdf)", "*.pdf"));
        File dest = ch.showSaveDialog(table.getScene().getWindow());
        if (dest == null) return;
        // Copier les données filtrées avant d'entrer dans le thread
        List<LocalTransaction> snapshot = new java.util.ArrayList<>(filteredData);
        java.time.LocalDate du = histoDateDu.getValue();
        java.time.LocalDate au = histoDateAu.getValue();
        String search = histoSearch.getText();
        feedback("Génération du PDF en cours...", false);
        Thread t = new Thread(() -> {
            try {
                PrintService.imprimerHistorique(snapshot, dest, du, au, search);
                Platform.runLater(() -> feedback("Document prêt : " + dest.getName(), false));
            } catch (Exception ex) {
                Platform.runLater(() -> feedback("Erreur impression : " + ex.getMessage(), true));
            }
        }, "mbsc-print-histo");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void onPrintGl() {
        FileChooser ch = new FileChooser();
        ch.setTitle("Enregistrer le document à imprimer");
        ch.setInitialFileName("grand_livre.pdf");
        ch.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF (*.pdf)", "*.pdf"));
        File dest = ch.showSaveDialog(grandLivreTable.getScene().getWindow());
        if (dest == null) return;
        List<LocalEcriture> snapshot = new java.util.ArrayList<>(filteredGl);
        java.time.LocalDate du = glDateDu.getValue();
        java.time.LocalDate au = glDateAu.getValue();
        String search = glSearch.getText();
        feedback("Génération du PDF en cours...", false);
        Thread t = new Thread(() -> {
            try {
                PrintService.imprimerGrandLivre(snapshot, dest, du, au, search);
                Platform.runLater(() -> feedback("Document prêt : " + dest.getName(), false));
            } catch (Exception ex) {
                Platform.runLater(() -> feedback("Erreur impression : " + ex.getMessage(), true));
            }
        }, "mbsc-print-gl");
        t.setDaemon(true);
        t.start();
    }

    // =========================================================================
    // Export Excel
    // =========================================================================

    @FXML
    private void onExportNotes() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Exporter les notes à payer");
        chooser.setInitialFileName("notes_a_payer.xlsx");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel (*.xlsx)", "*.xlsx"));
        File dest = chooser.showSaveDialog(table.getScene().getWindow());
        if (dest == null) return;
        try {
            ExcelExporter.exporterNotes(new ArrayList<>(filteredNotes), dest);
            feedback("Export réussi : " + dest.getName(), false);
        } catch (Exception ex) {
            feedback("Erreur export : " + ex.getMessage(), true);
        }
    }

    @FXML
    private void onExportHisto() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Exporter l'historique");
        chooser.setInitialFileName("historique_transactions.xlsx");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel (*.xlsx)", "*.xlsx"));
        File dest = chooser.showSaveDialog(table.getScene().getWindow());
        if (dest == null) return;
        try {
            ExcelExporter.exporterHistorique(new ArrayList<>(filteredData), dest);
            feedback("Export réussi : " + dest.getName(), false);
        } catch (Exception ex) {
            feedback("Erreur export : " + ex.getMessage(), true);
        }
    }

    @FXML
    private void onExportGl() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Exporter le grand livre");
        chooser.setInitialFileName("grand_livre.xlsx");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel (*.xlsx)", "*.xlsx"));
        File dest = chooser.showSaveDialog(table.getScene().getWindow());
        if (dest == null) return;
        try {
            ExcelExporter.exporterGrandLivre(new ArrayList<>(filteredGl), dest);
            feedback("Export réussi : " + dest.getName(), false);
        } catch (Exception ex) {
            feedback("Erreur export : " + ex.getMessage(), true);
        }
    }

    // =========================================================================
    // Fenêtres plein écran
    // =========================================================================

    @FXML private void onFullHisto() { ouvrirHistoriqueComplet(); }
    @FXML private void onFullGl()    { ouvrirGrandLivreComplet(); }

    private void ouvrirHistoriqueComplet() {
        Stage stage = new Stage();
        stage.setTitle("Historique complet des transactions — MBSC Finapp");
        stage.setWidth(1150); stage.setHeight(720);

        ObservableList<LocalTransaction> snapshot = FXCollections.observableArrayList(data);
        FilteredList<LocalTransaction> flt = new FilteredList<>(snapshot, p -> true);

        TableView<LocalTransaction> tv = new TableView<>(flt);
        tv.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        VBox.setVgrow(tv, Priority.ALWAYS);

        TableColumn<LocalTransaction, String> cRef = col("Référence",
            c -> new SimpleStringProperty(c.getValue().getReference()), 160);
        TableColumn<LocalTransaction, String> cSens = new TableColumn<>("Type");
        cSens.setPrefWidth(135);
        cSens.setCellValueFactory(c -> new SimpleStringProperty(
            c.getValue().getSens() == SensTransaction.ENCAISSEMENT ? "Encaissement" : "Décaissement"));
        cSens.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty); if (empty || v == null) { setText(null); setStyle(""); return; }
                setText(v);
                setStyle("Encaissement".equals(v)
                    ? "-fx-text-fill:#1b5e20; -fx-font-weight:bold;"
                    : "-fx-text-fill:#b71c1c; -fx-font-weight:bold;");
            }
        });
        TableColumn<LocalTransaction, String> cNote = col("Note de frais",
            c -> new SimpleStringProperty(nvl(c.getValue().getNoteReference())), 145);
        TableColumn<LocalTransaction, String> cMont = col("Montant ($)",
            c -> new SimpleStringProperty(c.getValue().getMontant() != null
                ? MONTANT_FMT.format(c.getValue().getMontant()) + " $" : ""), 130);
        TableColumn<LocalTransaction, String> cDate = col("Date opération",
            c -> new SimpleStringProperty(c.getValue().getDateOperation() != null
                ? c.getValue().getDateOperation().atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) : ""), 160);
        TableColumn<LocalTransaction, String> cRecu = col("N° Reçu",
            c -> new SimpleStringProperty(nvl(c.getValue().getNumeroRecu())), 130);
        TableColumn<LocalTransaction, String> cSync = col("Statut",
            c -> new SimpleStringProperty(c.getValue().isSynced() ? "✓ Sync" : "⏳ Attente"), 100);

        tv.getColumns().addAll(cRef, cSens, cNote, cMont, cDate, cRecu, cSync);
        tv.setRowFactory(t -> {
            TableRow<LocalTransaction> row = new TableRow<>();
            row.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && !row.isEmpty()) afficherDetailTransaction(row.getItem()); });
            return row;
        });

        // Header
        Label titre = styledLabel("Historique des transactions", "white", 18, true);
        Label sous  = styledLabel(snapshot.size() + " transaction(s)  —  Double-clic pour détails", "rgba(255,255,255,0.75)", 11, false);
        VBox titreBox = new VBox(3, titre, sous);

        TextField search = searchField();
        search.textProperty().addListener((obs, o, val) -> flt.setPredicate(tx -> {
            if (val == null || val.isBlank()) return true;
            String f = val.toLowerCase();
            return nvl(tx.getReference()).toLowerCase().contains(f)
                || nvl(tx.getNoteReference()).toLowerCase().contains(f);
        }));

        Button exportBtn = actionBtn("↓ Excel", "#43c878");
        exportBtn.setOnAction(e -> {
            FileChooser ch = new FileChooser();
            ch.setInitialFileName("historique_transactions.xlsx");
            ch.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel (*.xlsx)", "*.xlsx"));
            File dest = ch.showSaveDialog(stage);
            if (dest == null) return;
            try { ExcelExporter.exporterHistorique(new ArrayList<>(flt), dest); } catch (Exception ex) { ex.printStackTrace(); }
        });

        Button fermer = closeBtn();
        fermer.setOnAction(e -> stage.close());

        HBox header = buildHeader("linear-gradient(from 0% 0% to 100% 0%, #0d6e2a 0%, #1e8e3e 55%, #00897b 100%)",
            titreBox, search, exportBtn, fermer);

        VBox center = new VBox(tv);
        center.setPadding(new Insets(10, 14, 14, 14));
        center.setStyle("-fx-background-color:#f5f7fa;");

        BorderPane root = new BorderPane(center);
        root.setTop(header);

        Scene sc = new Scene(root); sc.getStylesheets().add(cssUrl());
        stage.setScene(sc); stage.show();
    }

    private void ouvrirGrandLivreComplet() {
        Stage stage = new Stage();
        stage.setTitle("Grand Livre OHADA — MBSC Finapp");
        stage.setWidth(1250); stage.setHeight(760);

        ObservableList<LocalEcriture> snapshot = FXCollections.observableArrayList(grandLivreData);
        FilteredList<LocalEcriture> flt = new FilteredList<>(snapshot, p -> true);

        TableView<LocalEcriture> tv = new TableView<>(flt);
        tv.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        VBox.setVgrow(tv, Priority.ALWAYS);

        TableColumn<LocalEcriture, String> cNum = new TableColumn<>("N°");
        cNum.setPrefWidth(52);
        cNum.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty ? null : String.valueOf(getIndex() + 1));
            }
        });
        TableColumn<LocalEcriture, String> cDate = col("Date",
            c -> new SimpleStringProperty(c.getValue().getDateEcriture() != null
                ? c.getValue().getDateEcriture().format(DATE_FMT) : ""), 110);
        TableColumn<LocalEcriture, String> cCompte = col("Compte",
            c -> new SimpleStringProperty(nvl(c.getValue().getNumeroCompte())), 100);
        TableColumn<LocalEcriture, String> cLib = col("Libellé",
            c -> new SimpleStringProperty(nvl(c.getValue().getLibelle())), 320);
        TableColumn<LocalEcriture, String> cCaiss = col("Caissier", c -> {
            LocalTransaction t = c.getValue().getTransaction();
            if (t == null || t.getCaissierEmail() == null) return new SimpleStringProperty("");
            String e = t.getCaissierEmail(); int at = e.indexOf('@');
            return new SimpleStringProperty(at > 0 ? e.substring(0, at) : e);
        }, 145);
        TableColumn<LocalEcriture, String> cDeb = col("Débit ($)",
            c -> new SimpleStringProperty(fmtMontant(c.getValue().getDebit())), 135);
        TableColumn<LocalEcriture, String> cCred = col("Crédit ($)",
            c -> new SimpleStringProperty(fmtMontant(c.getValue().getCredit())), 135);

        tv.getColumns().addAll(cNum, cDate, cCompte, cLib, cCaiss, cDeb, cCred);
        tv.setRowFactory(t -> {
            TableRow<LocalEcriture> row = new TableRow<>();
            row.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && !row.isEmpty()) afficherDetailEcriture(row.getItem()); });
            return row;
        });

        Label titre = styledLabel("Grand Livre OHADA", "white", 18, true);
        Label sous  = styledLabel(snapshot.size() + " écriture(s)  —  Double-clic pour détails", "rgba(255,255,255,0.75)", 11, false);
        VBox titreBox = new VBox(3, titre, sous);

        TextField search = searchField();
        search.textProperty().addListener((obs, o, val) -> flt.setPredicate(e -> {
            if (val == null || val.isBlank()) return true;
            String f = val.toLowerCase();
            return nvl(e.getNumeroCompte()).toLowerCase().contains(f)
                || nvl(e.getLibelle()).toLowerCase().contains(f);
        }));

        Button exportBtn = actionBtn("↓ Excel", "#43c878");
        exportBtn.setOnAction(e -> {
            FileChooser ch = new FileChooser();
            ch.setInitialFileName("grand_livre.xlsx");
            ch.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel (*.xlsx)", "*.xlsx"));
            File dest = ch.showSaveDialog(stage);
            if (dest == null) return;
            try { ExcelExporter.exporterGrandLivre(new ArrayList<>(flt), dest); } catch (Exception ex) { ex.printStackTrace(); }
        });

        Button fermer = closeBtn(); fermer.setOnAction(e -> stage.close());

        HBox header = buildHeader("linear-gradient(from 0% 0% to 100% 0%, #0d47a1 0%, #1a73e8 55%, #1565c0 100%)",
            titreBox, search, exportBtn, fermer);

        VBox center = new VBox(tv);
        center.setPadding(new Insets(10, 14, 14, 14));
        center.setStyle("-fx-background-color:#f5f7fa;");

        BorderPane root = new BorderPane(center);
        root.setTop(header);

        Scene sc = new Scene(root); sc.getStylesheets().add(cssUrl());
        stage.setScene(sc); stage.show();
    }

    // =========================================================================
    // Popups détail (double-clic sur ligne)
    // =========================================================================

    private void afficherDetailTransaction(LocalTransaction tx) {
        boolean enc = tx.getSens() == SensTransaction.ENCAISSEMENT;
        String grad = enc
            ? "linear-gradient(from 0% 0% to 100% 0%, #1b5e20 0%, #2e7d32 100%)"
            : "linear-gradient(from 0% 0% to 100% 0%, #b71c1c 0%, #c62828 100%)";
        String btnColor = enc ? "#2e7d32" : "#c62828";

        Stage popup = popupStage("Détail de la transaction", 500);

        Label titreLabel = styledLabel("Détail de la transaction", "white", 16, true);
        Label refLabel   = styledLabel(nvl(tx.getReference()), "rgba(255,255,255,0.8)", 12, false);
        VBox  titreBox   = popupHeader(grad, titreLabel, refLabel);

        String dateStr = tx.getDateOperation() != null
            ? tx.getDateOperation().atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm"))
            : "—";

        GridPane grid = detailGrid(new String[][]{
            {"Référence",      nvl(tx.getReference())},
            {"Type",           enc ? "ENCAISSEMENT" : "DÉCAISSEMENT"},
            {"Montant",        tx.getMontant() != null ? MONTANT_FMT.format(tx.getMontant()) + " $" : "—"},
            {"Note de frais",  nvl2(tx.getNoteReference())},
            {"N° Reçu",        nvl2(tx.getNumeroRecu())},
            {"Caissier",       nvl2(tx.getCaissierEmail())},
            {"Date opération", dateStr},
            {"Statut sync",    tx.isSynced() ? "✓ Synchronisé" : "⏳ En attente de synchronisation"}
        });

        Button fermer = new Button("  Fermer  ");
        fermer.setStyle("-fx-background-color:" + btnColor + "; -fx-text-fill:white; -fx-font-weight:bold; "
            + "-fx-background-radius:8; -fx-padding:8 28 8 28; -fx-cursor:hand;");
        fermer.setOnAction(e -> popup.close());

        HBox btnBox = new HBox(fermer);
        btnBox.setAlignment(Pos.CENTER_RIGHT);
        btnBox.setPadding(new Insets(4, 24, 20, 24));

        VBox root = new VBox(titreBox, grid, btnBox);
        root.setStyle("-fx-background-color:white;");
        Scene sc = new Scene(root); sc.getStylesheets().add(cssUrl());
        popup.setScene(sc); popup.show();
    }

    private void afficherDetailEcriture(LocalEcriture e) {
        Stage popup = popupStage("Écriture comptable OHADA", 500);

        Label titreLabel = styledLabel("Écriture comptable OHADA", "white", 16, true);
        Label compteLabel = styledLabel("Compte " + nvl(e.getNumeroCompte()), "rgba(255,255,255,0.8)", 12, false);
        VBox  titreBox    = popupHeader(
            "linear-gradient(from 0% 0% to 100% 0%, #0d47a1 0%, #1a73e8 100%)",
            titreLabel, compteLabel);

        String caissier = "—", txRef = "—";
        if (e.getTransaction() != null) {
            String email = e.getTransaction().getCaissierEmail();
            if (email != null) { int at = email.indexOf('@'); caissier = at > 0 ? email.substring(0, at) : email; }
            txRef = nvl2(e.getTransaction().getReference());
        }
        boolean isDebit  = e.getDebit()  != null && e.getDebit().signum()  > 0;
        boolean isCredit = e.getCredit() != null && e.getCredit().signum() > 0;

        GridPane grid = detailGrid(new String[][]{
            {"Date écriture", e.getDateEcriture() != null ? e.getDateEcriture().format(DATE_FMT) : "—"},
            {"Compte OHADA",  nvl(e.getNumeroCompte())},
            {"Libellé",       nvl(e.getLibelle())},
            {"Caissier",      caissier},
            {"Transaction",   txRef},
            {"Débit",         isDebit  ? MONTANT_FMT.format(e.getDebit())  + " $" : "—"},
            {"Crédit",        isCredit ? MONTANT_FMT.format(e.getCredit()) + " $" : "—"}
        });

        Button fermer = new Button("  Fermer  ");
        fermer.setStyle("-fx-background-color:#1a73e8; -fx-text-fill:white; -fx-font-weight:bold; "
            + "-fx-background-radius:8; -fx-padding:8 28 8 28; -fx-cursor:hand;");
        fermer.setOnAction(ev -> popup.close());

        HBox btnBox = new HBox(fermer);
        btnBox.setAlignment(Pos.CENTER_RIGHT);
        btnBox.setPadding(new Insets(4, 24, 20, 24));

        VBox root = new VBox(titreBox, grid, btnBox);
        root.setStyle("-fx-background-color:white;");
        Scene sc = new Scene(root); sc.getStylesheets().add(cssUrl());
        popup.setScene(sc); popup.show();
    }

    // =========================================================================
    // Helpers UI factory
    // =========================================================================

    private static <T> TableColumn<T, String> col(String title,
            javafx.util.Callback<TableColumn.CellDataFeatures<T, String>, javafx.beans.value.ObservableValue<String>> factory,
            double width) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setCellValueFactory(factory);
        c.setPrefWidth(width);
        return c;
    }

    private static Label styledLabel(String text, String color, int size, boolean bold) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill:" + color + "; -fx-font-size:" + size + "px;"
            + (bold ? " -fx-font-weight:bold;" : ""));
        return l;
    }

    private static VBox popupHeader(String gradient, Label... children) {
        VBox box = new VBox(3);
        box.getChildren().addAll(children);
        box.setPadding(new Insets(18, 24, 18, 24));
        box.setStyle("-fx-background-color:" + gradient + ";");
        return box;
    }

    private static Stage popupStage(String title, int width) {
        Stage s = new Stage();
        s.setTitle(title);
        s.initModality(Modality.APPLICATION_MODAL);
        s.setWidth(width);
        s.setResizable(false);
        return s;
    }

    private static GridPane detailGrid(String[][] rows) {
        GridPane g = new GridPane();
        g.setHgap(16); g.setVgap(0);
        g.setPadding(new Insets(20, 24, 8, 24));
        for (int i = 0; i < rows.length; i++) {
            Label lbl = new Label(rows[i][0]);
            lbl.setStyle("-fx-text-fill:#757575; -fx-font-size:11px; -fx-font-weight:bold;");
            lbl.setMinWidth(145);
            Label val = new Label(rows[i][1]);
            val.setStyle("-fx-text-fill:#212121; -fx-font-size:13px;");
            val.setWrapText(true);
            // fond alterné
            VBox rowBox = new VBox(lbl, val);
            rowBox.setPadding(new Insets(8, 0, 8, 0));
            rowBox.setStyle(i % 2 == 0 ? "-fx-background-color:#fafafa;" : "-fx-background-color:white;");
            GridPane.setColumnSpan(rowBox, 2);
            g.add(rowBox, 0, i);
        }
        return g;
    }

    private static TextField searchField() {
        TextField tf = new TextField();
        tf.setPromptText("Rechercher...");
        tf.setPrefWidth(210);
        tf.setStyle("-fx-background-radius:20; -fx-padding:6 14 6 14;"
            + "-fx-background-color:rgba(255,255,255,0.18); -fx-text-fill:white;"
            + "-fx-prompt-text-fill:rgba(255,255,255,0.5);");
        return tf;
    }

    private static Button actionBtn(String text, String color) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color:" + color + "; -fx-text-fill:white; -fx-font-weight:bold;"
            + "-fx-background-radius:8; -fx-padding:6 14 6 14; -fx-cursor:hand;");
        return b;
    }

    private static Button closeBtn() {
        Button b = new Button("✕ Fermer");
        b.setStyle("-fx-background-color:rgba(255,255,255,0.22); -fx-text-fill:white;"
            + "-fx-background-radius:8; -fx-padding:6 14 6 14; -fx-cursor:hand;");
        return b;
    }

    private static HBox buildHeader(String gradient, VBox titreBox, javafx.scene.Node... extra) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox box = new HBox(16);
        box.getChildren().add(titreBox);
        box.getChildren().add(spacer);
        for (javafx.scene.Node n : extra) box.getChildren().add(n);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(18, 24, 18, 24));
        box.setStyle("-fx-background-color:" + gradient + ";");
        return box;
    }

    private static String cssUrl() {
        return MainApp.class.getResource("/css/classroom.css").toExternalForm();
    }

    private static String nvl(String s)  { return s != null ? s : ""; }
    private static String nvl2(String s) { return s != null && !s.isBlank() ? s : "—"; }
}
