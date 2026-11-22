package com.gitbackup.backup;

import com.gitbackup.github.GitHubClient;
import com.gitbackup.github.GitHubException;
import com.gitbackup.github.TokenStore;
import com.gitbackup.hosts.BitbucketClient;
import com.gitbackup.hosts.GitLabClient;
import com.gitbackup.hosts.HostClient;
import com.gitbackup.model.AppConfig;
import com.gitbackup.model.BackupState;
import com.gitbackup.model.RemoteList;
import com.gitbackup.model.RepoInfo;
import com.gitbackup.model.RunRecord;
import com.gitbackup.store.AppPaths;
import com.gitbackup.store.JsonStore;
import com.gitbackup.store.SecretFile;
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

/** Ties settings, sign-in, the repository lists and the backup engine together. */
public final class BackupService {
    public enum Status { NEVER, CHANGED, CURRENT, MISSING }

    /**
     * One repository as the window shows it. {@code status} is a quick hint (push time
     * against last backup time); a real run compares branch and tag tips.
     */
    public record RepoStatus(RepoInfo repo, boolean selected, Status status, String lastBackup) {
    }

    /** One signed-in account. */
    public record AccountInfo(String host, String login, TokenStore.Source source) {
    }

    /**
     * @param problems    accounts that could not be read, as "Host: reason"; the others are listed
     * @param githubLists the GitHub account's star lists; null when they were not read (turned off,
     *                    no GitHub account, or GitHub refused), so the lists already saved stay
     */
    public record Listing(List<RepoStatus> repos, List<AccountInfo> accounts, List<String> problems,
                          boolean destinationFound, List<RemoteList> githubLists) {
        public Listing(List<RepoStatus> repos, List<AccountInfo> accounts, List<String> problems,
                       boolean destinationFound) {
            this(repos, accounts, problems, destinationFound, null);
        }
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

    /** Result of checking the backup files. */
    public record CheckSummary(boolean started, int ok, int damaged, int skipped) {
        public int exitCode() {
            return !started ? 2 : damaged > 0 ? 1 : 0;
        }
    }

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter LOG_MONTH = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT);
    private static final int HISTORY_LIMIT = 300;

    private final AppPaths paths;
    private final TokenStore tokens;
    private final TokenStore gitlabTokens;
    private final TokenStore bitbucketTokens;
    private final SecretFile zipPassword;

    /** A signed-in account and how its sign-in was found. */
    private record Account(HostClient client, TokenStore.Source source) {
    }

    public BackupService(AppPaths paths) {
        this.paths = paths;
        this.tokens = new TokenStore(paths.tokenFile());
        this.gitlabTokens = new TokenStore(paths.tokenFile(RepoInfo.GITLAB), false);
        this.bitbucketTokens = new TokenStore(paths.tokenFile(RepoInfo.BITBUCKET), false);
        this.zipPassword = new SecretFile(paths.zipPasswordFile());
    }

    /** The GitHub sign-in. */
    public TokenStore tokens() {
        return tokens;
    }

    public TokenStore tokens(String host) {
        return switch (host) {
            case RepoInfo.GITLAB -> gitlabTokens;
            case RepoInfo.BITBUCKET -> bitbucketTokens;
            default -> tokens;
        };
    }

    /** The saved password for encrypted ZIP files. */
    public SecretFile zipPassword() {
        return zipPassword;
    }

    public AppConfig loadConfig() throws IOException {
        return JsonStore.readConfig(paths.configFile());
    }

    public void saveConfig(AppConfig config) throws IOException {
        JsonStore.writeConfig(paths.configFile(), config);
    }

    /** Past runs and checks, newest first. */
    public List<RunRecord> history() {
        return JsonStore.readHistory(paths.historyFile());
    }

    public Path logsDir() {
        return paths.logsDir();
    }

