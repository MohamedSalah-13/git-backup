package com.gitbackup.ui;

import com.gitbackup.backup.BackupLog;
import com.gitbackup.backup.BackupService;
import com.gitbackup.github.GitHubException;
import com.gitbackup.github.TokenStore;
import com.gitbackup.model.AppConfig;
import com.gitbackup.store.AppPaths;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/** The application window: pick repositories, pick a folder, run the backup. */
public class MainApp extends Application {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm", Locale.ROOT);
    private static final String[] LANGUAGES = {"ar", "en"};
    private static final String[] LANGUAGE_NAMES = {"العربية", "English"};

    private final BackupService service = new BackupService(AppPaths.defaults());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "backup-worker");
        thread.setDaemon(true);
        return thread;
    });
    /** Fires a list change when a row is ticked, so the counter follows. */
    private final ObservableList<RepoRow> rows =
            FXCollections.observableArrayList(row -> new Observable[] {row.selectedProperty()});
    private final FilteredList<RepoRow> visibleRows = new FilteredList<>(rows);
    private final AtomicBoolean stopRequested = new AtomicBoolean();

    private Stage stage;
    private AppConfig config;
    private ResourceBundle text;
    private boolean busy;
    private boolean backupRunning;
    private boolean listLoaded;
    private boolean multipleOwners;
    private String accountText = "";
    private String statusText = "";
    private String statusAfterList;
    private boolean devAutoRunDone;

    // Controls are rebuilt when the language changes.
    private TextField destinationField;
    private Spinner<Integer> keepSpinner;
    private Label accountLabel;
    private Label countLabel;
    private Label statusLabel;
    private TextField searchField;
    private CheckBox forceBox;
    private ProgressBar progressBar;
    private TextArea logArea;
    private ComboBox<String> languageBox;
    private final List<Region> lockedWhileBusy = new ArrayList<>();
    private Button runButton;
    private Button saveButton;
    private Button stopButton;

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        try {
            config = service.loadConfig();
        } catch (IOException e) {
            config = new AppConfig();
        }
        text = bundleFor(config.language);
        accountText = t("label.account.none");
        statusText = t("state.loading");

        Scene scene = new Scene(buildRoot(), 1080, 720);
        scene.getStylesheets().add(MainApp.class.getResource("/app.css").toExternalForm());
        applyLanguage(scene);
        stage.setScene(scene);
        stage.setMinWidth(920);
        stage.setMinHeight(560);
        stage.setOnCloseRequest(event -> {
            if (backupRunning && !confirm(t("msg.closeRunning"))) {
                event.consume();
            }
        });
        rows.addListener((ListChangeListener<RepoRow>) change -> updateCount());
        stage.show();
        scene.getRoot().requestFocus(); // otherwise the folder field starts selected
        reloadList();
    }

    @Override
    public void stop() {
        worker.shutdownNow();
    }

    // ---------------------------------------------------------------- layout

    private Parent buildRoot() {
        lockedWhileBusy.clear();

        Label destinationLabel = new Label(t("label.destination"));
        destinationField = new TextField(config.destination);
        destinationField.setEditable(false);
        destinationField.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        HBox.setHgrow(destinationField, Priority.ALWAYS);
        Button browseButton = lock(new Button(t("button.browse")));
        browseButton.setOnAction(event -> chooseDestination());
        Label keepLabel = new Label(t("label.keep"));
        keepSpinner = lock(new Spinner<Integer>(1, 50, Math.min(50, Math.max(1, config.keepVersions))));
        keepSpinner.setPrefWidth(76);
        HBox destinationRow = row(destinationLabel, destinationField, browseButton, gap(10), keepLabel, keepSpinner);

        accountLabel = new Label(accountText);
        accountLabel.getStyleClass().add("muted");
        Button signInButton = lock(new Button(t("button.signIn")));
        signInButton.setOnAction(event -> showSignIn());
        languageBox = lock(new ComboBox<String>(FXCollections.observableArrayList(LANGUAGE_NAMES)));
        languageBox.getSelectionModel().select("en".equals(config.language) ? 1 : 0);
        languageBox.setOnAction(event -> changeLanguage(LANGUAGES[languageBox.getSelectionModel().getSelectedIndex()]));
        HBox accountRow = row(accountLabel, signInButton, spacer(), languageBox);

        Button allButton = lock(new Button(t("button.selectAll")));
        allButton.setOnAction(event -> tickVisible(row -> true));
        Button noneButton = lock(new Button(t("button.selectNone")));
        noneButton.setOnAction(event -> tickVisible(row -> false));
        Button neededButton = lock(new Button(t("button.selectNeeded")));
        neededButton.setOnAction(event -> tickVisible(row -> row.status() != BackupService.Status.CURRENT));
        Button refreshButton = lock(new Button(t("button.refresh")));
        refreshButton.setOnAction(event -> reloadList());
        searchField = new TextField();
        searchField.setPromptText(t("prompt.search"));
        searchField.setPrefWidth(230);
        searchField.textProperty().addListener((observable, before, after) -> applySearch(after));
        countLabel = new Label();
        countLabel.getStyleClass().add("count");
        HBox pickRow = row(allButton, noneButton, neededButton, refreshButton, searchField, spacer(), countLabel);

        TableView<RepoRow> table = buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        runButton = lock(new Button(t("button.run")));
        runButton.getStyleClass().add("primary");
        runButton.setOnAction(event -> runBackup());
        saveButton = lock(new Button(t("button.save")));
        saveButton.setOnAction(event -> {
            if (saveSelection()) {
                setStatus(t("state.saved"));
            }
        });
        stopButton = new Button(t("button.cancel"));
        stopButton.setDisable(true);
        stopButton.setOnAction(event -> {
            stopRequested.set(true);
            stopButton.setDisable(true);
            setStatus(t("state.stopping"));
        });
        forceBox = lock(new CheckBox(t("check.force")));
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(150);
        progressBar.setVisible(false);
        statusLabel = new Label(statusText);
        statusLabel.getStyleClass().add("muted");
        HBox runRow = row(runButton, saveButton, stopButton, gap(6), forceBox, gap(6), progressBar, statusLabel);

        String previousLog = logArea == null ? "" : logArea.getText();
        logArea = new TextArea(previousLog);
        logArea.setEditable(false);
        logArea.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        logArea.getStyleClass().add("log");
        logArea.setPrefRowCount(9);
        logArea.setMinHeight(120);

        VBox root = new VBox(8, destinationRow, accountRow, pickRow, table, runRow, logArea);
        root.setPadding(new Insets(12));
        updateCount();
        applyBusyState();
        return root;
    }

    private TableView<RepoRow> buildTable() {
        TableView<RepoRow> table = new TableView<>();
        table.setEditable(true);

        TableColumn<RepoRow, Boolean> pick = new TableColumn<>();
        pick.setCellValueFactory(cell -> cell.getValue().selectedProperty());
        pick.setCellFactory(CheckBoxTableCell.forTableColumn(pick));
        pick.setEditable(true);
        pick.setSortable(false);
        pick.setPrefWidth(42);

        TableColumn<RepoRow, String> name = column("column.repo", 300,
                row -> multipleOwners ? row.repo().fullName() : row.repo().name(), value -> value, null);
        TableColumn<RepoRow, String> type = column("column.type", 80,
                row -> t(row.repo().isPrivate() ? "type.private" : "type.public"), value -> value, null);
        TableColumn<RepoRow, Long> size = column("column.size", 95,
                row -> row.repo().sizeKb(), MainApp::formatSize, null);
        TableColumn<RepoRow, Instant> pushed = column("column.pushed", 175,
                row -> row.repo().pushedAt(),
                value -> ltr(WHEN.format(value.atZone(ZoneId.systemDefault()))), null);
        TableColumn<RepoRow, String> lastBackup = column("column.backup", 175,
                RepoRow::lastBackup, MainApp::formatLocalTime, null);
        TableColumn<RepoRow, BackupService.Status> status = column("column.status", 160,
                RepoRow::status, value -> t("status." + value.name()),
                value -> switch (value) {
                    case CURRENT -> "status-current";
                    case MISSING -> "status-missing";
                    default -> "status-pending";
                });

        table.getColumns().add(pick);
        table.getColumns().add(name);
        table.getColumns().add(type);
        table.getColumns().add(size);
        table.getColumns().add(pushed);
        table.getColumns().add(lastBackup);
        table.getColumns().add(status);

        SortedList<RepoRow> sorted = new SortedList<>(visibleRows);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        return table;
    }

    /** A read-only column whose cells show {@code format(value)} and may carry a style class. */
    private <T> TableColumn<RepoRow, T> column(String titleKey, double width, Function<RepoRow, T> value,
                                               Function<T, String> format, Function<T, String> styleClass) {
        TableColumn<RepoRow, T> column = new TableColumn<>(t(titleKey));
        column.setPrefWidth(width);
        column.setEditable(false);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("status-current", "status-pending", "status-missing");
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText(format.apply(item));
                if (styleClass != null) {
                    getStyleClass().add(styleClass.apply(item));
                }
            }
        });
        return column;
    }

    private HBox row(javafx.scene.Node... children) {
        HBox box = new HBox(8, children);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private static Region gap(double width) {
        Region region = new Region();
        region.setMinWidth(width);
        return region;
    }

    private <N extends Region> N lock(N control) {
        lockedWhileBusy.add(control);
        return control;
    }

    // ------------------------------------------------------------ list / state

    private void reloadList() {
        if (busy) {
            return;
        }
        setBusy(true, false, t("state.loading"));
        worker.submit(() -> {
            try {
                BackupService.Listing listing = service.list();
                Platform.runLater(() -> showListing(listing));
            } catch (Exception e) {
                Platform.runLater(() -> showListFailure(e));
            }
        });
    }

    private void showListing(BackupService.Listing listing) {
        // Keep ticks the user changed but has not saved yet.
        Map<String, Boolean> previous = new HashMap<>();
        for (RepoRow row : rows) {
            previous.put(row.repo().fullName(), row.isSelected());
        }
        List<RepoRow> fresh = new ArrayList<>();
        java.util.Set<String> owners = new java.util.HashSet<>();
        for (BackupService.RepoStatus status : listing.repos()) {
            RepoRow row = new RepoRow(status);
            Boolean ticked = previous.get(row.repo().fullName());
            if (ticked != null) {
                row.setSelected(ticked);
            }
            owners.add(row.repo().owner());
            fresh.add(row);
        }
        multipleOwners = owners.size() > 1;
        rows.setAll(fresh);
        listLoaded = true;

        accountText = MessageFormat.format(
                t(listing.tokenSource() == TokenStore.Source.SAVED_TOKEN ? "label.account.saved" : "label.account.cli"),
                listing.login());
        accountLabel.setText(accountText);

        String state = t("state.ready");
        if (config.destination.isBlank()) {
            state = t("state.noDestination");
        } else if (!listing.destinationFound()) {
            state = t("state.destinationMissing");
        } else if (statusAfterList != null) {
            state = statusAfterList;
        }
        statusAfterList = null;
        setBusy(false, false, state);
        devHook();
    }

    private void showListFailure(Exception error) {
        boolean signIn = error instanceof GitHubException github
                && (github.kind() == GitHubException.Kind.NOT_SIGNED_IN
                || github.kind() == GitHubException.Kind.UNAUTHORIZED);
        appendLog(String.valueOf(error.getMessage()));
        if (signIn) {
            accountText = t("label.account.none");
            accountLabel.setText(accountText);
        }
        statusAfterList = null;
        setBusy(false, false, t(signIn ? "state.signInNeeded" : "state.listFailed"));
        devHook();
    }

    private void applySearch(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        visibleRows.setPredicate(needle.isEmpty()
                ? null
                : row -> row.repo().fullName().toLowerCase(Locale.ROOT).contains(needle));
    }

    /** Ticks or clears the rows currently shown (the search may hide some). */
    private void tickVisible(Predicate<RepoRow> decide) {
        for (RepoRow row : new ArrayList<>(visibleRows)) {
            row.setSelected(decide.test(row));
        }
    }

    private void updateCount() {
        if (countLabel == null) {
            return;
        }
        long selected = rows.stream().filter(RepoRow::isSelected).count();
        countLabel.setText(MessageFormat.format(t("label.count"), selected, rows.size()));
    }

    private void setStatus(String value) {
        statusText = value;
        statusLabel.setText(value);
    }

    private void setBusy(boolean nowBusy, boolean backup, String status) {
        busy = nowBusy;
        backupRunning = nowBusy && backup;
        setStatus(status);
        applyBusyState();
    }

    private void applyBusyState() {
        for (Region control : lockedWhileBusy) {
            control.setDisable(busy);
        }
        if (!busy && !listLoaded) {
            runButton.setDisable(true);
            saveButton.setDisable(true);
        }
        stopButton.setDisable(!backupRunning || stopRequested.get());
        progressBar.setVisible(backupRunning);
    }

    // --------------------------------------------------------------- actions

    private void chooseDestination() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(t("chooser.title"));
        if (!config.destination.isBlank()) {
            File current = new File(config.destination);
            if (current.isDirectory()) {
                chooser.setInitialDirectory(current);
            }
        }
        File chosen = chooser.showDialog(stage);
        if (chosen == null) {
            return;
        }
        config.destination = chosen.getAbsolutePath();
        destinationField.setText(config.destination);
        if (saveConfig()) {
            reloadList();
        }
    }

    /** Stores the ticked repositories, the folder and the number of versions. */
    private boolean saveSelection() {
        if (config.destination.isBlank()) {
            warn(t("msg.pickDestination"));
            return false;
        }
        List<String> selected = rows.stream().filter(RepoRow::isSelected).map(row -> row.repo().fullName()).toList();
        if (selected.isEmpty()) {
            warn(t("msg.needOne"));
            return false;
        }
        // Everything ticked is stored as an empty list, which means "all repositories",
        // so repositories created later are covered too.
        config.include = selected.size() == rows.size() ? new ArrayList<>() : new ArrayList<>(selected);
        config.keepVersions = keepSpinner.getValue();
        return saveConfig();
    }

    private boolean saveConfig() {
        try {
            service.saveConfig(config);
            return true;
        } catch (IOException e) {
            appendLog("Could not save the settings: " + e.getMessage());
            return false;
        }
    }

    private void runBackup() {
        if (busy || !saveSelection()) {
            return;
        }
        stopRequested.set(false);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        setBusy(true, true, t("state.running"));
        boolean force = forceBox.isSelected();
        BackupLog log = new BackupLog() {
            @Override
            public void log(Level level, String line) {
                Platform.runLater(() -> appendLog(line));
            }

            @Override
            public void progress(int done, int total) {
                Platform.runLater(() -> progressBar.setProgress(total == 0 ? 1 : (double) done / total));
            }
        };
        worker.submit(() -> {
            BackupService.Summary summary = service.run(force, log, stopRequested::get);
            Platform.runLater(() -> {
                statusAfterList = t(switch (summary.exitCode()) {
                    case 0 -> "state.done";
                    case 1 -> "state.doneErrors";
                    default -> "state.failed";
                });
                setBusy(false, false, statusAfterList);
                reloadList();
            });
        });
    }

    private void showSignIn() {
        TokenStore tokens = service.tokens();
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle(t("signIn.title"));
        dialog.setHeaderText(t("signIn.header"));
        dialog.getDialogPane().setNodeOrientation(orientation());
        PasswordField field = new PasswordField();
        field.setPromptText(t("signIn.prompt"));
        field.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        field.setPrefWidth(420);
        dialog.getDialogPane().setContent(field);
        ButtonType save = new ButtonType(t("signIn.save"), ButtonBar.ButtonData.OK_DONE);
        ButtonType remove = new ButtonType(t("signIn.remove"), ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().addAll(save, remove, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(save).disableProperty().bind(field.textProperty().isEmpty());

        Optional<ButtonType> answer = dialog.showAndWait();
        if (answer.isEmpty() || answer.get() == ButtonType.CANCEL) {
            return;
        }
        try {
            if (answer.get() == save) {
                tokens.save(field.getText());
            } else {
                tokens.clear();
            }
        } catch (IOException e) {
            appendLog("Could not update the saved token: " + e.getMessage());
        }
        reloadList();
    }

    private void changeLanguage(String language) {
        if (language.equals(config.language)) {
            return;
        }
        config.language = language;
        saveConfig();
        String oldStatus = statusText;
        text = bundleFor(language);
        // The status and account lines are re-read from the list in the new language.
        statusText = oldStatus;
        Scene scene = stage.getScene();
        scene.setRoot(buildRoot());
        applyLanguage(scene);
        if (!busy) {
            reloadList();
        }
    }

    private void applyLanguage(Scene scene) {
        scene.setNodeOrientation(orientation());
        stage.setTitle(t("app.title"));
    }

    private NodeOrientation orientation() {
        return "ar".equals(config.language) ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT;
    }

    // --------------------------------------------------------------- helpers

    private void appendLog(String line) {
        logArea.appendText(line + System.lineSeparator());
    }

    private void warn(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message, ButtonType.OK);
        alert.initOwner(stage);
        alert.setHeaderText(null);
        alert.setTitle(t("app.title"));
        alert.getDialogPane().setNodeOrientation(orientation());
        alert.showAndWait();
    }

    private boolean confirm(String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.NO);
        alert.initOwner(stage);
        alert.setHeaderText(null);
        alert.setTitle(t("app.title"));
        alert.getDialogPane().setNodeOrientation(orientation());
        return alert.showAndWait().filter(button -> button == ButtonType.YES).isPresent();
    }

    private String t(String key) {
        return text.getString(key);
    }

    private static ResourceBundle bundleFor(String language) {
        return ResourceBundle.getBundle("messages", Locale.forLanguageTag(language),
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    /** Keeps numbers, units and dates in left-to-right order inside a right-to-left window. */
    private static String ltr(String value) {
        return "‪" + value + "‬";
    }

    private static String formatSize(Long kb) {
        return ltr(kb >= 1024
                ? String.format(Locale.ROOT, "%.1f MB", kb / 1024.0)
                : String.format(Locale.ROOT, "%d KB", kb));
    }

    /** yyyy-MM-ddTHH:mm:ss becomes yyyy-MM-dd  HH:mm. */
    private static String formatLocalTime(String iso) {
        if (iso.length() < 16) {
            return iso;
        }
        return ltr(iso.substring(0, 10) + "  " + iso.substring(11, 16));
    }

    // ------------------------------------------------------------- dev hooks

    /**
     * Development aid, inactive unless -Dgitbackup.screenshot=file.png is given: once the list
     * is shown, saves a picture of the window and exits. With -Dgitbackup.autorun=true it
     * first runs the backup of the saved selection.
     */
    private void devHook() {
        String target = System.getProperty("gitbackup.screenshot");
        if (target == null) {
            return;
        }
        if (Boolean.getBoolean("gitbackup.autorun") && !devAutoRunDone && listLoaded) {
            devAutoRunDone = true;
            runBackup();
            return;
        }
        PauseTransition settle = new PauseTransition(Duration.millis(600));
        settle.setOnFinished(event -> {
            try {
                WritableImage image = stage.getScene().snapshot(null);
                PixelReader reader = image.getPixelReader();
                int width = (int) image.getWidth();
                int height = (int) image.getHeight();
                BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        out.setRGB(x, y, reader.getArgb(x, y));
                    }
                }
                ImageIO.write(out, "png", new File(target));
            } catch (IOException | RuntimeException e) {
                System.err.println("Screenshot failed: " + e);
            }
            Platform.exit();
        });
        settle.play();
    }
}
