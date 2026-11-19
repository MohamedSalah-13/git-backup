package com.gitbackup.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gitbackup.model.RepoInfo;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChangeWatchTest {
    private static RepoInfo repo(String name, String pushed) {
        return new RepoInfo("me", name, false, false, false, 1, pushed == null ? null : Instant.parse(pushed), "main",
                "https://github.com/me/" + name + ".git");
    }

    @Test
    void firstLookOnlySetsTheStartingPoint() {
        ChangeWatch watch = new ChangeWatch();
        assertTrue(watch.update(List.of(repo("a", "2026-01-01T00:00:00Z"))).isEmpty());
    }

    @Test
    void reportsPushedAndNewRepositories() {
        ChangeWatch watch = new ChangeWatch();
        watch.update(List.of(repo("a", "2026-01-01T00:00:00Z"), repo("b", "2026-01-01T00:00:00Z"), repo("c", null)));
        List<RepoInfo> changed = watch.update(List.of(
                repo("a", "2026-01-02T00:00:00Z"),
                repo("b", "2026-01-01T00:00:00Z"),
                repo("c", null),
                repo("d", "2025-01-01T00:00:00Z")));
        assertEquals(List.of("a", "d"), changed.stream().map(RepoInfo::name).toList());
        // The same list again: nothing new.
        assertTrue(watch.update(List.of(repo("a", "2026-01-02T00:00:00Z"), repo("d", "2025-01-01T00:00:00Z")))
                .isEmpty());
    }

    @Test
    void dueAfterTheInterval() {
        assertFalse(ChangeWatch.isDue(false, 1, 0, 10 * 60_000));
        assertFalse(ChangeWatch.isDue(true, 5, 0, 4 * 60_000));
        assertTrue(ChangeWatch.isDue(true, 5, 0, 5 * 60_000));
        assertTrue(ChangeWatch.isDue(true, 0, 0, 60_000)); // a broken setting still means once a minute
    }

    @Test
    void nearestOfferedInterval() {
        assertEquals(1, ChangeWatch.nearestInterval(0));
        assertEquals(5, ChangeWatch.nearestInterval(4));
        assertEquals(60, ChangeWatch.nearestInterval(70));
        assertEquals(360, ChangeWatch.nearestInterval(10_000));
    }
}
