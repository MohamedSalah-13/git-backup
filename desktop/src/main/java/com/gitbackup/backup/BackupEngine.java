package com.gitbackup.backup;

import com.gitbackup.model.BackupState;
import com.gitbackup.model.RepoInfo;
import com.gitbackup.store.AppPaths;
import com.gitbackup.store.JsonStore;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.BundleWriter;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.FileUtils;

/**
 * Backs up one repository at a time.
 *
 * <p>A repository is skipped when its branches and tags are the same as at the last backup
 * and that backup's ZIP is still in place. Otherwise a new ZIP is built (source snapshot,
 * full-history bundle and restore notes), checked, copied to the destination, and only then
 * are older versions removed.
 */
public final class BackupEngine {
    public enum Outcome { BACKED_UP, UNCHANGED, EMPTY, FAILED }

    public record Result(RepoInfo repo, Outcome outcome, int removedVersions) {
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss", Locale.ROOT);
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final RefSpec HEADS = new RefSpec("+refs/heads/*:refs/heads/*");
    private static final RefSpec TAGS = new RefSpec("+refs/tags/*:refs/tags/*");

    private final AppPaths paths;
    private final BackupLog log;

    public BackupEngine(AppPaths paths, BackupLog log) {
        this.paths = paths;
        this.log = log;
    }

    /**
     * Backs up {@code repo} into {@code destination/owner/name} when needed and records the
     * result in {@code state} (saved to {@code stateFile} after every successful backup).
     * Never throws: a failure is logged and reported as {@link Outcome#FAILED}.
     */
    public Result backupOne(RepoInfo repo, Path destination, BackupState state, Path stateFile,
                            int keepVersions, boolean force, CredentialsProvider credentials) {
        String full = repo.fullName();
        Path tmpZip = null;
        try {
            String branch = repo.defaultBranch();
            String fingerprint = branch == null || branch.isBlank() ? null : fingerprint(repo, credentials);
            if (fingerprint == null) {
                log.info(full + " : empty repository, nothing to back up");
                return new Result(repo, Outcome.EMPTY, 0);
            }

            Path repoDir = destination.resolve(repo.owner()).resolve(repo.name());
            String reason = reasonToBackUp(state.repos.get(full), fingerprint, repoDir, force);
            if (reason == null) {
                log.info(full + " : unchanged, skipped");
                return new Result(repo, Outcome.UNCHANGED, 0);
            }

            log.info(full + " : backing up (" + reason + ")");
            paths.createDirectories();
            Path mirror = paths.mirrorsDir().resolve(repo.owner()).resolve(repo.name() + ".git");
            syncMirror(repo, mirror, credentials);

            String head = headOf(mirror, branch);
            String zipName = repo.name() + "_" + LocalDateTime.now().format(STAMP) + "_" + head + ".zip";
            tmpZip = paths.tmpDir().resolve(zipName);
            int files = buildZip(repo, mirror, tmpZip, head);
            long size = Files.size(tmpZip);

            // Copy under a temporary name so a half-written file is never mistaken for a backup.
            Files.createDirectories(repoDir);
            deleteLeftovers(repoDir);
            Path partial = repoDir.resolve(zipName + ".partial");
            Files.copy(tmpZip, partial, StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(partial) != size) {
                throw new IOException("Copy to the destination is incomplete.");
            }
            Files.move(partial, repoDir.resolve(zipName), StandardCopyOption.REPLACE_EXISTING);

            BackupState.Entry entry = new BackupState.Entry();
            entry.fingerprint = fingerprint;
            entry.branch = branch;
            entry.head = head;
            entry.file = zipName;
            entry.size = size;
            entry.backedUp = LocalDateTime.now().format(JsonStore.ISO_LOCAL);
            state.repos.put(full, entry);
            JsonStore.writeState(stateFile, state);

            // The new backup is safely in place: now older versions can go.
            int removed = pruneOldBackups(repoDir, repo.name(), zipName, keepVersions);
            String extra = removed > 0 ? ", removed " + removed + " old version(s)" : "";
            log.ok(full + " : saved " + zipName + " (" + files + " files, " + formatSize(size) + ")" + extra);
            return new Result(repo, Outcome.BACKED_UP, removed);
        } catch (Exception e) {
            log.error(full + " : FAILED - " + describe(e));
            return new Result(repo, Outcome.FAILED, 0);
        } finally {
            deleteQuietly(tmpZip);
        }
    }

