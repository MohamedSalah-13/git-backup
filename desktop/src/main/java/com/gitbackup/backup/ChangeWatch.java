package com.gitbackup.backup;

import com.gitbackup.model.RepoInfo;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers when each repository last changed, so that a fresh list can say which
 * repositories got new commits (or were created) since the previous one.
 *
 * <p>The first list only sets the starting point: nothing is reported for it.
 */
public final class ChangeWatch {
    /** The intervals offered in the settings, in minutes. */
    public static final int[] INTERVALS = {1, 5, 15, 30, 60, 180, 360};

    private final Map<String, Instant> seen = new HashMap<>();
    private boolean primed;

    /**
     * Records {@code repos} and returns those that changed since the previous call: pushed to
     * later than before, or not in the previous list at all. Empty on the first call.
     */
    public synchronized List<RepoInfo> update(List<RepoInfo> repos) {
        List<RepoInfo> changed = new ArrayList<>();
        Map<String, Instant> now = new HashMap<>();
        for (RepoInfo repo : repos) {
            now.put(repo.key(), repo.pushedAt());
            if (!primed) {
                continue;
            }
            if (!seen.containsKey(repo.key())) {
                changed.add(repo);
                continue;
            }
            Instant before = seen.get(repo.key());
            Instant after = repo.pushedAt();
            if (after != null && (before == null || after.isAfter(before))) {
                changed.add(repo);
            }
        }
        seen.clear();
        seen.putAll(now);
        primed = true;
        return changed;
    }

    /** True when watching is on and {@code everyMinutes} have passed since the last look. */
    public static boolean isDue(boolean enabled, int everyMinutes, long lastLookMillis, long nowMillis) {
        return enabled && nowMillis - lastLookMillis >= Math.max(1, everyMinutes) * 60_000L;
    }

    /** The offered interval closest to {@code minutes}, so a hand-edited setting still shows. */
    public static int nearestInterval(int minutes) {
        int best = INTERVALS[0];
        for (int interval : INTERVALS) {
            if (Math.abs(interval - minutes) < Math.abs(best - minutes)) {
                best = interval;
            }
        }
        return best;
    }
}
