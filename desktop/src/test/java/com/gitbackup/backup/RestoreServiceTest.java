package com.gitbackup.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gitbackup.backup.RestoreService.BackupVersion;
import com.gitbackup.model.BackupState;
import com.gitbackup.model.RepoInfo;
import com.gitbackup.store.AppPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestoreServiceTest {
    private static final PersonIdent AUTHOR = new PersonIdent("Tester", "tester@example.com");
    private static final BackupLog QUIET = (level, message) -> { };

    @TempDir
    Path tmp;

    private Path destination;
    private Path parent;
    private RestoreService restore;
    private ObjectId sourceHead;

    @BeforeEach
    void setUp() throws Exception {
        Path source = tmp.resolve("source");
        destination = Files.createDirectories(tmp.resolve("dest"));
        parent = Files.createDirectories(tmp.resolve("restored"));
        try (Git git = Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
            commitFile(git, "README.md", "hello\n");
            commitFile(git, "src/Main.java", "class Main {}\n");
            git.tag().setName("v1.0").setAnnotated(true).setMessage("release").setTagger(AUTHOR).setSigned(false).call();
            // Two more branches on the same commit: "dev" sorts before "main".
            git.branchCreate().setName("dev").call();
            sourceHead = git.getRepository().resolve(Constants.HEAD);
        }
        AppPaths paths = new AppPaths(tmp.resolve("home"));
        RepoInfo repo = new RepoInfo("acme", "demo", true, false, false, 1, Instant.now(), "main",
                source.toFile().getAbsolutePath());
        BackupEngine.Result result = new BackupEngine(paths, QUIET).backupOne(repo, destination,
                new BackupState(), destination.resolve("backup-state.json"), 3, false, null);
        assertEquals(BackupEngine.Outcome.BACKED_UP, result.outcome());
        restore = new RestoreService(paths);
    }

    @Test
    void findsBackupsNewestFirstAndIgnoresOtherFiles() throws Exception {
        Path repoDir = destination.resolve("acme").resolve("demo");
        Files.writeString(repoDir.resolve("demo_2020-01-01_000000_abcdef1.zip"), "old");
        Files.writeString(repoDir.resolve("demo-manual-copy.zip"), "x");
        Files.writeString(repoDir.resolve("notes.txt"), "x");

        Map<String, List<BackupVersion>> found = restore.findBackups(destination);

        assertEquals(List.of("acme/demo"), List.copyOf(found.keySet()));
        List<BackupVersion> versions = found.get("acme/demo");
        assertEquals(2, versions.size());
        assertEquals("2020-01-01", versions.get(1).date());
        assertEquals("abcdef1", versions.get(1).head());
        assertTrue(versions.get(0).date().compareTo("2020-01-01") > 0);
    }

    @Test
    void restoresFullRepositoryOnItsDefaultBranch() throws Exception {
        Path target = restore.restoreRepository(newest(), parent, QUIET);

        assertEquals(parent.resolve("demo").toAbsolutePath(), target);
        try (Git git = Git.open(target.toFile())) {
            assertEquals(sourceHead, git.getRepository().resolve(Constants.HEAD));
            assertEquals("main", git.getRepository().getBranch());
            assertNotNull(git.getRepository().findRef("refs/remotes/origin/dev"));
            assertNotNull(git.getRepository().findRef("refs/tags/v1.0"));
            assertEquals("https://github.com/acme/demo.git",
                    git.getRepository().getConfig().getString("remote", "origin", "url"));
            assertTrue(git.status().call().isClean());
        }
        // A working copy follows the machine's Git line-ending setting, like any "git clone".
        assertEquals("class Main {}\n", Files.readString(target.resolve("src/Main.java")).replace("\r\n", "\n"));
    }

    @Test
    void restoresFilesOnlyWithoutHistory() throws Exception {
        Path target = restore.restoreFiles(newest(), parent, QUIET);

        assertEquals("hello\n", Files.readString(target.resolve("README.md")));
        assertTrue(Files.exists(target.resolve("src/Main.java")));
        assertFalse(Files.exists(target.resolve(".git")));
        assertFalse(Files.exists(target.resolve("demo.bundle")));
    }

    @Test
    void refusesToRestoreIntoFolderThatHasContent() throws Exception {
        Path existing = Files.createDirectories(parent.resolve("demo"));
        Files.writeString(existing.resolve("mine.txt"), "keep me");

        assertThrows(IOException.class, () -> restore.restoreRepository(newest(), parent, QUIET));
        assertThrows(IOException.class, () -> restore.restoreFiles(newest(), parent, QUIET));
        assertEquals("keep me", Files.readString(existing.resolve("mine.txt")));
    }

    private BackupVersion newest() throws IOException {
        return restore.findBackups(destination).get("acme/demo").get(0);
    }

    private static void commitFile(Git git, String path, String content) throws Exception {
        Path file = git.getRepository().getWorkTree().toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        git.add().addFilepattern(path).call();
        git.commit().setMessage("add " + path).setAuthor(AUTHOR).setCommitter(AUTHOR).setSign(false).call();
    }
}
