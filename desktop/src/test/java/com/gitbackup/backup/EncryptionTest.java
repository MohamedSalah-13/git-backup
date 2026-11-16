package com.gitbackup.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gitbackup.backup.RestoreService.BackupVersion;
import com.gitbackup.model.BackupState;
import com.gitbackup.model.RepoInfo;
import com.gitbackup.store.AppPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncryptionTest {
    private static final PersonIdent AUTHOR = new PersonIdent("Tester", "tester@example.com");
    private static final char[] PASSWORD = "test-only-passphrase".toCharArray();
    private static final String MARKER = "PLAINTEXT-MARKER-7f3a";

    @TempDir
    Path tmp;

    private Path destination;
    private Path stateFile;
    private Path parent;
    private RepoInfo repo;
    private BackupEngine engine;
    private RestoreService restore;
    private ObjectId sourceHead;
    private final List<String> lines = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        Path source = tmp.resolve("source");
        destination = Files.createDirectories(tmp.resolve("dest"));
        stateFile = destination.resolve("backup-state.json");
        parent = Files.createDirectories(tmp.resolve("restored"));
        try (Git git = Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
            Files.writeString(source.resolve("secret.txt"), MARKER + "\n");
            Files.writeString(source.resolve("empty.txt"), "");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("first").setAuthor(AUTHOR).setCommitter(AUTHOR).setSign(false).call();
            sourceHead = git.getRepository().resolve(Constants.HEAD);
        }
        AppPaths paths = new AppPaths(tmp.resolve("home"));
        repo = new RepoInfo("acme", "demo", true, false, false, 1, Instant.now(), "main",
                source.toFile().getAbsolutePath());
        engine = new BackupEngine(paths, (level, message) -> lines.add(level + " " + message));
        restore = new RestoreService(paths);
    }

    @Test
    void encryptedBackupHidesContentAndRestoresWithThePassword() throws Exception {
        engine.setZipPassword(PASSWORD);
        BackupState state = new BackupState();

        assertEquals(BackupEngine.Outcome.BACKED_UP,
                engine.backupOne(repo, destination, state, stateFile, 3, false, null).outcome(),
                String.join("\n", lines));

        assertTrue(state.repos.get("acme/demo").encrypted);
        BackupVersion version = newest();
        try (ZipFile zip = new ZipFile(version.zip().toFile())) {
            assertTrue(zip.isEncrypted());
            for (FileHeader header : zip.getFileHeaders()) {
                assertTrue(header.isEncrypted(), header.getFileName() + " is not encrypted");
            }
        }
        // The file is small and the marker would be stored nearly as-is without encryption.
        String raw = new String(Files.readAllBytes(version.zip()), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains(MARKER));

        Path target = restore.restoreRepository(version, parent, PASSWORD, (level, message) -> { });
        try (Git git = Git.open(target.toFile())) {
            assertEquals(sourceHead, git.getRepository().resolve(Constants.HEAD));
        }
        assertTrue(Files.readString(target.resolve("secret.txt")).startsWith(MARKER));
    }

    @Test
    void restoreWithoutOrWithWrongPasswordIsRefusedAndLeavesNothingBehind() throws Exception {
        engine.setZipPassword(PASSWORD);
        engine.backupOne(repo, destination, new BackupState(), stateFile, 3, false, null);
        BackupVersion version = newest();
        BackupLog quiet = (level, message) -> { };

        PasswordRequiredException missing = assertThrows(PasswordRequiredException.class,
                () -> restore.restoreRepository(version, parent, null, quiet));
        assertFalse(missing.wrongPassword());
        PasswordRequiredException wrong = assertThrows(PasswordRequiredException.class,
                () -> restore.restoreFiles(version, parent, "not-the-password".toCharArray(), quiet));
        assertTrue(wrong.wrongPassword());
        assertFalse(Files.exists(parent.resolve("demo")));

        Path files = restore.restoreFiles(version, parent, PASSWORD, quiet);
        assertTrue(Files.readString(files.resolve("secret.txt")).startsWith(MARKER));
        assertEquals(0, Files.size(files.resolve("empty.txt")));
    }

    @Test
    void switchingEncryptionMakesAnUnchangedRepositoryBackUpAgain() throws Exception {
        BackupState state = new BackupState();
        engine.backupOne(repo, destination, state, stateFile, 3, false, null);
        assertFalse(state.repos.get("acme/demo").encrypted);
        try (ZipFile zip = new ZipFile(newest().zip().toFile())) {
            assertFalse(zip.isEncrypted());
        }

        engine.setZipPassword(PASSWORD);
        Thread.sleep(1100); // file names carry a one-second timestamp
        assertEquals(BackupEngine.Outcome.BACKED_UP,
                engine.backupOne(repo, destination, state, stateFile, 3, false, null).outcome());
        assertTrue(state.repos.get("acme/demo").encrypted);
        assertEquals(BackupEngine.Outcome.UNCHANGED,
                engine.backupOne(repo, destination, state, stateFile, 3, false, null).outcome());
    }

    private BackupVersion newest() throws Exception {
        return restore.findBackups(destination).get("acme/demo").get(0);
    }
}