    /** Null when the last backup is still valid, otherwise why a new one is needed. */
    private static String reasonToBackUp(BackupState.Entry previous, String fingerprint, Path repoDir, boolean force)
            throws IOException {
        if (force) {
            return "forced";
        }
        if (previous == null) {
            return "first backup";
        }
        if (!fingerprint.equals(previous.fingerprint)) {
            return "changed on GitHub";
        }
        if (previous.file == null || previous.file.isBlank()) {
            return "backup file is missing";
        }
        Path existing = repoDir.resolve(previous.file);
        if (!Files.exists(existing)) {
            return "backup file is missing";
        }
        if (Files.size(existing) != previous.size) {
            return "backup file size differs";
        }
        return null;
    }

    /**
     * A hash of the default branch name plus every branch and tag tip, or null for a
     * repository without commits. The text that is hashed matches what the PowerShell
     * version builds from "git ls-remote --heads --tags", so both share one state file.
     */
    public String fingerprint(RepoInfo repo, CredentialsProvider credentials) throws GitAPIException {
        Collection<Ref> refs = Git.lsRemoteRepository()
                .setRemote(repo.cloneUrl())
                .setHeads(true)
                .setTags(true)
                .setCredentialsProvider(credentials)
                .call();
        return fingerprintOf(repo.defaultBranch(), refs);
    }

    static String fingerprintOf(String branch, Collection<Ref> refs) {
        List<Ref> sorted = new ArrayList<>();
        for (Ref ref : refs) {
            if (ref.getObjectId() != null) {
                sorted.add(ref);
            }
        }
        if (sorted.isEmpty()) {
            return null;
        }
        sorted.sort(Comparator.comparing(Ref::getName));
        StringBuilder text = new StringBuilder("default:").append(branch);
        for (Ref ref : sorted) {
            text.append('\n').append(ref.getObjectId().name()).append('\t').append(ref.getName());
            if (ref.getPeeledObjectId() != null) {
                text.append('\n').append(ref.getPeeledObjectId().name()).append('\t').append(ref.getName()).append("^{}");
            }
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Keeps a local bare copy (branches and tags) so later runs only download what is new. */
    private void syncMirror(RepoInfo repo, Path mirror, CredentialsProvider credentials) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                if (!Files.exists(mirror.resolve("HEAD"))) {
                    deleteRecursively(mirror);
                    Files.createDirectories(mirror);
                    Git.init().setBare(true).setDirectory(mirror.toFile())
                            .setInitialBranch(repo.defaultBranch()).call().close();
                }
                try (Git git = Git.open(mirror.toFile())) {
                    git.fetch()
                            .setRemote(repo.cloneUrl())
                            .setRefSpecs(HEADS, TAGS)
                            .setRemoveDeletedRefs(true)
                            .setTagOpt(TagOpt.NO_TAGS)
                            .setForceUpdate(true)
                            .setCredentialsProvider(credentials)
                            .call();
                    RefUpdate head = git.getRepository().updateRef(Constants.HEAD);
                    head.disableRefLog();
                    head.link(Constants.R_HEADS + repo.defaultBranch());
                }
                return;
            } catch (Exception e) {
                if (attempt >= 2) {
                    throw e;
                }
                log.warn("  local copy problem, downloading again: " + describe(e));
                deleteRecursively(mirror);
            }
        }
    }

    private static String headOf(Path mirror, String branch) throws IOException {
        try (Repository db = openBare(mirror)) {
            ObjectId head = db.resolve(Constants.HEAD);
            if (head == null) {
                throw new IOException("default branch '" + branch + "' was not found in the repository");
            }
            return head.name().substring(0, 7);
        }
    }

