package com.gitbackup.backup;

import com.gitbackup.model.BackupState;
import com.gitbackup.store.AppPaths;
import com.gitbackup.store.JsonStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
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
    /** One ZIP of one repository. */
    public record BackupVersion(String owner, String name, Path zip, String date, String time, String head,
                                long size) {
        public String fullName() {
            return owner + "/" + name;
        }
    }

    private static final Pattern STAMP = Pattern.compile("_(\\d{4}-\\d{2}-\\d{2})_(\\d{6})_([0-9a-f]{7,40})\\.zip$");
    private static final String SOURCE_PREFIX = "source/";

    private final AppPaths paths;

    public RestoreService(AppPaths paths) {
        this.paths = paths;
    }

    /** Every backup found under {@code destination}, keyed by owner/name, newest version first. */
    public Map<String, List<BackupVersion>> findBackups(Path destination) throws IOException {
        Map<String, List<BackupVersion>> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (!Files.isDirectory(destination)) {
            return result;
        }
        try (DirectoryStream<Path> owners = Files.newDirectoryStream(destination, Files::isDirectory)) {
            for (Path ownerDir : owners) {
                try (DirectoryStream<Path> repos = Files.newDirectoryStream(ownerDir, Files::isDirectory)) {
                    for (Path repoDir : repos) {
                        List<BackupVersion> versions = versionsIn(ownerDir.getFileName().toString(), repoDir);
                        if (!versions.isEmpty()) {
                            result.put(versions.get(0).fullName(), versions);
                        }
                    }
                }
            }
        }
        return result;
    }

    private static List<BackupVersion> versionsIn(String owner, Path repoDir) throws IOException {
        String name = repoDir.getFileName().toString();
        List<BackupVersion> versions = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(repoDir, "*.zip")) {
            for (Path zip : files) {
                String fileName = zip.getFileName().toString();
                Matcher matcher = STAMP.matcher(fileName);
                if (fileName.startsWith(name + "_") && matcher.find() && matcher.start() == name.length()) {
                    versions.add(new BackupVersion(owner, name, zip, matcher.group(1), matcher.group(2),
                            matcher.group(3), Files.size(zip)));
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
     * @throws IOException if the target folder already has content; nothing is overwritten
     */
    public Path restoreRepository(BackupVersion version, Path parent, BackupLog log) throws Exception {
        Path target = prepareTarget(version, parent);
        paths.createDirectories();
        Path bundle = paths.tmpDir().resolve("restore-" + version.name() + ".bundle");
        log.info("Restore " + version.fullName() + " : reading " + version.zip().getFileName());
        try {
            try (ZipFile zip = new ZipFile(version.zip().toFile())) {
                ZipEntry entry = zip.getEntry(version.name() + ".bundle");
                if (entry == null) {
                    throw new IOException("This ZIP has no history bundle; only its files can be restored.");
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, bundle, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            String branch = recordedBranch(version);
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
                config.setString("remote", "origin", "url",
                        "https://github.com/" + version.owner() + "/" + version.name() + ".git");
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
    public Path restoreFiles(BackupVersion version, Path parent, BackupLog log) throws IOException {
        Path target = prepareTarget(version, parent);
        log.info("Restore " + version.fullName() + " : extracting files from " + version.zip().getFileName());
        int count = 0;
        try (ZipFile zip = new ZipFile(version.zip().toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().startsWith(SOURCE_PREFIX) || entry.isDirectory()) {
                    continue;
                }
                Path file = target.resolve(entry.getName().substring(SOURCE_PREFIX.length())).normalize();
                if (!file.startsWith(target)) {
                    throw new IOException("Unsafe path inside the ZIP: " + entry.getName());
                }
                Files.createDirectories(file.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, file);
                }
                count++;
            }
        } catch (IOException | RuntimeException e) {
            deleteQuietly(target);
            throw e;
        }
        log.ok("Restore " + version.fullName() + " : done, " + count + " files -> " + target);
        return target;
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

    /** The default branch noted in backup-state.json next to the backups, when there is one. */
    private static String recordedBranch(BackupVersion version) {
        try {
            Path destination = version.zip().getParent().getParent().getParent();
            BackupState.Entry entry = JsonStore.readState(destination.resolve("backup-state.json"))
                    .repos.get(version.fullName());
            return entry == null || entry.branch == null || entry.branch.isBlank() ? null : entry.branch;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void deleteQuietly(Path dir) {
        try {
            FileUtils.delete(dir.toFile(), FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING);
        } catch (IOException ignored) {
            // The caller reports the original failure.
        }
    }
}