    /** The repositories of every signed-in account with their backup status. */
    public Listing list() throws IOException {
        AppConfig config = loadConfig();
        List<RepoInfo> all = new ArrayList<>();
        List<AccountInfo> accountInfos = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<Account> accounts = accounts(config);
        List<RemoteList> githubLists = null;
        IOException firstError = null;
        for (Account account : accounts) {
            try {
                String login = account.client().login();
                all.addAll(account.client().listRepos());
                accountInfos.add(new AccountInfo(account.client().host(), login, account.source()));
                if (config.githubLists && account.client() instanceof GitHubClient github) {
                    githubLists = readLists(github);
                }
            } catch (IOException e) {
                firstError = firstError == null ? e : firstError;
                problems.add(RepoInfo.hostLabel(account.client().host()) + ": " + BackupEngine.describe(e));
            }
        }
        if (accountInfos.isEmpty() && firstError != null) {
            throw firstError; // nothing could be listed: report it as before
        }
        List<RepoInfo> candidates = candidates(all, config);

        Path destination = config.destination.isBlank() ? null : Path.of(config.destination);
        boolean destinationFound = destination != null && Files.isDirectory(destination);
        BackupState state = destinationFound
                ? JsonStore.readState(destination.resolve("backup-state.json"))
                : new BackupState();

        List<RepoStatus> rows = new ArrayList<>();
        for (RepoInfo repo : candidates) {
            BackupState.Entry entry = state.repos.get(repo.key());
            boolean selected = config.include.isEmpty() || matchesAny(repo, config.include);
            rows.add(new RepoStatus(repo, selected, statusOf(repo, entry, destination),
                    entry == null ? "" : entry.backedUp));
        }
        return new Listing(rows, accountInfos, problems, destinationFound, githubLists);
    }

