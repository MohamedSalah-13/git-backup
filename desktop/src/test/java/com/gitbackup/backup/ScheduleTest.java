package com.gitbackup.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class ScheduleTest {
    private static LocalDateTime at(String time) {
        return LocalDateTime.parse("2026-11-16T" + time);
    }

    @Test
    void runsOnceADayFromTheScheduledTimeOn() {
        assertFalse(Schedule.isDue(true, "21:00", "", at("20:59:59")));
        assertTrue(Schedule.isDue(true, "21:00", "", at("21:00:00")));
        assertTrue(Schedule.isDue(true, "21:00", "2026-11-15", at("21:00:30")));
        assertFalse(Schedule.isDue(true, "21:00", "2026-11-16", at("21:05:00")));
    }

    @Test
    void catchesUpLaterTheSameDayWhenTheTimeWasMissed() {
        assertTrue(Schedule.isDue(true, "09:00", "2026-11-15", at("23:30:00")));
        // The next morning, before the time: yesterday's missed run is not made up.
        assertFalse(Schedule.isDue(true, "09:00", "2026-11-15", at("08:00:00")));
    }

    @Test
    void neverRunsWhenOffOrWhenTheTimeIsInvalid() {
        assertFalse(Schedule.isDue(false, "21:00", "", at("22:00:00")));
        assertFalse(Schedule.isDue(true, "25:00", "", at("22:00:00")));
        assertFalse(Schedule.isDue(true, null, "", at("22:00:00")));
    }

    @Test
    void parsesTimesWithOrWithoutLeadingZero() {
        assertEquals(LocalTime.of(9, 5), Schedule.parseTime("09:05"));
        assertEquals(LocalTime.of(9, 5), Schedule.parseTime(" 9:05 "));
        assertNull(Schedule.parseTime("9"));
        assertNull(Schedule.parseTime("abc"));
    }

    @Test
    void checkIsDueAfterTheIntervalOrWhenItNeverRan() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 18);
        assertTrue(Schedule.isCheckDue(true, 7, "", today));
        assertTrue(Schedule.isCheckDue(true, 7, "2026-11-11", today));
        assertFalse(Schedule.isCheckDue(true, 7, "2026-11-12", today));
        assertFalse(Schedule.isCheckDue(false, 7, "", today));
        assertTrue(Schedule.isCheckDue(true, 7, "not a date", today));
    }
}
