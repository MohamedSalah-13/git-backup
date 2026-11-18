package com.gitbackup.backup;

import com.gitbackup.model.BackupState;
import com.gitbackup.model.RepoInfo;
import com.gitbackup.store.AppPaths;
import com.gitbackup.store.JsonStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.util.FileUtils;

/**
 * Restores a backup ZIP into a new folder. Works from the destination folder alone, so a
 * repository that no longer exists on GitHub can still be restored.
 */
public final class RestoreService {
    /**
     * One ZIP of one repository.
     *
     * @param destination the backup folder the ZIP was found in
     */
    public record BackupVersion(String host, String owner, String name, Path zip, String date, String time,
                                String head, long size, Path destination) {
        public String fullName() {
            return owner + "/" + name;
        }

        /** owner/name on GitHub, host:owner/name elsewhere, as in backup-state.json. */
        public String key() {
            return RepoInfo.keyOf(host, fullName());
        }
    }

    /** GitHub keeps owner/name; GitLab groups can nest, so other hosts are searched deeper. */
    private static final int MAX_DEPTH = 10;

    private static final Pattern STAMP = Pattern.compile("_(\\d{4}-\\d{2}-\\d{2})_(\\d{6})_([0-9a-f]{7,40})\\.zip$");
    private static final String SOURCE_PREFIX = "source/";

    private final AppPaths paths;

    public RestoreService(AppPaths paths) {
        this.paths = paths;
    }

    /**
     * Every backup found under {@code destination}, keyed like backup-state.json (owner/name,
     * or host:owner/name outside GitHub), newest version first.
     */
    public Map<String, List<BackupVersion>> findBackups(Path destination) throws IOException {
        Map<String, List<BackupVersion>> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (!Files.isDirectory(destination)) {
            return result;
        }
        collect(destination, destination, 0, result);
        return result;
    }

