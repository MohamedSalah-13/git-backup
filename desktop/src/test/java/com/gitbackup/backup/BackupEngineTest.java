package com.gitbackup.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gitbackup.model.BackupState;
import com.gitbackup.model.RepoInfo;
import com.gitbackup.store.AppPaths;
import com.gitbackup.store.JsonStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupEngineTest {
    private static final PersonIdent AUTHOR = new PersonIdent("Tester", "tester@example.com");

    @TempDir
    Path tmp;

    private Path source;
    private Path destination;
    private Path stateFile;
    private RepoInfo repo;
    private BackupEngine engine;
    private final List<String> lines = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        source = tmp.resolve("source");
        destination = Files.createDirectories(tmp.resolve("dest"));
        stateFile = destination.resolve("backup-state.json");
        try (Git git = Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
            commitFile(git, "README.md", "hello\n", "first");
            commitFile(git, "src/app/Main.java", "class Main {}\n", "second");
            git.tag().setName("v1.0").setAnnotated(true).setMessage("release").setTagger(AUTHOR).setSigned(false).call();
            git.branchCreate().setName("dev").call();
        }
        repo = new RepoInfo("acme", "demo", true, false, false, 10, Instant.now(), "main",
                source.toFile().getAbsolutePath());
        engine = new BackupEngine(new AppPaths(tmp.resolve("home")),
                (level, message) -> lines.add(level + " " + message));
    }

    @Test
    void firstRunCreatesZipWithSourceBundleAndNotes() throws Exception {
        BackupState state = new BackupState();

        BackupEngine.Result result = engine.backupOne(repo, destination, state, stateFile, 3, false, null);

        assertEquals(BackupEngine.Outcome.BACKED_UP, result.outcome(), String.join("\n", lines));
        BackupState.Entry entry = state.repos.get("acme/demo");
        assertNotNull(entry);
        Path zip = destination.resolve("acme").resolve("demo").resolve(entry.file);
        assertEquals(Files.size(zip), entry.size);
        try (ZipFile file = new ZipFile(zip.toFile())) {
            assertNotNull(file.getEntry("source/README.md"));
            assertNotNull(file.getEntry("source/src/app/Main.java"));
            assertNotNull(file.getEntry("demo.bundle"));
            assertNotNull(file.getEntry("RESTORE.txt"));
            assertEquals("hello\n", new String(
                    file.getInputStream(file.getEntry("source/README.md")).readAllBytes(), StandardCharsets.UTF_8));
        }
        // The state file on disk is what the next run reads.
        assertEquals(entry.fingerprint, JsonStore.readState(stateFile).repos.get("acme/demo").fingerprint);
    }

    @Test
    void unchangedRepositoryIsSkippedAndChangedOneGetsNewZip() throws Exception {
        BackupState state = new BackupState();
        engine.backupOne(repo, destination, state, stateFile, 3, false, null);
        String firstFile = state.repos.get("acme/demo").file;

        assertEquals(BackupEngine.Outcome.UNCHANGED,
                engine.backupOne(repo, destination, state, stateFile, 3, false, null).outcome());

        try (Git git = Git.open(source.toFile())) {
            commitFile(git, "CHANGELOG.md", "new\n", "third");
        }
        assertEquals(BackupEngine.Outcome.BACKED_UP,
                engine.backupOne(repo, destination, state, stateFile, 3, false, null).outcome(),
                String.join("\n", lines));
        String secondFile = state.repos.get("acme/demo").file;
        assertFalse(firstFile.equals(secondFile));
        assertEquals(2, zipCount());
    }

    @Test
    void missingZipIsRebuiltEvenWhenNothingChanged() throws Exception {
        BackupState state = new BackupState();
        engine.backupOne(repo, destination, state, stateFile, 3, false, null);
        Files.delete(destination.resolve("acme").resolve("demo").resolve(state.repos.get("acme/demo").file));

        assertEquals(BackupEngine.Outcome.BACKED_UP,
                engine.backupOne(repo, destination, state, stateFile, 3, false, null).outcome());
        assertEquals(1, zipCount());
    }

    @Test
    void bundleRestoresEveryBranchAndTag() throws Exception {
        BackupState state = new BackupState();
        engine.backupOne(repo, destination, state, stateFile, 3, false, null);
        Path zip = destination.resolve("acme").resolve("demo").resolve(state.repos.get("acme/demo").file);
        Path bundle = tmp.resolve("restored.bundle");
        try (ZipFile file = new ZipFile(zip.toFile());
             InputStream in = file.getInputStream(new ZipEntry("demo.bundle"))) {
            Files.copy(in, bundle);
        }

        Path restored = tmp.resolve("restored");
        // Same as the command in RESTORE.txt: git clone -b main demo.bundle
        try (Git clone = Git.cloneRepository().setURI(bundle.toFile().getAbsolutePath())
                .setBranch("main").setDirectory(restored.toFile()).call();
             Git original = Git.open(source.toFile())) {
            assertEquals(original.getRepository().resolve(Constants.HEAD),
                    clone.getRepository().resolve(Constants.HEAD));
            assertEquals("main", clone.getRepository().getBranch());
            assertNotNull(clone.getRepository().findRef("refs/remotes/origin/dev"));
            assertNotNull(clone.getRepository().findRef("refs/tags/v1.0"));
            assertTrue(Files.exists(restored.resolve("src/app/Main.java")));
        }
    }

    @Test
    void repositoryWithoutCommitsIsReportedEmpty() throws Exception {
        Path emptySource = tmp.resolve("empty");
        Git.init().setDirectory(emptySource.toFile()).setInitialBranch("main").call().close();
        RepoInfo empty = new RepoInfo("acme", "empty", false, false, false, 0, null, "main",
                emptySource.toFile().getAbsolutePath());

        assertEquals(BackupEngine.Outcome.EMPTY,
                engine.backupOne(empty, destination, new BackupState(), stateFile, 3, false, null).outcome());
    }

    /** Both versions of the tool share one state file, so the fingerprint must be identical. */
    @Test
    void fingerprintMatchesTheOneBuiltFromGitLsRemote() throws Exception {
        String gitOutput;
        try {
            Process process = new ProcessBuilder("git", "ls-remote", "--heads", "--tags",
                    source.toFile().getAbsolutePath()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            gitOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assumeTrue(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0, "git is not usable");
        } catch (IOException e) {
            assumeTrue(false, "git is not installed");
            return;
        }
        List<String> refLines = gitOutput.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
        String text = "default:main\n" + String.join("\n", refLines);
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));

        assertEquals(expected, engine.fingerprint(repo, null));
    }

    @Test
    void pruneKeepsNewestVersionsAndLeavesOtherFilesAlone() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("prune"));
        for (String name : List.of(
                "demo_2026-01-01_100000_aaaaaaa.zip",
                "demo_2026-01-02_100000_bbbbbbb.zip",
                "demo_2026-01-03_100000_ccccccc.zip",
                "demo_2026-01-04_100000_ddddddd.zip",
                "demo-manual-copy.zip",
                "notes.txt")) {
            Files.writeString(dir.resolve(name), "x");
        }

        int removed = BackupEngine.pruneOldBackups(dir, "demo", "demo_2026-01-04_100000_ddddddd.zip", 3);

        assertEquals(1, removed);
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(new TreeSet<>(List.of(
                            "demo-manual-copy.zip",
                            "demo_2026-01-02_100000_bbbbbbb.zip",
                            "demo_2026-01-03_100000_ccccccc.zip",
                            "demo_2026-01-04_100000_ddddddd.zip",
                            "notes.txt")),
                    new TreeSet<>(files.map(p -> p.getFileName().toString()).toList()));
        }
    }

    private long zipCount() throws IOException {
        try (Stream<Path> files = Files.list(destination.resolve("acme").resolve("demo"))) {
            return files.filter(p -> p.getFileName().toString().endsWith(".zip")).count();
        }
    }

    private static void commitFile(Git git, String path, String content, String message) throws Exception {
        Path file = git.getRepository().getWorkTree().toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        git.add().addFilepattern(path).call();
        git.commit().setMessage(message).setAuthor(AUTHOR).setCommitter(AUTHOR).setSign(false).call();
    }
}
