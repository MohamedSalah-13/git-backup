package com.gitbackup.store;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gitbackup.model.AppConfig;
import com.gitbackup.model.BackupState;
import com.gitbackup.model.RunRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;
import java.util.TreeMap;

/** Reads and writes the settings file and the backup state file. */
public final class JsonStore {
    public static final DateTimeFormatter ISO_LOCAL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private JsonStore() {
    }

    public static AppConfig readConfig(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new AppConfig();
        }
        AppConfig config = MAPPER.readValue(Files.readAllBytes(file), AppConfig.class);
        if (config.include == null) {
            config.include = new ArrayList<>();
        }
        if (config.exclude == null) {
            config.exclude = new ArrayList<>();
        }
        if (config.destination == null) {
            config.destination = "";
        }
        if (config.language == null || config.language.isBlank()) {
            config.language = "ar";
        }
        config.keepVersions = Math.max(1, config.keepVersions);
        config.verifyEveryDays = Math.max(1, config.verifyEveryDays);
        if (config.gitlabUrl == null || config.gitlabUrl.isBlank()) {
            config.gitlabUrl = "https://gitlab.com";
        }
        if (config.bitbucketUser == null) {
            config.bitbucketUser = "";
        }
        if (config.notify == null || config.notify.isBlank()) {
            config.notify = "always";
        }
        if (config.lastVerify == null) {
            config.lastVerify = "";
        }
        config.watchEveryMinutes = Math.max(1, config.watchEveryMinutes);
        if (config.lists == null) {
            config.lists = new java.util.LinkedHashMap<>();
        }
        config.lists.values().removeIf(java.util.Objects::isNull);
        if (config.shownList == null) {
            config.shownList = "";
        }
        return config;
    }

    public static void writeConfig(Path file, AppConfig config) throws IOException {
        writeAtomically(file, MAPPER.writeValueAsBytes(config));
    }

    /** A missing state file is a fresh start, not an error. */
    public static BackupState readState(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new BackupState();
        }
        BackupState state = MAPPER.readValue(Files.readAllBytes(file), BackupState.class);
        if (state.repos == null) {
            state.repos = new TreeMap<>();
        }
        return state;
    }

    public static void writeState(Path file, BackupState state) throws IOException {
        state.updated = LocalDateTime.now().format(ISO_LOCAL);
        writeAtomically(file, MAPPER.writeValueAsBytes(state));
    }

    /** The run history, newest first; a missing or unreadable file is an empty history. */
    public static List<RunRecord> readHistory(Path file) {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            List<RunRecord> runs = MAPPER.readValue(Files.readAllBytes(file), new TypeReference<List<RunRecord>>() {
            });
            return runs == null ? new ArrayList<>() : new ArrayList<>(runs);
        } catch (IOException | RuntimeException e) {
            return new ArrayList<>();
        }
    }

    public static void writeHistory(Path file, List<RunRecord> runs) throws IOException {
        writeAtomically(file, MAPPER.writeValueAsBytes(runs));
    }

    private static void writeAtomically(Path file, byte[] content) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, content);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