    private static void collect(Path destination, Path dir, int depth, Map<String, List<BackupVersion>> result)
            throws IOException {
        if (depth >= 2) {
            List<String> parts = new ArrayList<>();
            for (Path part : destination.relativize(dir)) {
                parts.add(part.toString());
            }
            String host = null;
            if (parts.size() == 2) {
                host = RepoInfo.GITHUB;
            } else if (parts.size() >= 3 && (RepoInfo.GITLAB.equals(parts.get(0))
                    || RepoInfo.BITBUCKET.equals(parts.get(0)))) {
                host = parts.get(0);
                parts = parts.subList(1, parts.size());
            }
            if (host != null) {
                String owner = String.join("/", parts.subList(0, parts.size() - 1));
                List<BackupVersion> versions = versionsIn(host, owner, dir, destination);
                if (!versions.isEmpty()) {
                    result.put(versions.get(0).key(), versions);
                }
            }
        }
        if (depth >= MAX_DEPTH) {
            return;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path child : children) {
                // GitHub stops at owner/name; only the host folders lead deeper.
                if (depth <= 1 || isHostFolder(destination, child)) {
                    collect(destination, child, depth + 1, result);
                }
            }
        }
    }

    /** True for folders below destination/gitlab or destination/bitbucket. */
    private static boolean isHostFolder(Path destination, Path dir) {
        Path relative = destination.relativize(dir);
        String first = relative.getNameCount() == 0 ? "" : relative.getName(0).toString();
        return RepoInfo.GITLAB.equals(first) || RepoInfo.BITBUCKET.equals(first);
    }

    private static List<BackupVersion> versionsIn(String host, String owner, Path repoDir, Path destination)
            throws IOException {
        String name = repoDir.getFileName().toString();
        List<BackupVersion> versions = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(repoDir, "*.zip")) {
            for (Path zip : files) {
                String fileName = zip.getFileName().toString();
                Matcher matcher = STAMP.matcher(fileName);
                if (fileName.startsWith(name + "_") && matcher.find() && matcher.start() == name.length()) {
                    versions.add(new BackupVersion(host, owner, name, zip, matcher.group(1), matcher.group(2),
                            matcher.group(3), Files.size(zip), destination));
                }
            }
        }
        versions.sort(Comparator.comparing((BackupVersion v) -> v.zip().getFileName().toString()).reversed());
        return versions;
    }

    /**
     * Restores the full repository (every commit, branch and tag) as a working copy in
     * {@code parent/name}, with "origin" pointing back at GitHub. Returns the new folder.
     *
     * @param password the ZIP password; may be null for a backup that is not encrypted
     * @throws PasswordRequiredException if the ZIP is encrypted and the password is missing or wrong
     * @throws IOException if the target folder already has content; nothing is overwritten
     */
    public Path restoreRepository(BackupVersion version, Path parent, char[] password, BackupLog log)
            throws Exception {
        Path target = prepareTarget(version, parent);
        paths.createDirectories();
        Path bundle = paths.tmpDir().resolve("restore-" + version.name() + ".bundle");
        log.info("Restore " + version.fullName() + " : reading " + version.zip().getFileName());
        try {
            try (ZipFile zip = open(version, password)) {
                FileHeader entry = zip.getFileHeader(version.name() + ".bundle");
                if (entry == null) {
                    throw new IOException("This ZIP has no history bundle; only its files can be restored.");
                }
                copyEntry(zip, entry, bundle);
            }
            BackupState.Entry recorded = recorded(version);
            String branch = recorded == null || recorded.branch == null || recorded.branch.isBlank()
                    ? null : recorded.branch;
            try (Git git = Git.cloneRepository()
                    .setURI(bundle.toFile().getAbsolutePath())
                    .setDirectory(target.toFile())
                    .call()) {
                Repository db = git.getRepository();
                // A bundle records HEAD's commit but not its branch name, so the clone may have
                // guessed another branch that sits on the same commit.
                if (branch != null && !branch.equals(db.getBranch())
                        && db.findRef(Constants.R_REMOTES + "origin/" + branch) != null) {
                    boolean exists = db.findRef(Constants.R_HEADS + branch) != null;
                    git.checkout().setName(branch).setCreateBranch(!exists)
                            .setStartPoint("origin/" + branch)
                            .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                            .call();
                }
                StoredConfig config = db.getConfig();
                config.setString("remote", "origin", "url", originOf(version, recorded));
                config.save();
                ObjectId head = db.resolve(Constants.HEAD);
                log.ok("Restore " + version.fullName() + " : done, branch " + db.getBranch() + " @ "
                        + (head == null ? "?" : head.name().substring(0, 7)) + " -> " + target);
            }
            return target;
        } catch (Exception e) {
            deleteQuietly(target);
            throw e;
        } finally {
            Files.deleteIfExists(bundle);
        }
    }

    /** Restores only the files of the default branch (no history) into {@code parent/name}. */
    public Path restoreFiles(BackupVersion version, Path parent, char[] password, BackupLog log)
            throws IOException {
        Path target = prepareTarget(version, parent);
        log.info("Restore " + version.fullName() + " : extracting files from " + version.zip().getFileName());
        int count = 0;
        try (ZipFile zip = open(version, password)) {
            for (FileHeader entry : zip.getFileHeaders()) {
                String name = entry.getFileName().replace('\\', '/');
                if (!name.startsWith(SOURCE_PREFIX) || entry.isDirectory()) {
                    continue;
                }
                Path file = target.resolve(name.substring(SOURCE_PREFIX.length())).normalize();
                if (!file.startsWith(target)) {
                    throw new IOException("Unsafe path inside the ZIP: " + name);
                }
                Files.createDirectories(file.getParent());
                copyEntry(zip, entry, file);
                count++;
            }
        } catch (IOException | RuntimeException e) {
            deleteQuietly(target);
            throw e;
        }
        log.ok("Restore " + version.fullName() + " : done, " + count + " files -> " + target);
        return target;
    }

    private static ZipFile open(BackupVersion version, char[] password) throws IOException {
        ZipFile zip = new ZipFile(version.zip().toFile());
        if (zip.isEncrypted()) {
            if (password == null || password.length == 0) {
                zip.close();
                throw new PasswordRequiredException(false);
            }
            zip.setPassword(password);
        }
        return zip;
    }

    private static void copyEntry(ZipFile zip, FileHeader entry, Path file) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            Files.copy(in, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (ZipException e) {
            if (e.getType() == ZipException.Type.WRONG_PASSWORD) {
                throw new PasswordRequiredException(true);
            }
            throw e;
        }
    }

    /** The new folder parent/name; it must not already contain anything. */
    private static Path prepareTarget(BackupVersion version, Path parent) throws IOException {
        if (!Files.isDirectory(parent)) {
            throw new IOException("Folder '" + parent + "' does not exist.");
        }
        Path target = parent.resolve(version.name()).toAbsolutePath().normalize();
        if (Files.exists(target)) {
            boolean empty;
            try (Stream<Path> content = Files.list(target)) {
                empty = content.findAny().isEmpty();
            } catch (IOException e) {
                empty = false;
            }
            if (!empty) {
                throw new IOException("Folder '" + target + "' already exists and is not empty. "
                        + "Choose another folder; nothing was overwritten.");
            }
        }
        Files.createDirectories(target);
        return target;
    }

    /** What backup-state.json next to the backups says about the repository, when anything. */
    private static BackupState.Entry recorded(BackupVersion version) {
        try {
            return JsonStore.readState(version.destination().resolve("backup-state.json")).repos.get(version.key());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Where "origin" of the restored repository points. GitHub addresses are fixed; other hosts
     * use the address recorded with the backup (a GitLab server may be the company's own).
     */
    static String originOf(BackupVersion version, BackupState.Entry recorded) {
        if (!RepoInfo.GITHUB.equals(version.host()) && recorded != null && recorded.remote != null
                && !recorded.remote.isBlank()) {
            return recorded.remote;
        }
        return switch (version.host()) {
            case RepoInfo.GITLAB -> "https://gitlab.com/" + version.fullName() + ".git";
            case RepoInfo.BITBUCKET -> "https://bitbucket.org/" + version.fullName() + ".git";
            default -> "https://github.com/" + version.fullName() + ".git";
        };
    }

    private static void deleteQuietly(Path dir) {
        try {
            FileUtils.delete(dir.toFile(), FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING);
        } catch (IOException ignored) {
            // The caller reports the original failure.
        }
    }
}
