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
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.io.outputstream.ZipOutputStream;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;
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
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
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

    /** @param detail the new ZIP's name, or what went wrong */
    public record Result(RepoInfo repo, Outcome outcome, int removedVersions, String detail) {
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss", Locale.ROOT);
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final RefSpec HEADS = new RefSpec("+refs/heads/*:refs/heads/*");
    private static final RefSpec TAGS = new RefSpec("+refs/tags/*:refs/tags/*");

    private final AppPaths paths;
    private final BackupLog log;
    private char[] zipPassword;
    private ExtrasSource extras;
    private boolean wiki;

    public BackupEngine(AppPaths paths, BackupLog log) {
        this.paths = paths;
        this.log = log;
    }

    /**
     * With a password, new ZIP files are encrypted with AES-256 (the standard ZIP AES format,
     * which 7-Zip and WinRAR open). File names inside the ZIP stay readable; contents do not.
     * Null or empty turns encryption off.
     */
    public void setZipPassword(char[] password) {
        this.zipPassword = password == null || password.length == 0 ? null : password.clone();
    }

    /**
     * Also puts issues, releases ({@code extras}, may be null) and the wiki's history into the
     * ZIP of GitHub repositories.
     */
    public void setExtras(ExtrasSource extras, boolean wiki) {
        this.extras = extras;
        this.wiki = wiki;
    }

    private boolean encrypting() {
        return zipPassword != null;
    }

    /**
     * Backs up {@code repo} into {@code destination/owner/name} when needed and records the
     * result in {@code state} (saved to {@code stateFile} after every successful backup).
     * Never throws: a failure is logged and reported as {@link Outcome#FAILED}.
     */
    public Result backupOne(RepoInfo repo, Path destination, BackupState state, Path stateFile,
                            int keepVersions, boolean force, CredentialsProvider credentials) {
        String full = repo.key();
        Path tmpZip = null;
        Path workDir = null;
        try {
            String branch = repo.defaultBranch();
            String fingerprint = branch == null || branch.isBlank() ? null : fingerprint(repo, credentials);
            if (fingerprint == null) {
                log.info(full + " : empty repository, nothing to back up");
                return new Result(repo, Outcome.EMPTY, 0, "");
            }
            String extrasFingerprint = extrasFingerprint(repo, credentials);

            Path repoDir = repo.folderIn(destination);
            String reason = reasonToBackUp(state.repos.get(full), fingerprint, extrasFingerprint, repoDir, force,
                    encrypting());
            if (reason == null) {
                log.info(full + " : unchanged, skipped");
                return new Result(repo, Outcome.UNCHANGED, 0, "");
            }

            log.info(full + " : backing up (" + reason + ")");
            paths.createDirectories();
            Path mirror = mirrorOf(repo, ".git");
            syncMirror(repo, mirror, credentials);

            String head = headOf(mirror, branch);
            String zipName = repo.name() + "_" + LocalDateTime.now().format(STAMP) + "_" + head + ".zip";
            tmpZip = paths.tmpDir().resolve(zipName);
            workDir = paths.tmpDir().resolve("extras-" + repo.name() + "-" + System.nanoTime());
            List<ExtrasSource.ExtraFile> extraFiles = collectExtras(repo, workDir, credentials);
            int files = buildZip(repo, mirror, tmpZip, head, extraFiles);
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
            entry.encrypted = encrypting();
            entry.backedUp = LocalDateTime.now().format(JsonStore.ISO_LOCAL);
            entry.extras = extrasFingerprint;
            entry.remote = repo.cloneUrl();
            state.repos.put(full, entry);
            JsonStore.writeState(stateFile, state);

            // The new backup is safely in place: now older versions can go.
            int removed = pruneOldBackups(repoDir, repo.name(), zipName, keepVersions);
            String extra = removed > 0 ? ", removed " + removed + " old version(s)" : "";
            log.ok(full + " : saved " + zipName + " (" + files + " files, " + formatSize(size)
                    + (encrypting() ? ", encrypted" : "") + ")" + extra);
            return new Result(repo, Outcome.BACKED_UP, removed, zipName);
        } catch (Exception e) {
            log.error(full + " : FAILED - " + describe(e));
            return new Result(repo, Outcome.FAILED, 0, describe(e));
        } finally {
            deleteQuietly(tmpZip);
            deleteTreeQuietly(workDir);
        }
    }

    /** Local bare copies: mirrors/owner/name.git, and mirrors/gitlab/group/name.git outside GitHub. */
    private Path mirrorOf(RepoInfo repo, String suffix) {
        Path folder = repo.folderIn(paths.mirrorsDir());
        return folder.resolveSibling(folder.getFileName() + suffix);
    }

    /** Empty when nothing besides the code is backed up; otherwise a hash of what else changes. */
    private String extrasFingerprint(RepoInfo repo, CredentialsProvider credentials) throws Exception {
        if (!repo.onGitHub() || (extras == null && !wiki)) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        if (extras != null) {
            text.append(extras.fingerprint(repo));
        }
        if (wiki && repo.hasWiki()) {
            Collection<Ref> refs = wikiRefs(repo, credentials);
            String wikiPrint = refs == null ? null : fingerprintOf("wiki", refs);
            text.append("wiki:").append(wikiPrint == null ? "none" : wikiPrint).append('\n');
        }
        if (text.length() == 0) {
            return "";
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The wiki's branches, or null when the wiki repository does not exist (GitHub creates it
     * only with the first page). Any other problem, such as a timeout or a refused sign-in, is
     * thrown, so the repository's backup fails instead of silently leaving the wiki out.
     */
    private static Collection<Ref> wikiRefs(RepoInfo repo, CredentialsProvider credentials) throws GitAPIException {
        try {
            return Git.lsRemoteRepository().setRemote(repo.wikiUrl()).setHeads(true).setTags(true)
                    .setCredentialsProvider(credentials).call();
        } catch (GitAPIException e) {
            if (isMissingRepository(e)) {
                return null;
            }
            throw e;
        }
    }

    /** True when git reported that the remote repository does not exist. */
    static boolean isMissingRepository(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof NoRemoteRepositoryException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private List<ExtrasSource.ExtraFile> collectExtras(RepoInfo repo, Path workDir, CredentialsProvider credentials)
            throws Exception {
        List<ExtrasSource.ExtraFile> files = new ArrayList<>();
        if (!repo.onGitHub()) {
            return files;
        }
        if (extras != null) {
            files.addAll(extras.collect(repo, workDir, log));
        }
        if (wiki && repo.hasWiki() && wikiRefs(repo, credentials) != null) {
            Path mirror = mirrorOf(repo, ".wiki.git");
            syncWikiMirror(repo, mirror, credentials);
            String name = repo.name() + ".wiki.bundle";
            Path bundle = workDir.resolve(name);
            Files.createDirectories(workDir);
            try (Repository db = openBare(mirror)) {
                int refs = writeBundle(db, db.resolve(Constants.HEAD), bundle);
                if (refs > 0) {
                    verifyBundle(db, bundle, refs);
                    files.add(new ExtrasSource.ExtraFile(name, bundle));
                    log.info("  wiki: " + refs + " branch(es)");
                }
            }
        }
        return files;
    }

    /** Like {@link #syncMirror}, for the wiki, whose main branch name is not known beforehand. */
    private void syncWikiMirror(RepoInfo repo, Path mirror, CredentialsProvider credentials) throws Exception {
        if (!Files.exists(mirror.resolve("HEAD"))) {
            deleteRecursively(mirror);
            Files.createDirectories(mirror);
            Git.init().setBare(true).setDirectory(mirror.toFile()).setInitialBranch("master").call().close();
        }
        try (Git git = Git.open(mirror.toFile())) {
            git.fetch().setRemote(repo.wikiUrl()).setRefSpecs(HEADS, TAGS).setRemoveDeletedRefs(true)
                    .setTagOpt(TagOpt.NO_TAGS).setForceUpdate(true).setCredentialsProvider(credentials).call();
            Repository db = git.getRepository();
            List<Ref> heads = db.getRefDatabase().getRefsByPrefix(Constants.R_HEADS);
            String target = null;
            for (String candidate : List.of("master", "main")) {
                if (db.findRef(Constants.R_HEADS + candidate) != null) {
                    target = Constants.R_HEADS + candidate;
                    break;
                }
            }
            if (target == null && !heads.isEmpty()) {
                target = heads.get(0).getName();
            }
            if (target != null) {
                RefUpdate head = db.updateRef(Constants.HEAD);
                head.disableRefLog();
                head.link(target);
            }
        }
    }

    /** Null when the last backup is still valid, otherwise why a new one is needed. */
    private static String reasonToBackUp(BackupState.Entry previous, String fingerprint, String extras, Path repoDir,
                                         boolean force, boolean encrypt) throws IOException {
        if (force) {
            return "forced";
        }
        if (previous == null) {
            return "first backup";
        }
        if (!fingerprint.equals(previous.fingerprint)) {
            return "changed on the server";
        }
        if (!extras.equals(previous.extras == null ? "" : previous.extras)) {
            return extras.isEmpty() ? "issues, releases or wiki no longer included" : "issues, releases or wiki changed";
        }
        if (previous.encrypted != encrypt) {
            return encrypt ? "encryption was turned on" : "encryption was turned off";
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
    private int buildZip(RepoInfo repo, Path mirror, Path outZip, String head, List<ExtrasSource.ExtraFile> extraFiles)
            throws Exception {
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
            OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(outZip));
            try (ZipOutputStream zip = encrypting()
                         ? new ZipOutputStream(fileOut, zipPassword)
                         : new ZipOutputStream(fileOut);
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
                    zip.putNextEntry(entry("source/" + tree.getPathString(), commitTime));
                    db.open(tree.getObjectId(0)).copyTo(zip);
                    zip.closeEntry();
                    files++;
                }

                // The bundle is already compressed, so it is stored as-is.
                ZipParameters bundleEntry = entry(bundleName, System.currentTimeMillis());
                bundleEntry.setCompressionMethod(CompressionMethod.STORE);
                bundleEntry.setEntrySize(bundleSize);
                zip.putNextEntry(bundleEntry);
                Files.copy(bundle, zip);
                zip.closeEntry();

                for (ExtrasSource.ExtraFile extra : extraFiles) {
                    ZipParameters extraEntry = entry(extra.nameInZip(), System.currentTimeMillis());
                    if (extra.nameInZip().endsWith(".bundle")) {
                        extraEntry.setCompressionMethod(CompressionMethod.STORE);
                        extraEntry.setEntrySize(Files.size(extra.file()));
                    }
                    zip.putNextEntry(extraEntry);
                    Files.copy(extra.file(), zip);
                    zip.closeEntry();
                }

                zip.putNextEntry(entry("RESTORE.txt", System.currentTimeMillis()));
                zip.write(restoreNotes(repo, head, extraFiles).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            verifyZip(outZip, files + 2 + extraFiles.size(), bundleName, bundleSize);
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
        if (count == 0) {
            return 0;
        }
        // HEAD lets "git clone repo.bundle" check out a branch on its own. A bundle only records
        // HEAD's commit, not its branch name, so RESTORE.txt names the branch with -b.
        if (headId != null) {
            writer.include(Constants.HEAD, headId);
        }
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

    private ZipParameters entry(String name, long time) {
        ZipParameters parameters = new ZipParameters();
        parameters.setFileNameInZip(name);
        parameters.setLastModifiedFileTime(time);
        if (encrypting()) {
            parameters.setEncryptFiles(true);
            parameters.setEncryptionMethod(EncryptionMethod.AES);
            parameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
        }
        return parameters;
    }

    /** Reopens the finished ZIP (with the password when encrypted) before it replaces anything. */
    private void verifyZip(Path zip, int expectedEntries, String bundleName, long bundleSize) throws IOException {
        try (ZipFile file = new ZipFile(zip.toFile(), zipPassword)) {
            int entries = file.getFileHeaders().size();
            if (entries != expectedEntries) {
                throw new IOException("ZIP check failed: " + entries + " entries, expected " + expectedEntries + ".");
            }
            FileHeader bundle = file.getFileHeader(bundleName);
            if (bundle == null || bundle.getUncompressedSize() != bundleSize) {
                throw new IOException("ZIP check failed: the history bundle is missing or incomplete.");
            }
            if (file.isEncrypted() != encrypting()) {
                throw new IOException("ZIP check failed: encryption does not match the setting.");
            }
            // Reading an entry back proves the password opens the file.
            try (InputStream in = file.getInputStream(file.getFileHeader("RESTORE.txt"))) {
                in.readAllBytes();
            }
        }
    }

    private static String restoreNotes(RepoInfo repo, String head, List<ExtrasSource.ExtraFile> extraFiles) {
        String name = repo.name();
        List<String> extraLines = new ArrayList<>();
        boolean issues = false;
        boolean releases = false;
        for (ExtrasSource.ExtraFile extra : extraFiles) {
            issues |= extra.nameInZip().startsWith("issues/");
            releases |= extra.nameInZip().startsWith("releases/");
            if (extra.nameInZip().endsWith(".wiki.bundle")) {
                extraLines.add("  " + extra.nameInZip());
                extraLines.add("      Full history of the wiki: git clone " + extra.nameInZip() + " " + name + ".wiki");
            }
        }
        if (issues) {
            extraLines.add("  issues\\");
            extraLines.add("      Issues and pull requests with their comments, as JSON from the GitHub API.");
        }
        if (releases) {
            extraLines.add("  releases\\");
            extraLines.add("      Releases (releases.json) and, when included, the files attached to them.");
        }
        List<String> lines = new ArrayList<>(List.of(
                "Backup of " + RepoInfo.hostLabel(repo.host()) + " " + repo.fullName(),
                "Created : " + LocalDateTime.now().format(HUMAN),
                "Branch  : " + repo.defaultBranch() + " @ " + head,
                "",
                "Contents",
                "  source\\",
                "      Files of the default branch at the time of the backup.",
                "  " + name + ".bundle",
                "      Full history: every commit, branch and tag."));
        lines.addAll(extraLines);
        lines.addAll(List.of(
                "",
                "Restore the full repository (history included)",
                "  git clone -b " + repo.defaultBranch() + " " + name + ".bundle " + name,
                "  cd " + name,
                "  git remote set-url origin " + repo.cloneUrl(),
                "",
                "Restore as an exact mirror (all branches as local branches)",
                "  git clone --mirror " + name + ".bundle " + name + ".git",
                ""));
        return String.join("\r\n", lines);
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

    private static void deleteRecursively(Path dir) throws IOException {
        FileUtils.delete(dir.toFile(), FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING);
    }

    private static void deleteTreeQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            deleteRecursively(dir);
        } catch (IOException ignored) {
            // Cleaned up on a later run.
        }
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
