package com.gitbackup.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gitbackup.model.BackupState;
import com.gitbackup.model.RepoInfo;
import com.gitbackup.model.RunRecord;
import com.gitbackup.store.AppPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issues/releases/wiki inside the ZIP, the folder layout of other hosts, and the backup check. */
class ExtrasAndHostsTest {
    private static final PersonIdent AUTHOR = new PersonIdent("Tester", "tester@example.com");

    @TempDir
    Path tmp;

    private Path source;
    private Path destination;
    private Path stateFile;
    private AppPaths paths;
    private final List<String> lines = new ArrayList<>();
    private BackupLog log;

    @BeforeEach
    void setUp() throws Exception {
        source = tmp.resolve("source");
        destination = Files.createDirectories(tmp.resolve("dest"));
        stateFile = destination.resolve("backup-state.json");
        paths = new AppPaths(tmp.resolve("home"));
        log = (level, message) -> lines.add(level + " " + message);
        try (Git git = Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
            commit(git, source, "README.md", "hello\n");
        }
    }

    /** Stands in for the GitHub API. */
    private static final class FakeExtras implements ExtrasSource {
        String issuesVersion = "1";

        @Override
        public String fingerprint(RepoInfo repo) {
            return "issues:" + issuesVersion;
        }

        @Override
        public List<ExtraFile> collect(RepoInfo repo, Path workDir, BackupLog log) throws Exception {
            Path file = workDir.resolve("issues/issues.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "[{\"number\":1,\"version\":\"" + issuesVersion + "\"}]");
            return List.of(new ExtraFile("issues/issues.json", file));
        }
    }

    @Test
    void issuesAndWikiGoIntoTheZipAndTheirChangesTriggerANewBackup() throws Exception {
        Path wiki = tmp.resolve("source.wiki.git");
        try (Git git = Git.init().setDirectory(wiki.toFile()).setInitialBranch("master").call()) {
            commit(git, wiki, "Home.md", "# Welcome\n");
        }
        RepoInfo repo = new RepoInfo("acme", "demo", true, false, false, 1, Instant.now(), "main",
                source.toFile().getAbsolutePath(), RepoInfo.GITHUB, true, true);
        FakeExtras extras = new FakeExtras();
        BackupEngine engine = new BackupEngine(paths, log);
        engine.setExtras(extras, true);
        BackupState state = new BackupState();

        assertEquals(BackupEngine.Outcome.BACKED_UP,
                engine.backupOne(repo, destination, state, stateFile, 3, false, null).outcome(), String.join("\n", lines));
        Path zip = repo.folderIn(destination).resolve(state.repos.get("acme/demo").file);
        try (ZipFile file = new ZipFile(zip.toFile())) {
            assertNotNull(file.getEntry("issues/issues.json"));
            assertNotNull(file.getEntry("demo.wiki.bundle"));
            String notes = new String(file.getInputStream(file.getEntry("RESTORE.txt")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(notes.contains("demo.wiki.bundle"), notes);
            assertTrue(notes.contains("issues"), notes);
        }

        assertEquals(BackupEngine.Outcome.UNCHANGED,
                engine.backupOne(repo, destination, state, stateFile, 3, false, null).outcome());

        extras.issuesVersion = "2"; // a new comment on an issue, no new commit
        assertEquals(BackupEngine.Outcome.BACKED_UP,
                engine.backupOne(repo, destination, state, stateFile, 3, false, null).outcome());
        assertTrue(lines.stream().anyMatch(line -> line.contains("issues, releases or wiki changed")),
                String.join("\n", lines));

        // The check reads the code bundle and the wiki bundle.
        Path latest = repo.folderIn(destination).resolve(state.repos.get("acme/demo").file);
        assertEquals(5, new BackupVerifier(paths, log).verifyOne(latest, null));
    }

    @Test
    void otherHostsGetTheirOwnFolderAndRestoreFindsThem() throws Exception {
        RepoInfo gitlab = new RepoInfo("group/sub", "demo", true, false, false, 1, Instant.now(), "main",
                source.toFile().getAbsolutePath(), RepoInfo.GITLAB, false, false);
        // A GitHub account that happens to be called "gitlab" keeps the plain owner/name layout.
        RepoInfo github = new RepoInfo("gitlab", "tool", true, false, false, 1, Instant.now(), "main",
                source.toFile().getAbsolutePath());
        BackupEngine engine = new BackupEngine(paths, log);
        BackupState state = new BackupState();
        engine.backupOne(gitlab, destination, state, stateFile, 3, false, null);
        engine.backupOne(github, destination, state, stateFile, 3, false, null);

        assertNotNull(state.repos.get("gitlab:group/sub/demo"), state.repos.keySet().toString());
        assertNotNull(state.repos.get("gitlab/tool"), state.repos.keySet().toString());
        assertTrue(Files.isDirectory(destination.resolve("gitlab/group/sub/demo")));
        assertTrue(Files.isDirectory(destination.resolve("gitlab/tool")));

        RestoreService restore = new RestoreService(paths);
        Map<String, List<RestoreService.BackupVersion>> found = restore.findBackups(destination);
        assertEquals(2, found.size(), found.keySet().toString());
        RestoreService.BackupVersion version = found.get("gitlab:group/sub/demo").get(0);
        assertEquals(RepoInfo.GITLAB, version.host());
        assertEquals("group/sub", version.owner());
        assertEquals(RepoInfo.GITHUB, found.get("gitlab/tool").get(0).host());

        Path target = restore.restoreRepository(version, Files.createDirectories(tmp.resolve("out")), null, log);
        try (Git git = Git.open(target.toFile())) {
            // "origin" points at the address recorded with the backup.
            assertEquals(source.toFile().getAbsolutePath(),
                    git.getRepository().getConfig().getString("remote", "origin", "url"));
        }
    }

    @Test
    void checkReportsFineDamagedMissingAndSkippedBackups() throws Exception {
        BackupEngine engine = new BackupEngine(paths, log);
        BackupState state = new BackupState();
        for (String name : List.of("good", "broken", "gone")) {
            Path repoSource = tmp.resolve("src-" + name);
            try (Git git = Git.init().setDirectory(repoSource.toFile()).setInitialBranch("main").call()) {
                commit(git, repoSource, "file.txt", name.repeat(2000) + "\n");
            }
            engine.backupOne(new RepoInfo("acme", name, false, false, false, 1, Instant.now(), "main",
                    repoSource.toFile().getAbsolutePath()), destination, state, stateFile, 3, false, null);
        }
        Path broken = destination.resolve("acme/broken").resolve(state.repos.get("acme/broken").file);
        byte[] bytes = Files.readAllBytes(broken);
        for (int i = bytes.length / 3; i < bytes.length / 3 + 40; i++) {
            bytes[i] ^= 0x5A;
        }
        Files.write(broken, bytes);
        Files.delete(destination.resolve("acme/gone").resolve(state.repos.get("acme/gone").file));
        BackupState.Entry locked = new BackupState.Entry();
        locked.file = state.repos.get("acme/good").file;
        locked.encrypted = true;
        state.repos.put("acme/locked", locked);
        Files.createDirectories(destination.resolve("acme/locked"));
        Files.copy(destination.resolve("acme/good").resolve(locked.file), destination.resolve("acme/locked")
                .resolve(locked.file));

        List<RunRecord.Item> items = new ArrayList<>();
        int[] counts = new BackupVerifier(paths, log).verifyAll(destination, state, null, () -> false, items);

        assertEquals(1, counts[0], String.join("\n", lines));
        assertEquals(2, counts[1], String.join("\n", lines));
        assertEquals(1, counts[2], String.join("\n", lines));
        Map<String, String> outcomes = new java.util.HashMap<>();
        for (RunRecord.Item item : items) {
            outcomes.put(item.repo, item.outcome);
        }
        assertEquals(Map.of("acme/good", "OK", "acme/broken", "DAMAGED", "acme/gone", "DAMAGED",
                "acme/locked", "SKIPPED"), outcomes);
    }

    @Test
    void keysAndFoldersOfEachHost() {
        Path dest = Path.of("D:", "backup");
        assertEquals("acme/demo", RepoInfo.keyOf(RepoInfo.GITHUB, "acme/demo"));
        assertEquals("bitbucket:team/demo", RepoInfo.keyOf(RepoInfo.BITBUCKET, "team/demo"));
        assertEquals(dest.resolve("acme").resolve("demo"), RepoInfo.folderFor(dest, "acme/demo"));
        assertEquals(dest.resolve("gitlab").resolve("a").resolve("b").resolve("demo"),
                RepoInfo.folderFor(dest, "gitlab:a/b/demo"));
        assertEquals(RepoInfo.GITLAB, RepoInfo.hostOf("gitlab:a/b/demo"));
        assertEquals("a/b/demo", RepoInfo.fullNameOf("gitlab:a/b/demo"));
        assertEquals("https://github.com/acme/demo.wiki.git",
                new RepoInfo("acme", "demo", false, false, false, 0, null, "main",
                        "https://github.com/acme/demo.git").wikiUrl());
    }

    private static void commit(Git git, Path dir, String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        git.add().addFilepattern(name).call();
        git.commit().setMessage("add " + name).setAuthor(AUTHOR).setCommitter(AUTHOR).setSign(false).call();
    }
}
