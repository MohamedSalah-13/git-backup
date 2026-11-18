package com.gitbackup.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the last run saved for each repository. Stored as backup-state.json inside the
 * destination folder, in the same format the PowerShell version writes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BackupState {
    public int version = 1;
    public String updated = "";
    /** Keyed by owner/name (GitHub) or host:owner/name. */
    public Map<String, Entry> repos = new TreeMap<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        /** Hash of the default branch name and every branch and tag tip. */
        public String fingerprint = "";
        /**
         * Hash of what else went into the ZIP (issues, releases, wiki); empty when nothing
         * extra was backed up. Kept apart so the PowerShell version still reads the state.
         */
        public String extras = "";
        public String branch = "";
        public String head = "";
        /** ZIP file name inside the repository's folder. */
        public String file = "";
        public long size;
        /** True when the ZIP is password-protected. */
        public boolean encrypted;
        /** Local time, yyyy-MM-ddTHH:mm:ss. */
        public String backedUp = "";
        /** Clone address, so a restore can point "origin" back at the right host. */
        public String remote = "";
    }
}
