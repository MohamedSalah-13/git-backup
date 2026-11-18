package com.gitbackup.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.gitbackup.hosts.HostClient;
import com.gitbackup.hosts.HttpJson;
import com.gitbackup.model.RepoInfo;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

/** The GitHub REST calls the application needs. */
public final class GitHubClient implements HostClient {
    private static final String API = "https://api.github.com";
    private static final int PAGE_SIZE = 100;

    private final String token;
    private final boolean includeOrgs;
    private final HttpJson http;

    public GitHubClient(String token) {
        this(token, false);
    }

    /** @param includeOrgs also list repositories of organisations the account belongs to */
    public GitHubClient(String token, boolean includeOrgs) {
        this.token = token;
        this.includeOrgs = includeOrgs;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        headers.put("Authorization", "Bearer " + token);
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        this.http = new HttpJson("GitHub", headers);
    }

    @Override
    public String host() {
        return RepoInfo.GITHUB;
    }

    @Override
    public String login() throws IOException {
        return http.get(API + "/user").path("login").asText();
    }

    @Override
    public CredentialsProvider credentials() {
        return new UsernamePasswordCredentialsProvider("x-access-token", token);
    }

    @Override
    public List<RepoInfo> listRepos() throws IOException {
        String affiliation = includeOrgs ? "owner,organization_member" : "owner";
        List<RepoInfo> repos = new ArrayList<>();
        for (JsonNode node : getAll("/user/repos?affiliation=" + affiliation)) {
            repos.add(toRepo(node));
        }
        repos.sort(Comparator.comparing(RepoInfo::fullName, String.CASE_INSENSITIVE_ORDER));
        return repos;
    }

    /** Every repository the signed-in account owns, sorted by name. */
    public List<RepoInfo> listOwnedRepos() throws IOException {
        return new GitHubClient(token, false).listRepos();
    }

    /** Time of the most recent change to any issue or pull request, or "" when there are none. */
    public String latestIssueChange(RepoInfo repo) throws IOException {
        JsonNode newest = http.get(API + repoPath(repo) + "/issues?state=all&sort=updated&direction=desc&per_page=1");
        return newest.isArray() && !newest.isEmpty() ? newest.get(0).path("updated_at").asText("") : "";
    }

    /** All pages of a list endpoint, for example "/repos/o/r/releases". */
    public ArrayNode getAll(String path) throws IOException {
        ArrayNode all = HttpJson.mapper().createArrayNode();
        String separator = path.contains("?") ? "&" : "?";
        for (int page = 1; ; page++) {
            JsonNode batch = http.get(API + path + separator + "per_page=" + PAGE_SIZE + "&page=" + page);
            if (!batch.isArray() || batch.isEmpty()) {
                break;
            }
            all.addAll((ArrayNode) batch);
            if (batch.size() < PAGE_SIZE) {
                break;
            }
        }
        return all;
    }

    /** The first page of a list endpoint only. */
    public JsonNode getFirstPage(String path) throws IOException {
        String separator = path.contains("?") ? "&" : "?";
        return http.get(API + path + separator + "per_page=" + PAGE_SIZE);
    }

    /** Downloads a release file through its API address (works for private repositories too). */
    public void downloadAsset(String apiUrl, Path target) throws IOException {
        http.download(apiUrl, "application/octet-stream", target);
    }

    public static String repoPath(RepoInfo repo) {
        return "/repos/" + repo.owner() + "/" + repo.name();
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
                node.path("clone_url").asText(),
                RepoInfo.GITHUB,
                node.path("has_issues").asBoolean(),
                node.path("has_wiki").asBoolean());
    }
}
