package com.gitbackup.store;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A small secret kept on disk, encrypted with Windows DPAPI: only the same Windows user on
 * the same machine can read it back. It is a convenience copy, not a backup of the secret.
 */
public final class SecretFile {
    private final Path file;

    public SecretFile(Path file) {
        this.file = file;
    }

    public boolean exists() {
        return load() != null;
    }

    /** The stored secret, or null when nothing readable is stored. */
    public char[] load() {
        if (!Platform.isWindows() || !Files.exists(file)) {
            return null;
        }
        try {
            byte[] plain = Crypt32Util.cryptUnprotectData(Files.readAllBytes(file));
            String value = new String(plain, StandardCharsets.UTF_8);
            return value.isEmpty() ? null : value.toCharArray();
        } catch (IOException | RuntimeException e) {
            return null; // copied from another machine or user
        }
    }

    public void save(char[] secret) throws IOException {
        if (!Platform.isWindows()) {
            throw new IOException("Saving a secret is only supported on Windows.");
        }
        byte[] encrypted = Crypt32Util.cryptProtectData(new String(secret).getBytes(StandardCharsets.UTF_8));
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.write(file, encrypted);
    }

    public void clear() throws IOException {
        Files.deleteIfExists(file);
    }
}
