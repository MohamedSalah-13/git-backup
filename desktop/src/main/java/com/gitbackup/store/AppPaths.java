package com.gitbackup.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Where the application keeps its own files (settings, local mirrors, logs). */
public final class AppPaths {
    private final Path home;

    public AppPaths(Path home) {
        this.home = home.toAbsolutePath();
    }

    /**
     * %LOCALAPPDATA%\GitBackupDesktop, unless the "gitbackup.home" system property or the
     * GITBACKUP_HOME environment variable names another folder.
     */
    public static AppPaths defaults() {
        String override = System.getProperty("gitbackup.home", System.getenv("GITBACKUP_HOME"));
        if (override != null && !override.isBlank()) {
            return new AppPaths(Path.of(override));
        }
        String local = System.getenv("LOCALAPPDATA");
        Path base = local != null && !local.isBlank()
                ? Path.of(local)
                : Path.of(System.getProperty("user.home"), ".local", "share");
        return new AppPaths(base.resolve("GitBackupDesktop"));
    }

    public Path home() {
        return home;
    }

    public Path configFile() {
        return home.resolve("config.json");
    }

    public Path tokenFile() {
        return home.resolve("token.bin");
    }

    public Path lockFile() {
        return home.resolve("run.lock");
    }

    public Path mirrorsDir() {
        return home.resolve("mirrors");
    }

    public Path tmpDir() {
        return home.resolve("tmp");
    }

    public Path logsDir() {
        return home.resolve("logs");
    }

    public void createDirectories() throws IOException {
        for (Path dir : new Path[] {home, mirrorsDir(), tmpDir(), logsDir()}) {
            Files.createDirectories(dir);
        }
    }
}
