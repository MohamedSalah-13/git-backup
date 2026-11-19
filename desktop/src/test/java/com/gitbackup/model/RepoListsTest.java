package com.gitbackup.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RepoListsTest {
    @Test
    void createAddRemoveAndFind() {
        Map<String, List<String>> lists = new LinkedHashMap<>();
        RepoLists.create(lists, "Work", List.of("me/a", "me/a", "me/b"));
        RepoLists.create(lists, "Tools", List.of());
        RepoLists.add(lists, "Tools", List.of("me/b", "gitlab:g/c"));
        RepoLists.add(lists, "Tools", List.of("me/b"));
        assertEquals(List.of("me/a", "me/b"), lists.get("Work"));
        assertEquals(List.of("me/b", "gitlab:g/c"), lists.get("Tools"));
        assertEquals(List.of("Work", "Tools"), RepoLists.listsOf(lists, "me/b"));

        RepoLists.remove(lists, "Work", List.of("me/b"));
        assertFalse(RepoLists.contains(lists, "Work", "me/b"));
        assertTrue(RepoLists.contains(lists, "Work", "me/a"));
    }

    @Test
    void renameKeepsTheOrder() {
        Map<String, List<String>> lists = new LinkedHashMap<>();
        RepoLists.create(lists, "A", List.of("x"));
        RepoLists.create(lists, "B", List.of());
        RepoLists.create(lists, "C", List.of());
        RepoLists.rename(lists, "B", "Bee");
        assertEquals(List.of("A", "Bee", "C"), List.copyOf(lists.keySet()));
    }

    @Test
    void namesAreTrimmedAndUnique() {
        Map<String, List<String>> lists = new LinkedHashMap<>();
        RepoLists.create(lists, "Work", List.of());
        assertNull(RepoLists.clean("   "));
        assertEquals("Home", RepoLists.clean("  Home "));
        assertTrue(RepoLists.taken(lists, "work", null));
        assertFalse(RepoLists.taken(lists, "work", "Work")); // renaming a list to itself
    }

    @Test
    void githubListsComeFirstAndFollowGitHub() {
        Map<String, List<String>> lists = new LinkedHashMap<>();
        Map<String, String> urls = new LinkedHashMap<>();
        RepoLists.create(lists, "Mine", List.of("me/a"));
        RepoLists.create(lists, "Core", List.of("me/z"));

        Map<String, String> renamed = new LinkedHashMap<>();
        assertTrue(RepoLists.syncGithub(lists, urls, List.of(
                new RemoteList("Core", "https://github.com/stars/me/lists/core", List.of("me/a", "me/b")),
                new RemoteList("Archive", "https://github.com/stars/me/lists/archive", List.of("me/c"))), renamed));
        assertEquals(List.of("Core", "Archive", "Mine", "Core (2)"), List.copyOf(lists.keySet()));
        assertEquals(Map.of("Core", "Core (2)"), renamed);
        assertEquals(List.of("me/a", "me/b"), lists.get("Core"));
        assertEquals(List.of("me/z"), lists.get("Core (2)")); // the application's own list is kept
        assertEquals(List.of("Core", "Archive"), List.copyOf(urls.keySet()));

        // The same answer again changes nothing.
        assertFalse(RepoLists.syncGithub(lists, urls, List.of(
                new RemoteList("Core", "https://github.com/stars/me/lists/core", List.of("me/a", "me/b")),
                new RemoteList("Archive", "https://github.com/stars/me/lists/archive", List.of("me/c"))),
                new LinkedHashMap<>()));

        // A list deleted or emptied on GitHub follows here.
        assertTrue(RepoLists.syncGithub(lists, urls, List.of(
                new RemoteList("Core", "https://github.com/stars/me/lists/core", List.of())), new LinkedHashMap<>()));
        assertEquals(List.of("Core", "Mine", "Core (2)"), List.copyOf(lists.keySet()));
        assertEquals(List.of(), lists.get("Core"));

        assertTrue(RepoLists.dropGithub(lists, urls));
        assertEquals(List.of("Mine", "Core (2)"), List.copyOf(lists.keySet()));
        assertTrue(urls.isEmpty());
        assertFalse(RepoLists.dropGithub(lists, urls));
    }
}
