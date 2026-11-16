package com.gitbackup.backup;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** The rule for the daily automatic backup. */
public final class Schedule {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm", Locale.ROOT);

    private Schedule() {
    }

    /** Null when the text is not a valid HH:mm time. */
    public static LocalTime parseTime(String text) {
        if (text == null) {
            return null;
        }
        try {
            return LocalTime.parse(text.trim(), TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * True when today's run has not started yet and its time has come. A run missed because
     * the application was closed is therefore caught up the next time it is open that day;
     * a day that passes entirely with the application closed is skipped.
     *
     * @param lastRunDate yyyy-MM-dd of the last automatic run that started, or empty
     */
    public static boolean isDue(boolean enabled, String time, String lastRunDate, LocalDateTime now) {
        LocalTime at = parseTime(time);
        if (!enabled || at == null) {
            return false;
        }
        return !now.toLocalTime().isBefore(at) && !now.toLocalDate().toString().equals(lastRunDate);
    }
}
