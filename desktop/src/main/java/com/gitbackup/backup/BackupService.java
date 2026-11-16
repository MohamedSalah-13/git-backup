package com.gitbackup.backup;

import com.gitbackup.github.GitHubClient;
import com.gitbackup.github.GitHubException;
import com.gitbackup.github.TokenStore;
import com.gitbackup.model.AppConfig;
import com.gitbackup.model.BackupState;
import com.gitbackup.model.RepoInfo;
import com.gitbackup.store.AppPaths;
import com.gitbackup.store.JsonStore;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

/** Ties settings, sign-in, the repository list and the backup engine together. */
public final class BackupService {
    public enum Status { NEVER, CHANGED, CURRENT, MISSING }

    /**
     * One repository as the window shows it. {@code status} is a quick hint (push time
     * against last backup time); a real run compares branch and tag tips.
     */
    public record RepoStatus(RepoInfo repo, boolean selected, Status status, String lastBackup) {
    }

    public record Listing(List<RepoStatus> repos, String login, TokenStore.Source tokenSource,
                          boolean destinationFound) {
    }

    public record Summary(boolean started, int backedUp, int unchanged, int empty, int failed, int removed) {
        /** 0 = all good, 1 = some repositories failed, 2 = the run could not start. */
        public int exitCode() {
            return !started ? 2 : failed > 0 ? 1 : 0;
        }

