package com.gitbackup.hosts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitbackup.model.RepoInfo;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The fields read from GitLab and Bitbucket answers (trimmed copies of documented responses). */
class HostParsingTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void gitLabProject() throws Exception {
        RepoInfo repo = GitLabClient.toRepo(json.readTree("""
                {"path": "api-server", "namespace": {"full_path": "acme/backend"},
                 "visibility": "private", "archived": false,
                 "forked_from_project": {"id": 7},
                 "statistics": {"repository_size": 2097152},
                 "last_activity_at": "2026-11-10T08:30:00.000Z",
                 "default_branch": "main",
                 "http_url_to_repo": "https://gitlab.com/acme/backend/api-server.git"}
                """));
        assertEquals("acme/backend", repo.owner());
        assertEquals("api-server", repo.name());
        assertEquals("gitlab:acme/backend/api-server", repo.key());
        assertTrue(repo.isPrivate());
        assertTrue(repo.fork());
        assertEquals(2048, repo.sizeKb());
        assertEquals(Instant.parse("2026-11-10T08:30:00Z"), repo.pushedAt());
        assertEquals("https://gitlab.com/acme/backend/api-server.git", repo.cloneUrl());
    }

    @Test
    void emptyGitLabProjectHasNoBranch() throws Exception {
        RepoInfo repo = GitLabClient.toRepo(json.readTree("""
                {"path": "new", "namespace": {"full_path": "me"}, "visibility": "public",
                 "default_branch": null, "http_url_to_repo": "https://gitlab.com/me/new.git"}
                """));
        assertFalse(repo.isPrivate());
        assertFalse(repo.fork());
        assertEquals("", repo.defaultBranch());
    }

    @Test
    void bitbucketRepository() throws Exception {
        RepoInfo repo = BitbucketClient.toRepo(json.readTree("""
                {"slug": "mobile-app", "full_name": "team-x/mobile-app",
                 "workspace": {"slug": "team-x"}, "is_private": true, "parent": null,
                 "size": 1048576, "updated_on": "2026-11-12T10:00:00.123456+00:00",
                 "mainbranch": {"name": "develop"},
                 "links": {"clone": [{"name": "https", "href": "https://someone@bitbucket.org/team-x/mobile-app.git"}]}}
                """));
        assertEquals("team-x", repo.owner());
        assertEquals("mobile-app", repo.name());
        assertEquals("bitbucket:team-x/mobile-app", repo.key());
        assertFalse(repo.fork());
        assertEquals(1024, repo.sizeKb());
        assertEquals("develop", repo.defaultBranch());
        // No user name inside the address: git gets the token separately.
        assertEquals("https://bitbucket.org/team-x/mobile-app.git", repo.cloneUrl());
    }
}
