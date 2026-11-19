package com.gitbackup.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Changes to the named repository lists of {@link AppConfig#lists}. A repository may be in
 * any number of lists; lists keep the order they were created in.
 */
public final class RepoLists {
    private RepoLists() {
    }

    /** The list name as stored: trimmed; null when it is empty. */
    public static String clean(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** True when another list already carries this name (case is ignored). */
    public static boolean taken(Map<String, List<String>> lists, String name, String except) {
        for (String existing : lists.keySet()) {
            if (!existing.equals(except) && existing.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static void create(Map<String, List<String>> lists, String name, Collection<String> keys) {
        List<String> members = new ArrayList<>();
        for (String key : keys) {
            if (!members.contains(key)) {
                members.add(key);
            }
        }
        lists.put(name, members);
    }

    /** Renames in place, keeping the list where it was in the order. */
    public static void rename(Map<String, List<String>> lists, String from, String to) {
        if (!lists.containsKey(from) || from.equals(to)) {
            return;
        }
        Map<String, List<String>> copy = new LinkedHashMap<>(lists);
        lists.clear();
        copy.forEach((name, members) -> lists.put(name.equals(from) ? to : name, members));
    }

    public static void add(Map<String, List<String>> lists, String name, Collection<String> keys) {
        List<String> members = lists.computeIfAbsent(name, ignored -> new ArrayList<>());
        for (String key : keys) {
            if (!members.contains(key)) {
                members.add(key);
            }
        }
    }

    public static void remove(Map<String, List<String>> lists, String name, Collection<String> keys) {
        List<String> members = lists.get(name);
        if (members != null) {
            members.removeAll(keys);
        }
    }

    public static boolean contains(Map<String, List<String>> lists, String name, String key) {
        List<String> members = lists.get(name);
        return members != null && members.contains(key);
    }

    /**
     * Replaces the lists brought from GitHub with {@code fetched}. GitHub's lists come first, in
     * GitHub's order, followed by the lists made in the application. A list made in the
     * application whose name GitHub now uses is kept under a free name such as "Work (2)".
     *
     * @param githubUrls the names of the lists that came from GitHub, with their pages; updated
     * @param renamed    receives the application's lists that moved to a free name (old to new)
     * @return true when anything changed
     */
    public static boolean syncGithub(Map<String, List<String>> lists, Map<String, String> githubUrls,
                                     List<RemoteList> fetched, Map<String, String> renamed) {
        List<Map.Entry<String, List<String>>> before = List.copyOf(copy(lists).entrySet());
        Map<String, String> urlsBefore = new LinkedHashMap<>(githubUrls);
        Map<String, List<String>> local = new LinkedHashMap<>();
        lists.forEach((name, members) -> {
            if (!githubUrls.containsKey(name)) {
                local.put(name, members);
            }
        });
        lists.clear();
        githubUrls.clear();
        for (RemoteList list : fetched) {
            if (taken(lists, list.name(), null)) {
                continue; // GitHub does not allow two lists with one name; ignore a repeat
            }
            create(lists, list.name(), list.keys());
            githubUrls.put(list.name(), list.url());
        }
        local.forEach((name, members) -> {
            String free = name;
            for (int n = 2; taken(lists, free, null); n++) {
                free = name + " (" + n + ")";
            }
            lists.put(free, members);
            if (!free.equals(name)) {
                renamed.put(name, free);
            }
        });
        return !before.equals(List.copyOf(lists.entrySet())) || !urlsBefore.equals(githubUrls);
    }

    /** Removes every list that came from GitHub. @return true when one was removed */
    public static boolean dropGithub(Map<String, List<String>> lists, Map<String, String> githubUrls) {
        boolean removed = lists.keySet().removeAll(githubUrls.keySet());
        boolean had = !githubUrls.isEmpty();
        githubUrls.clear();
        return removed || had;
    }

    private static Map<String, List<String>> copy(Map<String, List<String>> lists) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        lists.forEach((name, members) -> copy.put(name, new ArrayList<>(members)));
        return copy;
    }

    /** The names of the lists that hold {@code key}, in list order. */
    public static List<String> listsOf(Map<String, List<String>> lists, String key) {
        List<String> names = new ArrayList<>();
        lists.forEach((name, members) -> {
            if (members.contains(key)) {
                names.add(name);
            }
        });
        return names;
    }
}