        static Summary notStarted() {
            return new Summary(false, 0, 0, 0, 0, 0);
        }
    }

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter LOG_MONTH = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT);

    private final AppPaths paths;
    private final TokenStore tokens;

    public BackupService(AppPaths paths) {
        this.paths = paths;
        this.tokens = new TokenStore(paths.tokenFile());
    }

    public TokenStore tokens() {
        return tokens;
    }

    public AppConfig loadConfig() throws IOException {
        return JsonStore.readConfig(paths.configFile());
    }

    public void saveConfig(AppConfig config) throws IOException {
        JsonStore.writeConfig(paths.configFile(), config);
    }

    /** The repositories of the signed-in account with their backup status. */
    public Listing list() throws IOException {
        AppConfig config = loadConfig();
        TokenStore.Resolved token = requireToken();
        GitHubClient client = new GitHubClient(token.token());
        String login = client.login();
        List<RepoInfo> candidates = candidates(client.listOwnedRepos(), config);

        Path destination = config.destination.isBlank() ? null : Path.of(config.destination);
        boolean destinationFound = destination != null && Files.isDirectory(destination);
        BackupState state = destinationFound
                ? JsonStore.readState(destination.resolve("backup-state.json"))
                : new BackupState();

        List<RepoStatus> rows = new ArrayList<>();
        for (RepoInfo repo : candidates) {
            BackupState.Entry entry = state.repos.get(repo.fullName());
            boolean selected = config.include.isEmpty() || matchesAny(repo, config.include);
            rows.add(new RepoStatus(repo, selected, statusOf(repo, entry, destination),
                    entry == null ? "" : entry.backedUp));
        }
        return new Listing(rows, login, token.source(), destinationFound);
    }

    private static Status statusOf(RepoInfo repo, BackupState.Entry entry, Path destination) {
        if (entry == null) {
            return Status.NEVER;
        }
        if (entry.file == null || entry.file.isBlank()
                || !Files.exists(destination.resolve(repo.owner()).resolve(repo.name()).resolve(entry.file))) {
            return Status.MISSING;
        }
        try {
            Instant backedUp = LocalDateTime.parse(entry.backedUp, JsonStore.ISO_LOCAL)
                    .atZone(ZoneId.systemDefault()).toInstant();
            if (repo.pushedAt() != null && repo.pushedAt().isAfter(backedUp)) {
                return Status.CHANGED;
            }
        } catch (RuntimeException e) {
            // An unreadable date only affects the hint shown in the list.
        }
        return Status.CURRENT;
    }

    /**
     * Backs up the selected repositories (the "include" list of the settings, or all of
     * them when it is empty). Everything is reported through {@code uiLog} and the monthly
     * log file; this method does not throw.
     */
    public Summary run(boolean force, BackupLog uiLog, BooleanSupplier cancelled) {
        BackupLog log = withFileLog(uiLog);
        try {
            paths.createDirectories();
        } catch (IOException e) {
            log.error("Cannot create the application folder: " + BackupEngine.describe(e));
            return Summary.notStarted();
        }
        try (FileChannel channel = FileChannel.open(paths.lockFile(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) {
                log.error("Another backup run is still in progress.");
                return Summary.notStarted();
            }
            try {
                return runLocked(force, log, cancelled);
            } finally {
                lock.release();
            }
        } catch (Exception e) {
            log.error(BackupEngine.describe(e));
            return Summary.notStarted();
        }
    }

    private Summary runLocked(boolean force, BackupLog log, BooleanSupplier cancelled) throws IOException {
        log.info("=== GitHub backup started ===");
        AppConfig config = loadConfig();
        Path destination = prepareDestination(config);
        log.info("Destination: " + destination + " (keeping " + config.keepVersions + " version(s) per repository)");

        TokenStore.Resolved token = requireToken();
        List<RepoInfo> all = new GitHubClient(token.token()).listOwnedRepos();
        List<RepoInfo> selected = new ArrayList<>();
        for (RepoInfo repo : candidates(all, config)) {
            if (config.include.isEmpty() || matchesAny(repo, config.include)) {
                selected.add(repo);
            }
        }
        log.info("Repositories: " + selected.size() + " selected of " + all.size() + " in the account");

        Path stateFile = destination.resolve("backup-state.json");
        BackupState state = JsonStore.readState(stateFile);
        CredentialsProvider credentials = new UsernamePasswordCredentialsProvider("x-access-token", token.token());
        BackupEngine engine = new BackupEngine(paths, log);

        int backedUp = 0;
        int unchanged = 0;
        int empty = 0;
        int failed = 0;
        int removed = 0;
        int done = 0;
        log.progress(0, selected.size());
        for (RepoInfo repo : selected) {
            if (cancelled.getAsBoolean()) {
                log.warn("Stopped by the user; the remaining repositories were not checked.");
                break;
            }
            BackupEngine.Result result = engine.backupOne(repo, destination, state, stateFile,
                    config.keepVersions, force, credentials);
            switch (result.outcome()) {
                case BACKED_UP -> backedUp++;
                case UNCHANGED -> unchanged++;
                case EMPTY -> empty++;
                case FAILED -> failed++;
            }
            removed += result.removedVersions();
            log.progress(++done, selected.size());
        }

        // Repositories that disappeared from the account: their backups stay, the log says so.
        Set<String> names = new HashSet<>();
        for (RepoInfo repo : all) {
            names.add(repo.fullName());
        }
        for (String key : state.repos.keySet()) {
            if (!names.contains(key)) {
                log.warn(key + " : no longer in the account; its backups were kept");
            }
        }

        String text = "Done: " + backedUp + " backed up, " + unchanged + " unchanged, " + empty + " empty, "
                + failed + " failed, " + removed + " old version(s) removed";
        if (failed > 0) {
            log.warn(text);
        } else {
            log.ok(text);
        }
        return new Summary(true, backedUp, unchanged, empty, failed, removed);
    }

    /** The destination must sit on an available drive; only its last folder is created. */
    private static Path prepareDestination(AppConfig config) throws IOException {
        if (config.destination.isBlank()) {
            throw new IOException("No destination folder is set.");
        }
        Path destination = Path.of(config.destination).toAbsolutePath();
        if (destination.getRoot() != null && !Files.exists(destination.getRoot())) {
            throw new IOException("Drive " + destination.getRoot() + " is not available. If the destination is "
                    + "Google Drive, make sure Google Drive for desktop is running.");
        }
        if (!Files.isDirectory(destination)) {
            Path parent = destination.getParent();
            if (parent == null || !Files.isDirectory(parent)) {
                throw new IOException("Folder '" + parent + "' does not exist. Choose the destination again.");
            }
            Files.createDirectory(destination);
        }
        return destination;
    }

    private TokenStore.Resolved requireToken() throws GitHubException {
        TokenStore.Resolved token = tokens.resolve();
        if (!token.present()) {
            throw new GitHubException(GitHubException.Kind.NOT_SIGNED_IN,
                    "Not signed in to GitHub. Add a token in the application, or sign in with GitHub CLI (gh auth login).");
        }
        return token;
    }

    private static List<RepoInfo> candidates(List<RepoInfo> all, AppConfig config) {
        List<RepoInfo> result = new ArrayList<>();
        for (RepoInfo repo : all) {
            if (repo.fork() && !config.includeForks) {
                continue;
            }
            if (repo.archived() && !config.includeArchived) {
                continue;
            }
            if (matchesAny(repo, config.exclude)) {
                continue;
            }
            result.add(repo);
        }
        return result;
    }

    /** True when the name or owner/name matches one of the patterns; * is a wildcard, case is ignored. */
    static boolean matchesAny(RepoInfo repo, List<String> patterns) {
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            Pattern regex = Pattern.compile(
                    ("\\Q" + pattern.trim().replace("*", "\\E.*\\Q") + "\\E"), Pattern.CASE_INSENSITIVE);
            if (regex.matcher(repo.name()).matches() || regex.matcher(repo.fullName()).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Sends every line to the caller and appends it to this month's log file. */
    private BackupLog withFileLog(BackupLog target) {
        Path file = paths.logsDir().resolve("backup-" + LocalDateTime.now().format(LOG_MONTH) + ".log");
        return new BackupLog() {
            @Override
            public synchronized void log(Level level, String message) {
                String line = LocalDateTime.now().format(LOG_TIME)
                        + " [" + String.format(Locale.ROOT, "%-5s", level) + "] " + message;
                try {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ignored) {
                    // The run matters more than its log file.
                }
                target.log(level, line);
            }

            @Override
            public void progress(int done, int total) {
                target.progress(done, total);
            }
        };
    }
}