    /** Lists are an extra: when GitHub refuses them (an older sign-in without "read:user"), the repositories still show. */
    private static List<RemoteList> readLists(GitHubClient github) {
        try {
            return github.listLists();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Status statusOf(RepoInfo repo, BackupState.Entry entry, Path destination) {
        if (entry == null) {
            return Status.NEVER;
        }
        if (entry.file == null || entry.file.isBlank() || destination == null
                || !Files.exists(repo.folderIn(destination).resolve(entry.file))) {
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

    public Summary run(boolean force, BackupLog uiLog, BooleanSupplier cancelled) {
        return run(force, "manual", uiLog, cancelled);
    }

    /**
     * Backs up the selected repositories (the "include" list of the settings, or all of
     * them when it is empty). Everything is reported through {@code uiLog}, the monthly log
     * file and the history; this method does not throw.
     *
     * @param kind how the run started, for the history: "manual", "scheduled", "tray" or "command"
     */
    public Summary run(boolean force, String kind, BackupLog uiLog, BooleanSupplier cancelled) {
        RunRecord record = startRecord(kind);
        BackupLog log = withFileLog(uiLog);
        Summary summary = locked(log, record, () -> runLocked(force, log, cancelled, record), Summary.notStarted());
        record.ran = summary.started();
        record.backedUp = summary.backedUp();
        record.unchanged = summary.unchanged();
        record.empty = summary.empty();
        record.failed = summary.failed();
        record.removed = summary.removed();
        record.exitCode = summary.exitCode();
        record.stopped = cancelled.getAsBoolean();
        finishRecord(record);
        return summary;
    }

    /**
     * Checks the latest backup file of every repository in the destination folder. The caller
     * records the date of the check in the settings when it wants to.
     */
    public CheckSummary check(String kind, BackupLog uiLog, BooleanSupplier cancelled) {
        RunRecord record = startRecord(kind);
        BackupLog log = withFileLog(uiLog);
        CheckSummary summary = locked(log, record, () -> {
            log.info("=== Backup check started ===");
            AppConfig config = loadConfig();
            Path destination = prepareDestination(config);
            BackupState state = JsonStore.readState(destination.resolve("backup-state.json"));
            log.info("Checking the latest backup of " + state.repos.size() + " repositories in " + destination);
            int[] counts = new BackupVerifier(paths, log)
                    .verifyAll(destination, state, zipPassword.load(), cancelled, record.items);
            String text = "Check done: " + counts[0] + " OK, " + counts[1] + " damaged or missing, "
                    + counts[2] + " not checked";
            if (counts[1] > 0) {
                log.error(text + ". Run a backup with \"Back up again\" ticked to replace damaged files.");
            } else if (counts[2] > 0) {
                log.warn(text);
            } else {
                log.ok(text);
            }
            return new CheckSummary(true, counts[0], counts[1], counts[2]);
        }, new CheckSummary(false, 0, 0, 0));
        record.ran = summary.started();
        record.checked = summary.ok() + summary.damaged();
        record.damaged = summary.damaged();
        record.skipped = summary.skipped();
        record.exitCode = summary.exitCode();
        record.stopped = cancelled.getAsBoolean();
        finishRecord(record);
        return summary;
    }

    private interface LockedWork<T> {
        T call() throws Exception;
    }

    /** Runs {@code work} while holding the run lock, so two runs never write at the same time. */
    private <T> T locked(BackupLog log, RunRecord record, LockedWork<T> work, T notStarted) {
        try {
            paths.createDirectories();
        } catch (IOException e) {
            log.error("Cannot create the application folder: " + BackupEngine.describe(e));
            record.error = BackupEngine.describe(e);
            return notStarted;
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
                record.error = "Another backup run is still in progress.";
                return notStarted;
            }
            try {
                return work.call();
            } finally {
                lock.release();
            }
        } catch (Exception e) {
            log.error(BackupEngine.describe(e));
            record.error = BackupEngine.describe(e);
            return notStarted;
        }
    }

    private Summary runLocked(boolean force, BackupLog log, BooleanSupplier cancelled, RunRecord record)
            throws IOException {
        log.info("=== Backup started ===");
        AppConfig config = loadConfig();
        Path destination = prepareDestination(config);
        log.info("Destination: " + destination + " (keeping " + config.keepVersions + " version(s) per repository"
                + (config.encrypt ? ", encrypted" : "") + ")");
        char[] password = null;
        if (config.encrypt) {
            password = zipPassword.load();
            if (password == null) {
                // Never fall back to unencrypted files when the user asked for encryption.
                throw new IOException("Encryption is on but no password is saved on this computer. "
                        + "Set the password in the application first.");
            }
        }

        List<RepoInfo> all = new ArrayList<>();
        Set<String> listedHosts = new HashSet<>();
        java.util.Map<String, CredentialsProvider> credentials = new java.util.HashMap<>();
        GitHubClient github = null;
        int hostFailures = 0;
        List<Account> accounts = accounts(config);
        for (Account account : accounts) {
            HostClient client = account.client();
            try {
                all.addAll(client.listRepos());
                listedHosts.add(client.host());
                credentials.put(client.host(), client.credentials());
                if (client instanceof GitHubClient gitHubClient) {
                    github = gitHubClient;
                }
            } catch (IOException e) {
                if (accounts.size() == 1) {
                    throw e;
                }
                hostFailures++;
                String text = RepoInfo.hostLabel(client.host()) + " : FAILED to list repositories - "
                        + BackupEngine.describe(e);
                log.error(text);
                record.items.add(new RunRecord.Item(RepoInfo.hostLabel(client.host()), "FAILED",
                        BackupEngine.describe(e)));
            }
        }
        List<RepoInfo> selected = new ArrayList<>();
        for (RepoInfo repo : candidates(all, config)) {
            if (config.include.isEmpty() || matchesAny(repo, config.include)) {
                selected.add(repo);
            }
        }
        log.info("Repositories: " + selected.size() + " selected of " + all.size() + " in the account(s)");

        Path stateFile = destination.resolve("backup-state.json");
        BackupState state = JsonStore.readState(stateFile);
        BackupEngine engine = new BackupEngine(paths, log);
        engine.setZipPassword(password);
        if (github != null) {
            GitHubExtras extras = new GitHubExtras(github, config.backupIssues, config.backupReleases,
                    config.backupReleaseAssets);
            engine.setExtras(extras.active() ? extras : null, config.backupWiki);
            if (extras.active() || config.backupWiki) {
                log.info("Also backing up:" + (config.backupIssues ? " issues" : "")
                        + (config.backupReleases ? " releases" + (config.backupReleaseAssets ? " (with files)" : "") : "")
                        + (config.backupWiki ? " wiki" : "") + " (GitHub repositories)");
            }
        }

        int backedUp = 0;
        int unchanged = 0;
        int empty = 0;
        int failed = hostFailures;
        int removed = 0;
        int done = 0;
        log.progress(0, selected.size());
        for (RepoInfo repo : selected) {
            if (cancelled.getAsBoolean()) {
                log.warn("Stopped by the user; the remaining repositories were not checked.");
                break;
            }
            BackupEngine.Result result = engine.backupOne(repo, destination, state, stateFile,
                    config.keepVersions, force, credentials.get(repo.host()));
            switch (result.outcome()) {
                case BACKED_UP -> backedUp++;
                case UNCHANGED -> unchanged++;
                case EMPTY -> empty++;
                case FAILED -> failed++;
            }
            removed += result.removedVersions();
            record.items.add(new RunRecord.Item(repo.key(), result.outcome().name(), result.detail()));
            log.progress(++done, selected.size());
        }

        // Repositories that disappeared from an account: their backups stay, the log says so.
        Set<String> keys = new HashSet<>();
        for (RepoInfo repo : all) {
            keys.add(repo.key());
        }
        for (String key : state.repos.keySet()) {
            if (!keys.contains(key) && listedHosts.contains(RepoInfo.hostOf(key))) {
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

    /** Every account with a sign-in. Throws when there is none at all. */
    private List<Account> accounts(AppConfig config) throws GitHubException {
        List<Account> accounts = new ArrayList<>();
        TokenStore.Resolved github = tokens.resolve();
        if (github.present()) {
            accounts.add(new Account(new GitHubClient(github.token(), config.includeOrgs), github.source()));
        }
        TokenStore.Resolved gitlab = gitlabTokens.resolve();
        if (gitlab.present()) {
            accounts.add(new Account(new GitLabClient(config.gitlabUrl, gitlab.token(), config.includeOrgs),
                    gitlab.source()));
        }
        TokenStore.Resolved bitbucket = bitbucketTokens.resolve();
        if (bitbucket.present() && !config.bitbucketUser.isBlank()) {
            accounts.add(new Account(new BitbucketClient(config.bitbucketUser, bitbucket.token(), config.includeOrgs),
                    bitbucket.source()));
        }
        if (accounts.isEmpty()) {
            throw new GitHubException(GitHubException.Kind.NOT_SIGNED_IN,
                    "Not signed in. Sign in to GitHub (or GitLab / Bitbucket) in the settings, "
                            + "or sign in with GitHub CLI (gh auth login).");
        }
        return accounts;
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

    /**
     * True when the name, owner/name or key (host:owner/name) matches one of the patterns;
     * * is a wildcard, case is ignored.
     */
    public static boolean matchesAny(RepoInfo repo, List<String> patterns) {
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            Pattern regex = Pattern.compile(
                    ("\\Q" + pattern.trim().replace("*", "\\E.*\\Q") + "\\E"), Pattern.CASE_INSENSITIVE);
            if (regex.matcher(repo.name()).matches() || regex.matcher(repo.key()).matches()
                    || (repo.onGitHub() && regex.matcher(repo.fullName()).matches())) {
                return true;
            }
        }
        return false;
    }

    private static RunRecord startRecord(String kind) {
        RunRecord record = new RunRecord();
        record.kind = kind;
        record.started = LocalDateTime.now().format(JsonStore.ISO_LOCAL);
        return record;
    }

    /** Adds the finished run at the top of the history; a full history drops its oldest runs. */
    private synchronized void finishRecord(RunRecord record) {
        record.finished = LocalDateTime.now().format(JsonStore.ISO_LOCAL);
        try {
            List<RunRecord> runs = JsonStore.readHistory(paths.historyFile());
            runs.add(0, record);
            while (runs.size() > HISTORY_LIMIT) {
                runs.remove(runs.size() - 1);
            }
            JsonStore.writeHistory(paths.historyFile(), runs);
        } catch (IOException | RuntimeException ignored) {
            // The run matters more than its history entry.
        }
    }

    /** Sends every line to the caller and appends it to this month's log file. */
    public BackupLog withFileLog(BackupLog target) {
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
