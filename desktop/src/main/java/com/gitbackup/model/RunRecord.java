package com.gitbackup.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/** One backup run or backup check, as the history window shows it. Stored in history.json. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RunRecord {
    /** How the run started: "manual", "scheduled", "tray", "command" or "check". */
    public String kind = "manual";
    /** Local times, yyyy-MM-ddTHH:mm:ss. */
    public String started = "";
    public String finished = "";
    /** False when the run stopped before any repository was handled. */
    public boolean ran;
    public boolean stopped;
    /** 0 = all good, 1 = some problems, 2 = could not start. */
    public int exitCode;
    /** Why the run could not start, when it could not. */
    public String error = "";

    public int backedUp;
    public int unchanged;
    public int empty;
    public int failed;
    public int removed;

    /** For a check: ZIP files checked, found damaged, and skipped (no password). */
    public int checked;
    public int damaged;
    public int skipped;

    public List<Item> items = new ArrayList<>();

    /** One repository within the run. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Item {
        /** owner/name or host:owner/name. */
        public String repo = "";
        /** BACKED_UP, UNCHANGED, EMPTY, FAILED, or for a check OK, DAMAGED, SKIPPED. */
        public String outcome = "";
        /** The ZIP name, or what went wrong. */
        public String detail = "";

        public Item() {
        }

        public Item(String repo, String outcome, String detail) {
            this.repo = repo;
            this.outcome = outcome;
            this.detail = detail == null ? "" : detail;
        }
    }
}