    /** Builds the ZIP and checks it. Returns the number of source files in it. */
    private int buildZip(RepoInfo repo, Path mirror, Path outZip, String head) throws Exception {
        String bundleName = repo.name() + ".bundle";
        Path bundle = paths.tmpDir().resolve(bundleName);
        Files.deleteIfExists(bundle);
        Files.deleteIfExists(outZip);
        try (Repository db = openBare(mirror)) {
            ObjectId headId = db.resolve(Constants.HEAD);
            int refs = writeBundle(db, headId, bundle);
            verifyBundle(db, bundle, refs);
            long bundleSize = Files.size(bundle);

            int files = 0;
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(outZip)));
                 RevWalk walk = new RevWalk(db);
                 TreeWalk tree = new TreeWalk(db)) {
                RevCommit commit = walk.parseCommit(headId);
                long commitTime = commit.getCommitTime() * 1000L;
                tree.addTree(commit.getTree());
                tree.setRecursive(true);
                while (tree.next()) {
                    if (tree.getFileMode(0) == FileMode.GITLINK) {
                        continue; // a submodule is another repository, not a file
                    }
                    ZipEntry entry = new ZipEntry("source/" + tree.getPathString());
                    entry.setTime(commitTime);
                    zip.putNextEntry(entry);
                    db.open(tree.getObjectId(0)).copyTo(zip);
                    zip.closeEntry();
                    files++;
                }

                // The bundle is already compressed, so it is stored as-is.
                ZipEntry bundleEntry = new ZipEntry(bundleName);
                bundleEntry.setMethod(ZipEntry.STORED);
                bundleEntry.setSize(bundleSize);
                bundleEntry.setCompressedSize(bundleSize);
                bundleEntry.setCrc(crcOf(bundle));
                zip.putNextEntry(bundleEntry);
                Files.copy(bundle, zip);
                zip.closeEntry();

                zip.putNextEntry(new ZipEntry("RESTORE.txt"));
                zip.write(restoreNotes(repo, head).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            verifyZip(outZip, files + 2, bundleName, bundleSize);
            return files;
        } finally {
            deleteQuietly(bundle);
        }
    }

    private static int writeBundle(Repository db, ObjectId headId, Path bundle) throws IOException {
        BundleWriter writer = new BundleWriter(db);
        int count = 0;
        for (Ref ref : db.getRefDatabase().getRefsByPrefix(Constants.R_HEADS, Constants.R_TAGS)) {
            writer.include(ref);
            count++;
        }
        // HEAD lets "git clone repo.bundle" check out a branch on its own. A bundle only records
        // HEAD's commit, not its branch name, so RESTORE.txt names the branch with -b.
        writer.include(Constants.HEAD, headId);
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(bundle))) {
            writer.writeBundle(NullProgressMonitor.INSTANCE, out);
        }
        return count;
    }

    private static void verifyBundle(Repository db, Path bundle, int expectedRefs) throws Exception {
        try (Transport transport = Transport.open(db, new URIish().setPath(bundle.toFile().getAbsolutePath()));
             FetchConnection connection = transport.openFetch()) {
            if (connection.getRefs().size() < expectedRefs) {
                throw new IOException("History bundle check failed: branches or tags are missing.");
            }
        }
    }

    private static void verifyZip(Path zip, int expectedEntries, String bundleName, long bundleSize) throws IOException {
        try (ZipFile file = new ZipFile(zip.toFile())) {
            if (file.size() != expectedEntries) {
                throw new IOException("ZIP check failed: " + file.size() + " entries, expected " + expectedEntries + ".");
            }
            ZipEntry bundle = file.getEntry(bundleName);
            if (bundle == null || bundle.getSize() != bundleSize) {
                throw new IOException("ZIP check failed: the history bundle is missing or incomplete.");
            }
        }
    }

    private static String restoreNotes(RepoInfo repo, String head) {
        String name = repo.name();
        return String.join("\r\n",
                "Backup of " + repo.fullName(),
                "Created : " + LocalDateTime.now().format(HUMAN),
                "Branch  : " + repo.defaultBranch() + " @ " + head,
                "",
                "Contents",
                "  source\\",
                "      Files of the default branch at the time of the backup.",
                "  " + name + ".bundle",
                "      Full history: every commit, branch and tag.",
                "",
                "Restore the full repository (history included)",
                "  git clone -b " + repo.defaultBranch() + " " + name + ".bundle " + name,
                "  cd " + name,
                "  git remote set-url origin " + repo.cloneUrl(),
                "",
                "Restore as an exact mirror (all branches as local branches)",
                "  git clone --mirror " + name + ".bundle " + name + ".git",
                "");
    }

    /**
     * Deletes older ZIPs of one repository, keeping {@code keep} versions including
     * {@code current}. Only touches files that follow this application's naming pattern.
     */
    static int pruneOldBackups(Path repoDir, String name, String current, int keep) throws IOException {
        Pattern pattern = Pattern.compile(
                "^" + Pattern.quote(name) + "_\\d{4}-\\d{2}-\\d{2}_\\d{6}_[0-9a-f]{7,40}\\.zip$");
        List<Path> older = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(repoDir)) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                if (Files.isRegularFile(file) && pattern.matcher(fileName).matches() && !fileName.equals(current)) {
                    older.add(file);
                }
            }
        }
        older.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        int removed = 0;
        for (int i = Math.max(0, keep - 1); i < older.size(); i++) {
            Files.delete(older.get(i));
            removed++;
        }
        return removed;
    }

    private static void deleteLeftovers(Path repoDir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(repoDir, "*.partial")) {
            for (Path file : stream) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static Repository openBare(Path mirror) throws IOException {
        return new FileRepositoryBuilder().setGitDir(mirror.toFile()).setMustExist(true).build();
    }

    private static long crcOf(Path file) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                crc.update(buffer, 0, read);
            }
        }
        return crc.getValue();
    }

    private static void deleteRecursively(Path dir) throws IOException {
        FileUtils.delete(dir.toFile(), FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING);
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A leftover temporary file is replaced on the next run.
        }
    }

    static String formatSize(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%d KB", Math.round(bytes / 1024.0));
    }

    static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
