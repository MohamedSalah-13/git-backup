package com.gitbackup.github;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Finds the GitHub token to use: one the user saved in the application, otherwise the
 * sign-in of GitHub CLI (gh) when it is installed.
 *
 * <p>A saved token is encrypted with Windows DPAPI, so only the same Windows user on the
 * same machine can read it back.
 */
public final class TokenStore {
    public enum Source { SAVED_TOKEN, GITHUB_CLI, NONE }

    public record Resolved(String token, Source source) {
        public boolean present() {
            return token != null && !token.isBlank();
        }
    }

    private final Path file;

    public TokenStore(Path file) {
        this.file = file;
    }

    public Resolved resolve() {
        String saved = readSaved();
        if (saved != null) {
            return new Resolved(saved, Source.SAVED_TOKEN);
        }
        String cli = readFromGitHubCli();
        if (cli != null) {
            return new Resolved(cli, Source.GITHUB_CLI);
        }
        return new Resolved(null, Source.NONE);
    }

    public boolean canSave() {
        return Platform.isWindows();
    }

    public void save(String token) throws IOException {
        if (!canSave()) {
            throw new IOException("Saving a token is only supported on Windows.");
        }
        byte[] encrypted = Crypt32Util.cryptProtectData(token.trim().getBytes(StandardCharsets.UTF_8));
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.write(file, encrypted);
    }

    public void clear() throws IOException {
        Files.deleteIfExists(file);
    }

    private String readSaved() {
        if (!Platform.isWindows() || !Files.exists(file)) {
            return null;
        }
        try {
            byte[] plain = Crypt32Util.cryptUnprotectData(Files.readAllBytes(file));
            String token = new String(plain, StandardCharsets.UTF_8).trim();
            return token.isEmpty() ? null : token;
        } catch (IOException | RuntimeException e) {
            // Unreadable (copied from another machine or user): behave as if nothing was saved.
            return null;
        }
    }

    private static String readFromGitHubCli() {
        try {
            Process process = new ProcessBuilder("gh", "auth", "token")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            process.getOutputStream().close();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 && !out.isEmpty() ? out : null;
        } catch (IOException e) {
            return null; // gh is not installed
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
