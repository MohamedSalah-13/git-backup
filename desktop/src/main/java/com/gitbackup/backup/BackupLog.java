package com.gitbackup.backup;

/** Receives what a backup run reports while it works. */
public interface BackupLog {
    enum Level { INFO, OK, WARN, ERROR }

    void log(Level level, String message);

    /** Called after each repository is handled. */
    default void progress(int done, int total) {
    }

    default void info(String message) {
        log(Level.INFO, message);
    }

    default void ok(String message) {
        log(Level.OK, message);
    }

    default void warn(String message) {
        log(Level.WARN, message);
    }

    default void error(String message) {
        log(Level.ERROR, message);
    }
}
