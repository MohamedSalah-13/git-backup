package com.gitbackup.backup;

import com.gitbackup.model.BackupState;
import com.gitbackup.model.RepoInfo;
import com.gitbackup.model.RunRecord;
import com.gitbackup.store.AppPaths;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.util.FileUtils;

/**
 * Checks the latest ZIP of every repository: every entry must read back completely (which
 * makes the ZIP library check each entry's CRC, or its authentication code when encrypted),
 * and every history bundle inside must load into an empty repository with all its objects.
 */
public final class BackupVerifier {
    public enum Outcome { OK, DAMAGED, SKIPPED }

    private final AppPaths paths;
    private final BackupLog log;

    public BackupVerifier(AppPaths paths, BackupLog log) {
        this.paths = paths;
        this.log = log;
    }

    /**
     * Checks every repository recorded in the destination's backup-state.json.
     *
     * @param password the saved ZIP password, or null; encrypted ZIPs are skipped without it
     * @param items    receives one line per repository
     */
    public int[] verifyAll(Path destination, BackupState state, char[] password, BooleanSupplier cancelled,
                           List<RunRecord.Item> items) {
        int ok = 0;
        int damaged = 0;
        int skipped = 0;
        int done = 0;
        log.progress(0, state.repos.size());
        for (Map.Entry<String, BackupState.Entry> repo : state.repos.entrySet()) {
            if (cancelled.getAsBoolean()) {
                log.warn("Stopped by the user; the remaining backups were not checked.");
                break;
            }
            String key = repo.getKey();
            BackupState.Entry entry = repo.getValue();
            Path zip = entry.file == null || entry.file.isBlank()
                    ? null
                    : RepoInfo.folderFor(destination, key).resolve(entry.file);
            Outcome outcome;
            String detail;
            if (zip == null || !Files.isRegularFile(zip)) {
                outcome = Outcome.DAMAGED;
                detail = "backup file is missing";
            } else if (entry.encrypted && (password == null || password.length == 0)) {
                outcome = Outcome.SKIPPED;
                detail = "encrypted, and no password is saved on this computer";
            } else {
                try {
                    int entries = verifyOne(zip, entry.encrypted ? password : null);
                    outcome = Outcome.OK;
                    detail = zip.getFileName() + " (" + entries + " entries)";
                } catch (Exception e) {
                    outcome = Outcome.DAMAGED;
                    detail = zip.getFileName() + ": " + BackupEngine.describe(e);
                }
            }
            switch (outcome) {
                case OK -> {
                    ok++;
                    log.ok(key + " : OK, " + detail);
                }
                case DAMAGED -> {
                    damaged++;
                    log.error(key + " : DAMAGED - " + detail);
                }
                case SKIPPED -> {
                    skipped++;
                    log.warn(key + " : not checked - " + detail);
                }
            }
            items.add(new RunRecord.Item(key, outcome.name(), detail));
            log.progress(++done, state.repos.size());
        }
        return new int[] {ok, damaged, skipped};
    }

    /** Reads one ZIP completely. Returns the number of entries. */
    public int verifyOne(Path zipFile, char[] password) throws Exception {
        paths.createDirectories();
        int entries = 0;
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            if (zip.isEncrypted()) {
                if (password == null) {
                    throw new IOException("the file is encrypted and no password was given");
                }
                zip.setPassword(password);
            }
            if (!zip.isValidZipFile()) {
                throw new IOException("not a readable ZIP file");
            }
            boolean history = false;
            byte[] buffer = new byte[64 * 1024];
            for (FileHeader header : zip.getFileHeaders()) {
                if (header.isDirectory()) {
                    continue;
                }
                entries++;
                if (header.getFileName().endsWith(".bundle")) {
                    checkBundle(zip, header);
                    history |= !header.getFileName().endsWith(".wiki.bundle");
                    continue;
                }
                try (InputStream in = zip.getInputStream(header)) {
                    while (in.read(buffer) >= 0) {
                        // Reading to the end makes the library check the entry.
                    }
                }
            }
            if (!history) {
                throw new IOException("the history bundle is missing");
            }
        } catch (ZipException e) {
            if (e.getType() == ZipException.Type.WRONG_PASSWORD) {
                throw new IOException("the saved password does not open this file", e);
            }
            throw e;
        }
        return entries;
    }

    /** Loads the bundle into an empty repository, which checks every object it holds. */
    private void checkBundle(ZipFile zip, FileHeader header) throws Exception {
        Path work = Files.createTempDirectory(paths.tmpDir(), "check-");
        try {
            Path bundle = work.resolve("history.bundle");
            try (InputStream in = zip.getInputStream(header)) {
                Files.copy(in, bundle, StandardCopyOption.REPLACE_EXISTING);
            }
            Path repo = work.resolve("repo.git");
            try (Git git = Git.init().setBare(true).setDirectory(repo.toFile()).call()) {
                int refs = git.fetch()
                        .setRemote(bundle.toFile().getAbsolutePath())
                        .setRefSpecs(new RefSpec("+refs/*:refs/*"))
                        .setCheckFetchedObjects(true)
                        .call()
                        .getTrackingRefUpdates().size();
                if (refs == 0) {
                    throw new IOException(header.getFileName() + " has no branches");
                }
            }
        } finally {
            FileUtils.delete(work.toFile(), FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING);
        }
    }
}
