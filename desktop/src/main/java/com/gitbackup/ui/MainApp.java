package com.gitbackup.ui;

import atlantafx.base.controls.CustomTextField;
import atlantafx.base.controls.Tile;
import atlantafx.base.controls.ToggleSwitch;
import atlantafx.base.theme.Styles;
import com.gitbackup.backup.BackupLog;
import com.gitbackup.backup.BackupService;
import com.gitbackup.backup.PasswordRequiredException;
import com.gitbackup.backup.RestoreService;
import com.gitbackup.backup.RestoreService.BackupVersion;
import com.gitbackup.backup.Schedule;
import com.gitbackup.github.GitHubDeviceFlow;
import com.gitbackup.github.GitHubException;
import com.gitbackup.github.TokenStore;
import com.gitbackup.model.AppConfig;
import com.gitbackup.model.RepoInfo;
import com.gitbackup.store.AppPaths;
import com.gitbackup.store.AutoStart;
import com.gitbackup.store.SingleInstance;
import com.hamza.updater.AppUpdater;
import com.hamza.updater.ReleaseSource;
import com.hamza.updater.fx.UpdatePrompt;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Arrays;
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
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;
import javax.imageio.ImageIO;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.feather.Feather;

/** The application window: pick repositories, pick a folder, run the backup. */
public class MainApp extends Application {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm", Locale.ROOT);
    private static final int MIN_PASSWORD_LENGTH = 8;
    /** Gives Google Drive and the network time to come up after Windows starts. */
    private static final long STARTUP_DELAY_MS = 90_000;
    private static final long RETRY_DELAY_MS = 10 * 60_000;
    private static final String[] LANGUAGES = {"ar", "en"};
    private static final String[] LANGUAGE_NAMES = {"العربية", "English"};

    private final AppPaths paths = AppPaths.defaults();
    private final BackupService service = new BackupService(paths);
    private final RestoreService restoreService = new RestoreService(paths);
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
    private boolean dark;
    /** Shown in the header and in the settings window; both follow these values. */
    private final StringProperty account = new SimpleStringProperty("");
    private final StringProperty githubAccount = new SimpleStringProperty("");
    private final StringProperty gitlabAccount = new SimpleStringProperty("");
    private final StringProperty bitbucketAccount = new SimpleStringProperty("");
    private boolean multipleHosts;
    private final StringProperty destinationText = new SimpleStringProperty("");
    private final BooleanProperty busyProperty = new SimpleBooleanProperty();
    private String statusText = "";
    private String statusAfterList;
    private boolean devAutoRunDone;
    private SingleInstance instance;
    private TrayIconSupport tray;
    private boolean trayHintShown;
    private final long startedAt = System.currentTimeMillis();
    private long autoRetryAfter;

    // Controls are rebuilt when the language changes.
    private Label scheduleChip;
    private Label encryptChip;
    private Label countLabel;
    private Label statusLabel;
    private TextField searchField;
    private TableView<RepoRow> repoTable;
    private CheckBox forceBox;
    private ProgressBar progressBar;
    private TextArea logArea;
    private Dialog<ButtonType> settingsDialog;
    private AppUpdater updater;
    private UpdatePrompt updates;
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
        clearAccounts();
        statusText = t("state.loading");
        String themeOverride = System.getProperty("gitbackup.theme"); // development aid, never saved
        dark = Themes.apply(themeOverride != null ? themeOverride : config.theme);

        // One window per user: a second start only brings the first one back.
        try {
            instance = SingleInstance.acquire(paths, () -> Platform.runLater(this::showWindow));
            if (instance == null) {
                Platform.exit();
                return;
            }
        } catch (IOException e) {
            instance = null; // cannot guard; opening a window is still better than not starting
        }

        // Start with Windows may still point at a copy that was moved or deleted.
        AutoStart.repair();

        Scene scene = new Scene(buildRoot(), 1080, 760);
        scene.getStylesheets().add(css());
        Themes.decorate(scene, dark);
        stage.setScene(scene);
        applyLanguage(scene);
        // "Like Windows" follows a change of the Windows colour mode while the program runs.
        Platform.getPreferences().colorSchemeProperty().addListener((observable, before, after) -> {
            if (Themes.SYSTEM.equals(config.theme) && System.getProperty("gitbackup.theme") == null) {
                applyTheme();
            }
        });
        for (String size : List.of("16", "32", "48", "256")) {
            stage.getIcons().add(new Image(MainApp.class.getResource("/icon-" + size + ".png").toExternalForm()));
        }
        stage.setMinWidth(920);
        stage.setMinHeight(600);

        boolean devRun = System.getProperty("gitbackup.screenshot") != null;
        if (TrayIconSupport.supported() && !devRun) {
            tray = new TrayIconSupport(this::showWindow, this::backupFromTray, this::exitApplication);
            tray.show(trayLabels());
            Platform.setImplicitExit(false); // closing the window keeps the tray icon alive
        }
        stage.setOnCloseRequest(event -> {
            event.consume();
            if (tray != null && tray.active()) {
                stage.hide();
                if (!trayHintShown) {
                    trayHintShown = true;
                    tray.notify(t("app.title"), t("tray.hint"), false);
                }
            } else {
                exitApplication();
            }
        });
        rows.addListener((ListChangeListener<RepoRow>) change -> updateCount());

        boolean startHidden = tray != null && getParameters().getRaw().contains("--tray");
        if (startHidden) {
            // If the tray icon could not be created there would be no way to reach the program.
            PauseTransition check = new PauseTransition(Duration.seconds(4));
            check.setOnFinished(event -> {
                if (!tray.active()) {
                    showWindow();
                }
            });
            check.play();
        } else {
            stage.show();
            scene.getRoot().requestFocus(); // otherwise the folder field starts selected
        }

        // A newer release is announced, downloaded and installed by the shared desktop-updater library.
        // Pre-releases count: every 0.x release of this program is published as one.
        updater = AppUpdater.builder("Git Backup")
                .source(ReleaseSource.github("MohamedSalah-13/git-backup").asset("*-setup.exe")
                        .includePreReleases(true))
                .build();
        // Closing goes through the program's own exit, which asks before it cuts a running backup short.
        updates = UpdatePrompt.create(updater, stage).language(config.language).onExit(this::exitApplication);
        if (!devRun) {
            updates.start();
        }

