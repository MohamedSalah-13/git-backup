package com.gitbackup.hosts;

import com.fasterxml.jackson.databind.JsonNode;
import com.gitbackup.model.RepoInfo;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

/**
 * GitLab (gitlab.com or a company server) with a personal access token that has the
 * read_api and read_repository scopes.
 */
public final class GitLabClient implements HostClient {
    private static final int PAGE_SIZE = 100;

    private final String api;
    private final String token;
    private final boolean includeGroups;
    private final HttpJson http;

    /** @param includeGroups also list projects of groups the account is a member of */
    public GitLabClient(String serverUrl, String token, boolean includeGroups) {
        String base = serverUrl == null || serverUrl.isBlank() ? "https://gitlab.com" : serverUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.api = base + "/api/v4";
        this.token = token;
        this.includeGroups = includeGroups;
        this.http = new HttpJson("GitLab", Map.of("PRIVATE-TOKEN", token, "Accept", "application/json"));
    }

    @Override
    public String host() {
        return RepoInfo.GITLAB;
    }

    @Override
    public String login() throws IOException {
        return http.get(api + "/user").path("username").asText();
    }

    @Override
    public CredentialsProvider credentials() {
        return new UsernamePasswordCredentialsProvider("oauth2", token);
    }

    @Override
    public List<RepoInfo> listRepos() throws IOException {
        String filter = includeGroups ? "membership=true" : "owned=true";
        List<RepoInfo> repos = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonNode batch = http.get(api + "/projects?" + filter + "&statistics=true&order_by=path&sort=asc"
                    + "&per_page=" + PAGE_SIZE + "&page=" + page);
            if (!batch.isArray() || batch.isEmpty()) {
                break;
            }
            for (JsonNode node : batch) {
                repos.add(toRepo(node));
            }
            if (batch.size() < PAGE_SIZE) {
                break;
            }
        }
        repos.sort(Comparator.comparing(RepoInfo::fullName, String.CASE_INSENSITIVE_ORDER));
        return repos;
    }

    static RepoInfo toRepo(JsonNode node) {
        String activity = node.path("last_activity_at").asText("");
        long bytes = node.path("statistics").path("repository_size").asLong(0);
        return new RepoInfo(
                node.path("namespace").path("full_path").asText(),
                node.path("path").asText(),
                !"public".equals(node.path("visibility").asText()),
                node.hasNonNull("forked_from_project"),
                node.path("archived").asBoolean(),
                bytes / 1024,
                activity.isEmpty() ? null : OffsetDateTime.parse(activity).toInstant(),
                node.path("default_branch").asText(""),
                node.path("http_url_to_repo").asText(),
                RepoInfo.GITLAB,
                false,
                false);
    }
}
