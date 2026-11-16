package com.gitbackup.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitbackup.model.RepoInfo;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The few GitHub REST calls the application needs. */
public final class GitHubClient {
    private static final String API = "https://api.github.com";
    private static final int PAGE_SIZE = 100;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    private final ObjectMapper json = new ObjectMapper();
    private final String token;

    public GitHubClient(String token) {
        this.token = token;
    }

    /** The account name the token belongs to. */
    public String login() throws IOException {
        return get("/user").path("login").asText();
    }

    /** Every repository the signed-in account owns, sorted by name. */
    public List<RepoInfo> listOwnedRepos() throws IOException {
        List<RepoInfo> repos = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonNode batch = get("/user/repos?affiliation=owner&per_page=" + PAGE_SIZE + "&page=" + page);
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

    private static RepoInfo toRepo(JsonNode node) {
        String pushed = node.path("pushed_at").asText("");
        return new RepoInfo(
                node.path("owner").path("login").asText(),
                node.path("name").asText(),
                node.path("private").asBoolean(),
                node.path("fork").asBoolean(),
                node.path("archived").asBoolean(),
                node.path("size").asLong(),
                pushed.isEmpty() ? null : Instant.parse(pushed),
                node.path("default_branch").asText(""),
                node.path("clone_url").asText());
    }

    private JsonNode get(String path) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while contacting GitHub", e);
        }
        int status = response.statusCode();
        if (status == 401) {
            throw new GitHubException(GitHubException.Kind.UNAUTHORIZED, "GitHub rejected the sign-in (401).");
        }
        if (status == 403 || status == 429) {
            throw new GitHubException(GitHubException.Kind.FORBIDDEN,
                    "GitHub refused the request (" + status + "): rate limit or missing permission.");
        }
        if (status < 200 || status >= 300) {
            throw new GitHubException(GitHubException.Kind.OTHER, "GitHub answered with status " + status + ".");
        }
        return json.readTree(response.body());
    }
}