        Timeline clock = new Timeline(new KeyFrame(Duration.seconds(30), event -> checkSchedule()));
        clock.setCycleCount(Timeline.INDEFINITE);
        clock.play();
        reloadList();
    }

    @Override
    public void stop() {
        worker.shutdownNow();
        if (tray != null) {
            tray.remove();
        }
        if (instance != null) {
            instance.close();
        }
        // The AWT thread behind the tray icon would otherwise keep the process alive.
        System.exit(0);
    }

    private void showWindow() {
        if (!stage.isShowing()) {
            stage.show();
        }
        stage.setIconified(false);
        stage.toFront();
        stage.requestFocus();
    }

    private void exitApplication() {
        if (backupRunning) {
            showWindow();
            if (!confirm(t("msg.closeRunning"))) {
                return;
            }
        }
        Platform.exit();
    }

    private TrayIconSupport.Labels trayLabels() {
        return new TrayIconSupport.Labels(t("app.title"), t("tray.open"), t("tray.backup"), t("tray.exit"));
    }

    private void backupFromTray() {
        if (busy || config.destination.isBlank()) {
            showWindow();
            return;
        }
        startBackup("tray", false);
    }

    // -------------------------------------------------------------- schedule

    /**
     * Called every 30 seconds: starts today's automatic backup when its time has come, and
     * otherwise the regular check of the backup files when it is due.
     */
    private void checkSchedule() {
        long now = System.currentTimeMillis();
        if (busy || config.destination.isBlank() || now - startedAt < STARTUP_DELAY_MS || now < autoRetryAfter) {
            return;
        }
        if (Schedule.isDue(config.scheduleEnabled, config.scheduleTime, config.lastAutoRun, LocalDateTime.now())) {
            startBackup("scheduled", false);
        } else if (Schedule.isCheckDue(config.verifyEnabled, config.verifyEveryDays, config.lastVerify,
                LocalDate.now())) {
            startCheck(true);
        }
    }

    private void afterScheduledRun(BackupService.Summary summary, String startedOn) {
        if (summary.started()) {
            config.lastAutoRun = startedOn;
            saveConfig();
        } else {
            // For example Google Drive was not running yet: try again later, not every 30 seconds.
            autoRetryAfter = System.currentTimeMillis() + RETRY_DELAY_MS;
        }
    }

    // --------------------------------------------------------- notifications

    private void notifyBackup(BackupService.Summary summary, boolean background) {
        if (!summary.started()) {
            notifyUser(t("tray.failedStart"), true, background);
        } else if (summary.failed() > 0) {
            notifyUser(MessageFormat.format(t("notify.failed"), summary.failed()), true, background);
        } else {
            notifyUser(MessageFormat.format(t("notify.done"), summary.backedUp(), summary.unchanged()), false,
                    background);
        }
    }

    private void notifyCheck(BackupService.CheckSummary summary, boolean background) {
        if (!summary.started()) {
            notifyUser(t("notify.checkNotStarted"), true, background);
        } else if (summary.damaged() > 0) {
            notifyUser(MessageFormat.format(t("notify.checkDamaged"), summary.damaged()), true, background);
        } else {
            notifyUser(MessageFormat.format(t("notify.checkOk"), summary.ok()), false, background);
        }
    }

    /**
     * A Windows notification next to the clock, as the settings allow. Runs the user started
     * while looking at the window need none.
     */
    private void notifyUser(String message, boolean problem, boolean background) {
        if (tray == null || "never".equals(config.notify) || ("problems".equals(config.notify) && !problem)) {
            return;
        }
        if (!background && stage.isShowing() && stage.isFocused()) {
            return;
        }
        tray.notify(t("app.title"), message, problem);
    }

    private void saveSchedule(boolean enabled, String hour, String minute) {
        config.scheduleEnabled = enabled;
        config.scheduleTime = hour + ":" + minute;
        if (saveConfig()) {
            refreshHeader();
            setStatus(config.scheduleEnabled
                    ? MessageFormat.format(t("state.scheduleOn"), ltr(config.scheduleTime))
                    : t("state.scheduleOff"));
        }
    }

    private void toggleAutoStart(ToggleSwitch toggle) {
        if (toggle.isSelected() == AutoStart.isEnabled()) {
            return;
        }
        try {
            AutoStart.setEnabled(toggle.isSelected());
        } catch (RuntimeException e) {
            appendLog("Could not change start with Windows: " + e.getMessage());
            toggle.setSelected(AutoStart.isEnabled());
        }
    }

    // ---------------------------------------------------------------- layout

    private Parent buildRoot() {
        lockedWhileBusy.clear();

        Button allButton = lock(Icons.button(t("button.selectAll"), Feather.CHECK_SQUARE));
        allButton.setOnAction(event -> tickVisible(row -> true));
        Button noneButton = lock(Icons.button(t("button.selectNone"), Feather.SQUARE));
        noneButton.setOnAction(event -> tickVisible(row -> false));
        Button neededButton = lock(Icons.button(t("button.selectNeeded"), Feather.FILTER));
        neededButton.setOnAction(event -> tickVisible(row -> row.status() != BackupService.Status.CURRENT));
        Button refreshButton = lock(Icons.button(t("button.refresh"), Feather.REFRESH_CW));
        refreshButton.setOnAction(event -> reloadList());
        CustomTextField search = new CustomTextField();
        search.setLeft(Icons.icon(Feather.SEARCH));
        searchField = search;
        searchField.setPromptText(t("prompt.search"));
        searchField.setPrefWidth(240);
        searchField.textProperty().addListener((observable, before, after) -> applySearch(after));
        countLabel = new Label();
        countLabel.getStyleClass().add("count");
        HBox pickRow = row(allButton, noneButton, neededButton, refreshButton, gap(6), searchField, spacer(),
                countLabel);

        repoTable = buildTable();
        TableView<RepoRow> table = repoTable;
        VBox.setVgrow(table, Priority.ALWAYS);

        runButton = lock(Icons.button(t("button.run"), Feather.PLAY));
        runButton.getStyleClass().add(Styles.ACCENT);
        runButton.setOnAction(event -> runBackup());
        saveButton = lock(Icons.button(t("button.save"), Feather.SAVE));
        saveButton.setOnAction(event -> {
            if (saveSelection()) {
                setStatus(t("state.saved"));
            }
        });
        stopButton = Icons.button(t("button.cancel"), Feather.STOP_CIRCLE);
        stopButton.getStyleClass().add(Styles.DANGER);
        stopButton.setDisable(true);
        stopButton.setOnAction(event -> {
            stopRequested.set(true);
            stopButton.setDisable(true);
            setStatus(t("state.stopping"));
        });
        Button restoreButton = lock(Icons.button(t("button.restore"), Feather.ROTATE_CCW));
        restoreButton.setOnAction(event -> showRestore());
        forceBox = lock(new CheckBox(t("check.force")));
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(150);
        progressBar.setVisible(false);
        statusLabel = new Label(statusText);
        statusLabel.getStyleClass().add("muted");
        HBox runRow = row(runButton, saveButton, stopButton, restoreButton, gap(6), forceBox, gap(6),
                progressBar, statusLabel);
        statusLabel.setMinWidth(0);

        String previousLog = logArea == null ? "" : logArea.getText();
        logArea = new TextArea(previousLog);
        logArea.setEditable(false);
        logArea.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        logArea.getStyleClass().add("log");
        logArea.setPrefRowCount(8);
        logArea.setMinHeight(110);

        VBox body = new VBox(10, pickRow, table, runRow, logArea);
        body.setPadding(new Insets(12));
        BorderPane root = new BorderPane(body);
        root.setTop(buildHeader());
        refreshHeader();
        updateCount();
        applyBusyState();
        return root;
    }

    /** The bar at the top: who is signed in, where backups go, and the way into the settings. */
    private Parent buildHeader() {
        Label accountLabel = Icons.with(new Label(), Feather.GITHUB);
        accountLabel.textProperty().bind(account);
        Label destinationLabel = Icons.with(new Label(), Feather.FOLDER);
        destinationLabel.textProperty().bind(destinationText);
        destinationLabel.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        destinationLabel.setMinWidth(80);
        HBox.setHgrow(destinationLabel, Priority.SOMETIMES);
        scheduleChip = Icons.with(new Label(), Feather.CLOCK);
        encryptChip = Icons.with(new Label(), Feather.LOCK);
        for (Label label : List.of(accountLabel, destinationLabel, scheduleChip, encryptChip)) {
            label.getStyleClass().add("header-item");
        }

        accountLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        // The full text when the window is too narrow for it.
        for (Label label : List.of(accountLabel, destinationLabel)) {
            javafx.scene.control.Tooltip tip = new javafx.scene.control.Tooltip();
            tip.textProperty().bind(label.textProperty());
            label.setTooltip(tip);
        }
        accountLabel.setMinWidth(120);
        accountLabel.setMaxWidth(420);
        destinationLabel.setPrefWidth(280);
        destinationLabel.setMaxWidth(Double.MAX_VALUE);

        Button historyButton = Icons.button(t("button.history"), Feather.LIST);
        historyButton.setOnAction(event -> showHistory());
        Button settingsButton = Icons.button(t("button.settings"), Feather.SETTINGS);
        settingsButton.setOnAction(event -> showSettings());

        HBox bar = row(accountLabel, divider(), destinationLabel, divider(), scheduleChip, divider(), encryptChip,
                spacer(), historyButton, settingsButton);
        bar.setSpacing(10);
        bar.getStyleClass().add("header-bar");
        return bar;
    }

    /** Re-reads the folder, the schedule and the encryption state shown in the header. */
    private void refreshHeader() {
        destinationText.set(config.destination.isBlank() ? t("header.noDestination") : ltr(config.destination));
        if (scheduleChip == null) {
            return;
        }
        scheduleChip.setText(config.scheduleEnabled
                ? MessageFormat.format(t("header.scheduleOn"), ltr(config.scheduleTime))
                : t("header.scheduleOff"));
        encryptChip.setText(t(config.encrypt ? "header.encrypted" : "header.notEncrypted"));
        encryptChip.setGraphic(Icons.icon(config.encrypt ? Feather.LOCK : Feather.UNLOCK));
    }

    private static Node divider() {
        Separator separator = new Separator(javafx.geometry.Orientation.VERTICAL);
        separator.setPrefHeight(18);
        return separator;
    }

    // -------------------------------------------------------------- settings

    /**
     * Everything that used to fill the top of the window: folder, versions, encryption, GitHub
     * account, schedule, language and theme. Changes take effect at once, as before.
     */
    private void showSettings() {
        if (settingsDialog != null && settingsDialog.isShowing()) {
            ((Stage) settingsDialog.getDialogPane().getScene().getWindow()).toFront();
            return;
        }
        Dialog<ButtonType> dialog = new Dialog<>();
        prepare(dialog, t("settings.title"));
        settingsDialog = dialog;
        dialog.setResizable(true);

        // Backup
        Button browse = Icons.button(t("button.browse"), Feather.FOLDER);
        browse.disableProperty().bind(busyProperty);
        browse.setOnAction(event -> chooseDestination());
        Tile folderTile = tile(t("settings.folder"), null, Feather.HARD_DRIVE, browse);
        folderTile.descriptionProperty().bind(destinationText);

        Spinner<Integer> keep = new Spinner<>(1, 50, Math.min(50, Math.max(1, config.keepVersions)));
        keep.setPrefWidth(90);
        keep.disableProperty().bind(busyProperty);
        keep.valueProperty().addListener((observable, before, after) -> {
            config.keepVersions = after;
            saveConfig();
        });
        Tile keepTile = tile(t("settings.keep"), t("settings.keep.desc"), Feather.LAYERS, keep);

        ToggleSwitch encrypt = new ToggleSwitch();
        encrypt.setSelected(config.encrypt);
        encrypt.disableProperty().bind(busyProperty);
        encrypt.selectedProperty().addListener((observable, before, after) -> toggleEncryption(encrypt));
        Button password = Icons.button(t("button.password"), Feather.KEY);
        password.disableProperty().bind(busyProperty);
        password.setOnAction(event -> showPasswordDialog());
        Tile encryptTile = tile(t("check.encrypt"), t("settings.encrypt.desc"), Feather.SHIELD,
                actions(password, encrypt));

        CheckBox issues = extraBox("extras.issues", config.backupIssues, value -> config.backupIssues = value);
        CheckBox wiki = extraBox("extras.wiki", config.backupWiki, value -> config.backupWiki = value);
        CheckBox releases = extraBox("extras.releases", config.backupReleases, value -> config.backupReleases = value);
        CheckBox assets = extraBox("extras.assets", config.backupReleaseAssets,
                value -> config.backupReleaseAssets = value);
        assets.disableProperty().unbind();
        assets.disableProperty().bind(busyProperty.or(releases.selectedProperty().not()));
        GridPane extrasGrid = new GridPane();
        extrasGrid.setHgap(14);
        extrasGrid.setVgap(6);
        extrasGrid.addRow(0, issues, wiki);
        extrasGrid.addRow(1, releases, assets);
        Tile extrasTile = tile(t("settings.extras"), t("settings.extras.desc"), Feather.PACKAGE, extrasGrid);

        // Accounts
        Button browserSignIn = Icons.button(t("button.browserSignIn"), Feather.LOG_IN);
        browserSignIn.disableProperty().bind(busyProperty);
        browserSignIn.setOnAction(event -> showBrowserSignIn());
        Button signIn = Icons.button(t("button.token"), Feather.KEY);
        signIn.disableProperty().bind(busyProperty);
        signIn.setOnAction(event -> showSignIn());
        Tile accountTile = tile(null, t("settings.account.desc"), Feather.GITHUB, actions(browserSignIn, signIn));
        accountTile.titleProperty().bind(githubAccount);

        Button gitlab = Icons.button(t("button.connect"), Feather.LINK);
        gitlab.disableProperty().bind(busyProperty);
        gitlab.setOnAction(event -> showHostSignIn(RepoInfo.GITLAB));
        Tile gitlabTile = tile(null, t("settings.gitlab.desc"), Feather.GITLAB, gitlab);
        gitlabTile.titleProperty().bind(gitlabAccount);

        Button bitbucket = Icons.button(t("button.connect"), Feather.LINK);
        bitbucket.disableProperty().bind(busyProperty);
        bitbucket.setOnAction(event -> showHostSignIn(RepoInfo.BITBUCKET));
        Tile bitbucketTile = tile(null, t("settings.bitbucket.desc"), Feather.BOX, bitbucket);
        bitbucketTile.titleProperty().bind(bitbucketAccount);

        ToggleSwitch orgs = new ToggleSwitch();
        orgs.setSelected(config.includeOrgs);
        orgs.disableProperty().bind(busyProperty);
        orgs.selectedProperty().addListener((observable, before, after) -> {
            config.includeOrgs = after;
            if (saveConfig()) {
                reloadList();
            }
        });
        Tile orgsTile = tile(t("settings.orgs"), t("settings.orgs.desc"), Feather.USERS, orgs);

        // Checks and notifications
        ToggleSwitch verify = new ToggleSwitch();
        verify.setSelected(config.verifyEnabled);
        verify.selectedProperty().addListener((observable, before, after) -> {
            config.verifyEnabled = after;
            saveConfig();
        });
        Button checkNow = Icons.button(t("button.checkNow"), Feather.PLAY);
        checkNow.disableProperty().bind(busyProperty);
        checkNow.setOnAction(event -> startCheck(false));
        String lastCheck = config.lastVerify.isBlank() ? t("settings.verify.never") : ltr(config.lastVerify);
        Tile verifyTile = tile(t("settings.verify"),
                MessageFormat.format(t("settings.verify.desc"), config.verifyEveryDays, lastCheck),
                Feather.CHECK_SQUARE, actions(checkNow, verify));

        ComboBox<String> notify = new ComboBox<>();
        List<String> notifyModes = List.of("always", "problems", "never");
        for (String mode : notifyModes) {
            notify.getItems().add(t("notify.mode." + mode));
        }
        notify.getSelectionModel().select(Math.max(0, notifyModes.indexOf(config.notify)));
        notify.setOnAction(event -> {
            config.notify = notifyModes.get(notify.getSelectionModel().getSelectedIndex());
            saveConfig();
        });
        Tile notifyTile = tile(t("settings.notify"),
                t(tray != null ? "settings.notify.desc" : "settings.notify.noTray"), Feather.BELL, notify);

        // Schedule
        LocalTime time = Schedule.parseTime(config.scheduleTime);
        if (time == null) {
            time = LocalTime.of(21, 0);
        }
        ComboBox<String> hourBox = new ComboBox<>();
        for (int hour = 0; hour < 24; hour++) {
            hourBox.getItems().add(String.format(Locale.ROOT, "%02d", hour));
        }
        ComboBox<String> minuteBox = new ComboBox<>();
        for (int minute = 0; minute < 60; minute += 5) {
            minuteBox.getItems().add(String.format(Locale.ROOT, "%02d", minute));
        }
        String minute = String.format(Locale.ROOT, "%02d", time.getMinute());
        if (!minuteBox.getItems().contains(minute)) {
            minuteBox.getItems().add(minute);
        }
        hourBox.setValue(String.format(Locale.ROOT, "%02d", time.getHour()));
        minuteBox.setValue(minute);
        HBox timeBox = new HBox(4, hourBox, new Label(":"), minuteBox);
        timeBox.setAlignment(Pos.CENTER_LEFT);
        timeBox.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        ToggleSwitch schedule = new ToggleSwitch();
        schedule.setSelected(config.scheduleEnabled);
        timeBox.disableProperty().bind(schedule.selectedProperty().not());
        Runnable scheduleChanged = () -> saveSchedule(schedule.isSelected(), hourBox.getValue(), minuteBox.getValue());
        schedule.selectedProperty().addListener((observable, before, after) -> scheduleChanged.run());
        hourBox.setOnAction(event -> scheduleChanged.run());
        minuteBox.setOnAction(event -> scheduleChanged.run());
        Tile scheduleTile = tile(t("check.schedule"), t("label.scheduleHint"), Feather.CLOCK,
                actions(timeBox, schedule));

        ToggleSwitch autoStart = new ToggleSwitch();
        autoStart.setSelected(AutoStart.isEnabled());
        if (AutoStart.available()) {
            autoStart.selectedProperty().addListener((observable, before, after) -> toggleAutoStart(autoStart));
        } else {
            autoStart.setDisable(true);
        }
        Tile autoStartTile = tile(t("check.autostart"),
                t(AutoStart.available() ? "settings.autostart.desc" : "label.scheduleHintDev"), Feather.POWER,
                autoStart);

        // Appearance
        ComboBox<String> language = new ComboBox<>(FXCollections.observableArrayList(LANGUAGE_NAMES));
        language.getSelectionModel().select(Math.max(0, Arrays.asList(LANGUAGES).indexOf(config.language)));
        language.disableProperty().bind(busyProperty);
        language.setOnAction(event -> {
            String chosen = LANGUAGES[language.getSelectionModel().getSelectedIndex()];
            if (!chosen.equals(config.language)) {
                // The settings window is rebuilt in the new language too.
                dialog.close();
                changeLanguage(chosen);
                showSettings();
            }
        });
        Tile languageTile = tile(t("settings.language"), null, Feather.GLOBE, language);

        ComboBox<String> theme = new ComboBox<>();
        for (String choice : Themes.CHOICES) {
            theme.getItems().add(t("theme." + choice));
        }
        theme.getSelectionModel().select(Math.max(0, Arrays.asList(Themes.CHOICES).indexOf(config.theme)));
        theme.setOnAction(event -> {
            config.theme = Themes.CHOICES[theme.getSelectionModel().getSelectedIndex()];
            saveConfig();
            applyTheme();
        });
        Tile themeTile = tile(t("settings.theme"), null, Feather.MOON, theme);

        // Updates
        Button checkUpdates = Icons.button(t("button.checkUpdates"), Feather.DOWNLOAD_CLOUD);
        checkUpdates.setOnAction(event -> updates.checkNow());
        String installed = updater.currentVersion().map(version -> ltr(version.toString()))
                .orElse(t("settings.updates.dev"));
        Tile updatesTile = tile(t("settings.updates"),
                MessageFormat.format(t("settings.updates.desc"), installed), Feather.REFRESH_CW, checkUpdates);

        VBox content = new VBox(14,
                section(t("settings.section.backup"), folderTile, keepTile, encryptTile, extrasTile),
                section(t("settings.section.account"), accountTile, gitlabTile, bitbucketTile, orgsTile),
                section(t("settings.section.schedule"), scheduleTile, autoStartTile),
                section(t("settings.section.checks"), verifyTile, notifyTile),
                section(t("settings.section.appearance"), languageTile, themeTile),
                section(t("settings.section.updates"), updatesTile));
        content.setPadding(new Insets(4));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(Math.min(640, Math.max(360, stage.getHeight() - 120)));
        scroll.getStyleClass().add("settings-scroll");

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().add("settings-pane");
        pane.setPrefWidth(720);
        pane.setContent(scroll);
        pane.getButtonTypes().add(new ButtonType(t("settings.close"), ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.setOnHidden(event -> {
            if (settingsDialog == dialog) {
                settingsDialog = null;
            }
        });
        dialog.show();
    }

    /** A tick box for one kind of extra data; changes are saved at once. */
    private CheckBox extraBox(String key, boolean value, java.util.function.Consumer<Boolean> store) {
        CheckBox box = new CheckBox(t(key));
        box.setSelected(value);
        box.disableProperty().bind(busyProperty);
        box.selectedProperty().addListener((observable, before, after) -> {
            store.accept(after);
            saveConfig();
        });
        return box;
    }

    private static Tile tile(String title, String description, Ikon ikon, Node action) {
        Tile tile = new Tile(title, description);
        tile.setGraphic(Icons.icon(ikon, 20));
        tile.setAction(action);
        return tile;
    }

    private static HBox actions(Node... nodes) {
        HBox box = new HBox(10, nodes);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** A titled group of tiles with thin lines between them. */
    private static VBox section(String title, Tile... tiles) {
        Label header = new Label(title);
        header.getStyleClass().addAll(Styles.TITLE_4, "settings-header");
        VBox group = new VBox();
        group.getStyleClass().add("settings-group");
        for (int i = 0; i < tiles.length; i++) {
            if (i > 0) {
                group.getChildren().add(new Separator());
            }
            group.getChildren().add(tiles[i]);
        }
        return new VBox(8, header, group);
    }

    private void applyTheme() {
        dark = Themes.apply(config.theme);
        Themes.decorate(stage.getScene(), dark);
        if (settingsDialog != null) {
            Themes.decorate(settingsDialog.getDialogPane().getScene(), dark);
        }
    }

    private TableView<RepoRow> buildTable() {
        TableView<RepoRow> table = new TableView<>();
        table.setEditable(true);
        table.getStyleClass().add(Styles.STRIPED);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        TableColumn<RepoRow, Boolean> pick = new TableColumn<>();
        pick.setCellValueFactory(cell -> cell.getValue().selectedProperty());
        pick.setCellFactory(CheckBoxTableCell.forTableColumn(pick));
        pick.setEditable(true);
        pick.setSortable(false);
        pick.setPrefWidth(42);
        pick.setMinWidth(42);
        pick.setMaxWidth(42);

        Function<RepoInfo, String> shownName = repo -> multipleOwners || multipleHosts ? repo.fullName() : repo.name();
        TableColumn<RepoRow, RepoInfo> name = column("column.repo", 300, RepoRow::repo, shownName, null,
                repo -> multipleHosts ? hostIcon(repo.host()) : null);
        name.setComparator(Comparator.comparing(shownName, String.CASE_INSENSITIVE_ORDER));
        TableColumn<RepoRow, Boolean> type = column("column.type", 90,
                row -> row.repo().isPrivate(), value -> t(value ? "type.private" : "type.public"), null,
                value -> value ? Feather.LOCK : Feather.GLOBE);
        TableColumn<RepoRow, Long> size = column("column.size", 95,
                row -> row.repo().sizeKb(), MainApp::formatSize, null, null);
        TableColumn<RepoRow, Instant> pushed = column("column.pushed", 175,
                row -> row.repo().pushedAt(),
                value -> ltr(WHEN.format(value.atZone(ZoneId.systemDefault()))), null, null);
        TableColumn<RepoRow, String> lastBackup = column("column.backup", 175,
                RepoRow::lastBackup, MainApp::formatLocalTime, null, null);
        TableColumn<RepoRow, BackupService.Status> status = column("column.status", 170,
                RepoRow::status, value -> t("status." + value.name()),
                value -> switch (value) {
                    case CURRENT -> "status-current";
                    case MISSING -> "status-missing";
                    case NEVER -> "status-never";
                    default -> "status-pending";
                },
                value -> switch (value) {
                    case CURRENT -> Feather.CHECK_CIRCLE;
                    case MISSING -> Feather.ALERT_TRIANGLE;
                    case NEVER -> Feather.CIRCLE;
                    default -> Feather.REFRESH_CW;
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

    /**
     * A read-only column whose cells show {@code format(value)}, and may carry a style class
     * and an icon.
     */
    private <T> TableColumn<RepoRow, T> column(String titleKey, double width, Function<RepoRow, T> value,
                                               Function<T, String> format, Function<T, String> styleClass,
                                               Function<T, Ikon> icon) {
        TableColumn<RepoRow, T> column = new TableColumn<>(t(titleKey));
        column.setPrefWidth(width);
        column.setEditable(false);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("status-current", "status-pending", "status-missing", "status-never");
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(format.apply(item));
                Ikon ikon = icon == null ? null : icon.apply(item);
                setGraphic(ikon == null ? null : Icons.icon(ikon, 14));
                setGraphicTextGap(6);
                if (styleClass != null) {
                    getStyleClass().add(styleClass.apply(item));
                }
            }
        });
        return column;
    }

    /** A row of controls; buttons, tick boxes and chips keep their full text, labels may shorten. */
    private HBox row(Node... children) {
        for (Node child : children) {
            if (child instanceof javafx.scene.control.ButtonBase || child == scheduleChip || child == encryptChip) {
                ((Region) child).setMinWidth(Region.USE_PREF_SIZE);
            }
        }
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
            previous.put(row.repo().key(), row.isSelected());
        }
        List<RepoRow> fresh = new ArrayList<>();
        java.util.Set<String> owners = new java.util.HashSet<>();
        java.util.Set<String> hosts = new java.util.HashSet<>();
        for (BackupService.RepoStatus status : listing.repos()) {
            RepoRow row = new RepoRow(status);
            Boolean ticked = previous.get(row.repo().key());
            if (ticked != null) {
                row.setSelected(ticked);
            }
            owners.add(row.repo().owner());
            hosts.add(row.repo().host());
            fresh.add(row);
        }
        multipleOwners = owners.size() > 1;
        multipleHosts = hosts.size() > 1;
        rows.setAll(fresh);
        listLoaded = true;
        showAccounts(listing);

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
            clearAccounts();
        }
        statusAfterList = null;
        setBusy(false, false, t(signIn ? "state.signInNeeded" : "state.listFailed"));
        devHook();
    }

    private void applySearch(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        visibleRows.setPredicate(needle.isEmpty()
                ? null
                : row -> row.repo().key().toLowerCase(Locale.ROOT).contains(needle));
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
        busyProperty.set(nowBusy);
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
        File chosen = chooser.showDialog(owner());
        if (chosen == null) {
            return;
        }
        config.destination = chosen.getAbsolutePath();
        refreshHeader();
        if (saveConfig()) {
            reloadList();
        }
    }

    /** Stores the ticked repositories, the folder and the number of versions. */
    private boolean saveSelection() {
        if (config.destination.isBlank()) {
            warn(t("msg.pickDestination"));
            showSettings();
            return false;
        }
        List<String> selected = rows.stream().filter(RepoRow::isSelected).map(row -> row.repo().key()).toList();
        if (selected.isEmpty()) {
            warn(t("msg.needOne"));
            return false;
        }
        // Everything ticked is stored as an empty list, which means "all repositories",
        // so repositories created later are covered too.
        config.include = selected.size() == rows.size() ? new ArrayList<>() : new ArrayList<>(selected);
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
        startBackup("manual", forceBox.isSelected());
    }

    /**
     * Backs up the saved selection in the background. A scheduled run never saves what is
     * ticked in the window: only what the user saved counts.
     */
    private void startBackup(String kind, boolean force) {
        boolean scheduled = "scheduled".equals(kind);
        String startedOn = LocalDate.now().toString();
        stopRequested.set(false);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        setBusy(true, true, t("state.running"));
        BackupLog log = windowLog();
        worker.submit(() -> {
            BackupService.Summary summary = service.run(force, kind, log, stopRequested::get);
            Platform.runLater(() -> {
                if (scheduled) {
                    afterScheduledRun(summary, startedOn);
                }
                notifyBackup(summary, !"manual".equals(kind));
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

    /** Sends the lines and the progress of a background run to the window. */
    private BackupLog windowLog() {
        return new BackupLog() {
            @Override
            public void log(Level level, String line) {
                Platform.runLater(() -> appendLog(line));
            }

            @Override
            public void progress(int done, int total) {
                Platform.runLater(() -> progressBar.setProgress(total == 0 ? 1 : (double) done / total));
            }
        };
    }

    /** Checks the latest backup file of every repository in the background. */
    private void startCheck(boolean scheduled) {
        if (busy) {
            return;
        }
        if (config.destination.isBlank()) {
            if (!scheduled) {
                warn(t("msg.pickDestination"));
            }
            return;
        }
        stopRequested.set(false);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        setBusy(true, true, t("state.checking"));
        BackupLog log = windowLog();
        worker.submit(() -> {
            BackupService.CheckSummary summary =
                    service.check(scheduled ? "scheduled-check" : "check", log, stopRequested::get);
            Platform.runLater(() -> {
                if (summary.started() && !stopRequested.get()) {
                    config.lastVerify = LocalDate.now().toString();
                    saveConfig();
                } else if (!summary.started()) {
                    autoRetryAfter = System.currentTimeMillis() + RETRY_DELAY_MS;
                }
                notifyCheck(summary, scheduled);
                setBusy(false, false, t(!summary.started() ? "state.checkFailed"
                        : summary.damaged() > 0 ? "state.checkProblems" : "state.checkDone"));
            });
        });
    }

    private void showHistory() {
        Dialog<ButtonType> dialog = new Dialog<>();
        prepare(dialog, t("history.title"));
        dialog.setResizable(true);
        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().add("settings-pane");
        pane.setContent(new HistoryView(this::t).build(service.history()));
        ButtonType logs = new ButtonType(t("history.openLogs"), ButtonBar.ButtonData.LEFT);
        pane.getButtonTypes().addAll(logs, new ButtonType(t("settings.close"), ButtonBar.ButtonData.CANCEL_CLOSE));
        // Opening the log folder keeps the window open.
        pane.lookupButton(logs).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            getHostServices().showDocument(service.logsDir().toUri().toString());
        });
        dialog.show();
    }

    /**
     * Lets the user pick a backed-up repository (including ones no longer on GitHub), one of
     * its versions and a folder, then restores it into a new sub-folder there.
     */
    private void showRestore() {
        if (busy) {
            return;
        }
        if (config.destination.isBlank()) {
            warn(t("msg.pickDestination"));
            showSettings();
            return;
        }
        Map<String, List<BackupVersion>> backups;
        try {
            backups = restoreService.findBackups(Path.of(config.destination));
        } catch (IOException | RuntimeException e) {
            appendLog("Could not read the backup folder: " + e.getMessage());
            setStatus(t("state.restoreFailed"));
            return;
        }
        if (backups.isEmpty()) {
            warn(t("restore.none"));
            return;
        }

        ComboBox<String> repoBox = new ComboBox<>(FXCollections.observableArrayList(backups.keySet()));
        repoBox.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        repoBox.setMaxWidth(Double.MAX_VALUE);
        ComboBox<BackupVersion> versionBox = new ComboBox<>();
        versionBox.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        versionBox.setMaxWidth(Double.MAX_VALUE);
        versionBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(BackupVersion version) {
                return version == null ? "" : describe(version);
            }

            @Override
            public BackupVersion fromString(String value) {
                return null;
            }
        });
        repoBox.setOnAction(event -> {
            versionBox.getItems().setAll(backups.get(repoBox.getValue()));
            versionBox.getSelectionModel().selectFirst();
        });
        RepoRow highlighted = repoTable.getSelectionModel().getSelectedItem();
        String initial = highlighted != null && backups.containsKey(highlighted.repo().key())
                ? highlighted.repo().key()
                : backups.keySet().iterator().next();
        repoBox.setValue(initial);
        versionBox.getItems().setAll(backups.get(initial));
        versionBox.getSelectionModel().selectFirst();

        ToggleGroup mode = new ToggleGroup();
        RadioButton fullMode = new RadioButton(t("restore.modeFull"));
        fullMode.setToggleGroup(mode);
        fullMode.setSelected(true);
        RadioButton filesMode = new RadioButton(t("restore.modeFiles"));
        filesMode.setToggleGroup(mode);

        TextField folderField = new TextField();
        folderField.setEditable(false);
        folderField.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        folderField.setPrefWidth(380);
        Button folderButton = Icons.button(t("button.browse"), Feather.FOLDER);
        folderButton.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(t("restore.chooser"));
            File chosen = chooser.showDialog(folderButton.getScene().getWindow());
            if (chosen != null) {
                folderField.setText(chosen.getAbsolutePath());
            }
        });
        Label note = new Label(t("restore.note"));
        note.getStyleClass().add("muted");
        note.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(14));
        grid.addRow(0, new Label(t("restore.repo")), repoBox);
        grid.addRow(1, new Label(t("restore.version")), versionBox);
        grid.add(fullMode, 1, 2);
        grid.add(filesMode, 1, 3);
        grid.addRow(4, new Label(t("restore.folder")), folderField, folderButton);
        grid.add(note, 1, 5, 2, 1);
        GridPane.setHgrow(repoBox, Priority.ALWAYS);
        GridPane.setColumnSpan(repoBox, 2);
        GridPane.setColumnSpan(versionBox, 2);

        Dialog<ButtonType> dialog = new Dialog<>();
        prepare(dialog, t("restore.title"));
        dialog.setHeaderText(null);
        dialog.getDialogPane().setContent(grid);
        ButtonType run = new ButtonType(t("restore.run"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(run, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(run).disableProperty()
                .bind(folderField.textProperty().isEmpty().or(versionBox.valueProperty().isNull()));

        Optional<ButtonType> answer = dialog.showAndWait();
        if (answer.isEmpty() || answer.get() != run) {
            return;
        }
        startRestore(versionBox.getValue(), Path.of(folderField.getText()), fullMode.isSelected(),
                service.zipPassword().load());
    }

    /**
     * Restores in the background. An encrypted backup is first tried with the saved password;
     * when that is missing or does not fit (an older backup made with another password), the
     * user is asked and the restore is tried again.
     */
    private void startRestore(BackupVersion version, Path parent, boolean full, char[] password) {
        BackupLog log = (level, line) -> Platform.runLater(() -> appendLog(line));
        setBusy(true, false, t("state.restoring"));
        worker.submit(() -> {
            try {
                if (full) {
                    restoreService.restoreRepository(version, parent, password, log);
                } else {
                    restoreService.restoreFiles(version, parent, password, log);
                }
                Platform.runLater(() -> setBusy(false, false, t("state.restored")));
            } catch (PasswordRequiredException e) {
                Platform.runLater(() -> {
                    setBusy(false, false, t("state.ready"));
                    char[] entered = askRestorePassword(e.wrongPassword());
                    if (entered != null) {
                        startRestore(version, parent, full, entered);
                    }
                });
            } catch (Exception e) {
                log.error("Restore " + version.fullName() + " : FAILED - " + e.getMessage());
                Platform.runLater(() -> setBusy(false, false, t("state.restoreFailed")));
            }
        });
    }

    private char[] askRestorePassword(boolean wrong) {
        Dialog<ButtonType> dialog = new Dialog<>();
        prepare(dialog, t("restore.title"));
        dialog.setHeaderText(t(wrong ? "password.wrong" : "password.needed"));
        dialog.setGraphic(Icons.icon(Feather.LOCK, 28));
        PasswordField field = new PasswordField();
        field.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        field.setPrefWidth(340);
        dialog.getDialogPane().setContent(field);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty().bind(field.textProperty().isEmpty());
        Platform.runLater(field::requestFocus);
        Optional<ButtonType> answer = dialog.showAndWait();
        return answer.isPresent() && answer.get() == ButtonType.OK ? field.getText().toCharArray() : null;
    }

    private void toggleEncryption(ToggleSwitch toggle) {
        if (toggle.isSelected() == config.encrypt) {
            return; // the switch was put back below
        }
        if (toggle.isSelected() && !service.zipPassword().exists() && !showPasswordDialog()) {
            toggle.setSelected(false);
            return;
        }
        config.encrypt = toggle.isSelected();
        if (saveConfig()) {
            refreshHeader();
            setStatus(t(config.encrypt ? "state.encryptOn" : "state.encryptOff"));
        }
    }

    /** Sets or changes the password for new backups. Returns true when one was saved. */
    private boolean showPasswordDialog() {
        PasswordField first = new PasswordField();
        first.setPromptText(t("password.new"));
        PasswordField second = new PasswordField();
        second.setPromptText(t("password.repeat"));
        for (PasswordField field : List.of(first, second)) {
            field.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
            field.setPrefWidth(360);
        }
        Label warning = new Label(t("password.warning"));
        warning.setWrapText(true);
        warning.setMaxWidth(420);
        VBox content = new VBox(10, first, second, warning);
        content.setPadding(new Insets(14));

        Dialog<ButtonType> dialog = new Dialog<>();
        prepare(dialog, t("password.title"));
        dialog.setHeaderText(t("password.header"));
        dialog.setGraphic(Icons.icon(Feather.KEY, 28));
        dialog.getDialogPane().setContent(content);
        ButtonType save = new ButtonType(t("signIn.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(save).disableProperty().bind(
                first.textProperty().length().lessThan(MIN_PASSWORD_LENGTH)
                        .or(first.textProperty().isNotEqualTo(second.textProperty())));

        Optional<ButtonType> answer = dialog.showAndWait();
        if (answer.isEmpty() || answer.get() != save) {
            return false;
        }
        try {
            service.zipPassword().save(first.getText().toCharArray());
            setStatus(t("state.passwordSaved"));
            return true;
        } catch (IOException e) {
            appendLog("Could not save the password: " + e.getMessage());
            return false;
        }
    }

    /** For example: 2026-11-16 05:45  -  30348cf  -  26 KB. */
    private static String describe(BackupVersion version) {
        String time = version.time();
        return version.date() + " " + time.substring(0, 2) + ":" + time.substring(2, 4)
                + "  -  " + version.head().substring(0, 7)
                + "  -  " + formatSize(Math.max(1, version.size() / 1024)).replace("‪", "").replace("‬", "");
    }

    private void showSignIn() {
        TokenStore tokens = service.tokens();
        Dialog<ButtonType> dialog = new Dialog<>();
        prepare(dialog, t("signIn.title"));
        dialog.setHeaderText(t("signIn.header"));
        dialog.setGraphic(Icons.icon(Feather.GITHUB, 28));
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

    /** GitLab (server, token) or Bitbucket (account e-mail, API token). */
    private void showHostSignIn(String host) {
        boolean gitlab = RepoInfo.GITLAB.equals(host);
        Dialog<ButtonType> dialog = new Dialog<>();
        prepare(dialog, MessageFormat.format(t("hostSignIn.title"), RepoInfo.hostLabel(host)));
        dialog.setHeaderText(t(gitlab ? "gitlab.header" : "bitbucket.header"));
        dialog.setGraphic(Icons.icon(hostIcon(host), 28));
        TextField first = new TextField(gitlab ? config.gitlabUrl : config.bitbucketUser);
        first.setPromptText(t(gitlab ? "gitlab.server" : "bitbucket.email"));
        PasswordField token = new PasswordField();
        token.setPromptText(t("hostSignIn.token"));
        for (TextField field : List.of(first, token)) {
            field.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
            field.setPrefWidth(400);
        }
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(14));
        grid.addRow(0, new Label(t(gitlab ? "gitlab.server" : "bitbucket.email")), first);
        grid.addRow(1, new Label(t("hostSignIn.token")), token);
        dialog.getDialogPane().setContent(grid);
        ButtonType save = new ButtonType(t("signIn.save"), ButtonBar.ButtonData.OK_DONE);
        ButtonType remove = new ButtonType(t("hostSignIn.remove"), ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().addAll(save, remove, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(save).disableProperty()
                .bind(token.textProperty().isEmpty().or(first.textProperty().isEmpty()));
        Platform.runLater(token::requestFocus);

        Optional<ButtonType> answer = dialog.showAndWait();
        if (answer.isEmpty() || answer.get() == ButtonType.CANCEL) {
            return;
        }
        try {
            if (answer.get() == save) {
                if (gitlab) {
                    config.gitlabUrl = first.getText().trim();
                } else {
                    config.bitbucketUser = first.getText().trim();
                }
                saveConfig();
                service.tokens(host).save(token.getText());
            } else {
                service.tokens(host).clear();
            }
        } catch (IOException e) {
            appendLog("Could not update the saved sign-in: " + e.getMessage());
        }
        reloadList();
    }

    /**
     * Sign-in through the browser: shows a code, opens github.com/login/device, and waits
     * until the user approves there.
     */
    private void showBrowserSignIn() {
        GitHubDeviceFlow flow = new GitHubDeviceFlow();
        if (!flow.available()) {
            warn(t("browser.unavailable"));
            return;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        Dialog<ButtonType> dialog = new Dialog<>();
        prepare(dialog, t("browser.title"));
        dialog.setHeaderText(t("browser.header"));
        dialog.setGraphic(Icons.icon(Feather.GITHUB, 28));
        Label code = new Label("\u2026");
        code.getStyleClass().addAll(Styles.TITLE_1, "device-code");
        code.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        Button copy = Icons.button(t("browser.copy"), Feather.COPY);
        Button open = Icons.button(t("browser.open"), Feather.EXTERNAL_LINK);
        copy.setDisable(true);
        open.setDisable(true);
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(22, 22);
        Label state = new Label(t("browser.starting"));
        state.getStyleClass().add("muted");
        state.setWrapText(true);
        VBox content = new VBox(14, code, row(open, copy), row(spinner, state));
        content.setPadding(new Insets(14));
        content.setPrefWidth(460);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        dialog.setOnHidden(event -> cancelled.set(true));

        Thread waiter = new Thread(() -> {
            try {
                GitHubDeviceFlow.Code started = flow.start();
                Platform.runLater(() -> {
                    code.setText(started.userCode());
                    copy.setDisable(false);
                    open.setDisable(false);
                    copy.setOnAction(event -> copyToClipboard(started.userCode()));
                    open.setOnAction(event -> getHostServices().showDocument(started.verificationUri()));
                    copyToClipboard(started.userCode());
                    getHostServices().showDocument(started.verificationUri());
                    state.setText(t("browser.waiting"));
                });
                String token = flow.waitForToken(started, cancelled::get);
                if (token == null) {
                    return;
                }
                service.tokens().save(token);
                Platform.runLater(() -> {
                    dialog.close();
                    setStatus(t("browser.done"));
                    reloadList();
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    spinner.setVisible(false);
                    state.setText(e.getMessage());
                });
            }
        }, "github-sign-in");
        waiter.setDaemon(true);
        waiter.start();
        dialog.showAndWait();
    }

    private static void copyToClipboard(String value) {
        ClipboardContent content = new ClipboardContent();
        content.putString(value);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /** Fills the account lines of the header and the settings from a fresh list. */
    private void showAccounts(BackupService.Listing listing) {
        clearAccounts();
        List<String> parts = new ArrayList<>();
        for (BackupService.AccountInfo info : listing.accounts()) {
            String line;
            if (RepoInfo.GITHUB.equals(info.host())) {
                line = MessageFormat.format(t(info.source() == TokenStore.Source.SAVED_TOKEN
                        ? "label.account.saved" : "label.account.cli"), info.login());
            } else {
                line = MessageFormat.format(t("host.connected"), RepoInfo.hostLabel(info.host()), info.login());
            }
            accountProperty(info.host()).set(line);
            parts.add(line);
        }
        for (String problem : listing.problems()) {
            appendLog(MessageFormat.format(t("msg.accountProblem"), problem));
            for (String host : List.of(RepoInfo.GITHUB, RepoInfo.GITLAB, RepoInfo.BITBUCKET)) {
                if (problem.startsWith(RepoInfo.hostLabel(host) + ":")) {
                    accountProperty(host).set(MessageFormat.format(t("host.problem"), RepoInfo.hostLabel(host)));
                }
            }
        }
        account.set(parts.isEmpty() ? t("label.account.none") : String.join("   \u00B7   ", parts));
    }

    private void clearAccounts() {
        account.set(t("label.account.none"));
        githubAccount.set(t("label.account.none"));
        gitlabAccount.set(MessageFormat.format(t("host.notConnected"), "GitLab"));
        bitbucketAccount.set(MessageFormat.format(t("host.notConnected"), "Bitbucket"));
    }

    private StringProperty accountProperty(String host) {
        return switch (host) {
            case RepoInfo.GITLAB -> gitlabAccount;
            case RepoInfo.BITBUCKET -> bitbucketAccount;
            default -> githubAccount;
        };
    }

    private static Ikon hostIcon(String host) {
        return switch (host) {
            case RepoInfo.GITLAB -> Feather.GITLAB;
            case RepoInfo.BITBUCKET -> Feather.BOX;
            default -> Feather.GITHUB;
        };
    }

    private void changeLanguage(String language) {
        if (language.equals(config.language)) {
            return;
        }
        config.language = language;
        saveConfig();
        String oldStatus = statusText;
        text = bundleFor(language);
        updates.language(language);
        // The status and account lines are re-read from the list in the new language.
        statusText = oldStatus;
        Scene scene = stage.getScene();
        scene.setRoot(buildRoot());
        applyLanguage(scene);
        if (tray != null) {
            tray.show(trayLabels());
        }
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

    /** Owner, title, direction, stylesheet and frame colour shared by every dialog. */
    private void prepare(Dialog<?> dialog, String title) {
        dialog.initOwner(owner());
        dialog.setTitle(title);
        DialogPane pane = dialog.getDialogPane();
        pane.setNodeOrientation(orientation());
        pane.getStylesheets().add(css());
        dialog.setOnShowing(event -> Themes.decorate(pane.getScene(), dark));
    }

    /** The settings window while it is open, so dialogs opened from it stay in front of it. */
    private Window owner() {
        if (settingsDialog != null && settingsDialog.isShowing()) {
            return settingsDialog.getDialogPane().getScene().getWindow();
        }
        return stage;
    }

    private static String css() {
        return MainApp.class.getResource("/app.css").toExternalForm();
    }

    private void appendLog(String line) {
        logArea.appendText(line + System.lineSeparator());
    }

    private void warn(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message, ButtonType.OK);
        prepare(alert, t("app.title"));
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private boolean confirm(String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.NO);
        prepare(alert, t("app.title"));
        alert.setHeaderText(null);
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
        // -Dgitbackup.screenshotView=settings takes the picture of the settings window instead.
        String view = System.getProperty("gitbackup.screenshotView", "");
        boolean settings = "settings".equals(view);
        if (settings) {
            showSettings();
        } else if ("history".equals(view)) {
            showHistory();
        }
        PauseTransition settle = new PauseTransition(Duration.millis(600));
        settle.setOnFinished(event -> {
            try {
                Scene shown = stage.getScene();
                if (settings && settingsDialog != null) {
                    shown = settingsDialog.getDialogPane().getScene();
                } else if ("history".equals(view)) {
                    for (Window window : Window.getWindows()) {
                        if (window != stage && window.getScene() != null) {
                            shown = window.getScene();
                        }
                    }
                }
                WritableImage image = shown.snapshot(null);
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
