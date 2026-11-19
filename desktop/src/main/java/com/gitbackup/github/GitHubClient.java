package com.gitbackup.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitbackup.hosts.HostClient;
import com.gitbackup.hosts.HttpJson;
import com.gitbackup.model.RemoteList;
import com.gitbackup.model.RepoInfo;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

/** The GitHub calls the application needs (REST, and GraphQL for star lists). */
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

    /**
     * The signed-in account's star lists (github.com/stars/LOGIN/lists) in GitHub's order, each
     * with the repositories in it. Lists hold any starred repository, not only the account's own.
     */
    public List<RemoteList> listLists() throws IOException {
        List<RemoteList> lists = new ArrayList<>();
        String after = null;
        do {
            JsonNode viewer = graphql(LISTS_QUERY, after).path("viewer");
            String login = viewer.path("login").asText();
            JsonNode page = viewer.path("lists");
            for (JsonNode list : page.path("nodes")) {
                List<String> keys = new ArrayList<>();
                JsonNode items = list.path("items");
                addRepositories(items, keys);
                while (items.path("pageInfo").path("hasNextPage").asBoolean()) {
                    items = graphql(LIST_ITEMS_QUERY, list.path("id").asText(),
                            items.path("pageInfo").path("endCursor").asText()).path("node").path("items");
                    addRepositories(items, keys);
                }
                String url = "https://github.com/stars/" + login + "/lists/"
                        + URLEncoder.encode(list.path("slug").asText(), StandardCharsets.UTF_8).replace("+", "%20");
                lists.add(new RemoteList(list.path("name").asText(), url, keys));
            }
            after = page.path("pageInfo").path("hasNextPage").asBoolean()
                    ? page.path("pageInfo").path("endCursor").asText() : null;
        } while (after != null);
        return lists;
    }

    private static final String LISTS_QUERY = """
            query($after: String) { viewer { login lists(first: 100, after: $after) {
              pageInfo { hasNextPage endCursor }
              nodes { id name slug items(first: 100) {
                pageInfo { hasNextPage endCursor }
                nodes { ... on Repository { nameWithOwner } } } } } } }""";

    private static final String LIST_ITEMS_QUERY = """
            query($id: ID!, $after: String) { node(id: $id) { ... on UserList { items(first: 100, after: $after) {
              pageInfo { hasNextPage endCursor }
              nodes { ... on Repository { nameWithOwner } } } } } }""";

    private static void addRepositories(JsonNode items, List<String> keys) {
        for (JsonNode item : items.path("nodes")) {
            String fullName = item.path("nameWithOwner").asText("");
            if (!fullName.isEmpty()) {
                keys.add(RepoInfo.keyOf(RepoInfo.GITHUB, fullName));
            }
        }
    }

    private JsonNode graphql(String query, String after) throws IOException {
        return graphql(query, null, after);
    }

    /** Runs a GraphQL query; GitHub reports its errors inside a normal 200 answer. */
    private JsonNode graphql(String query, String id, String after) throws IOException {
        ObjectNode body = HttpJson.mapper().createObjectNode();
        body.put("query", query);
        ObjectNode variables = body.putObject("variables");
        if (id != null) {
            variables.put("id", id);
        }
        if (after == null) {
            variables.putNull("after");
        } else {
            variables.put("after", after);
        }
        JsonNode answer = http.post(API + "/graphql", body);
        JsonNode errors = answer.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            JsonNode first = errors.get(0);
            String message = "GitHub: " + first.path("message").asText("the request failed");
            throw "INSUFFICIENT_SCOPES".equals(first.path("type").asText())
                    ? new GitHubException(GitHubException.Kind.FORBIDDEN, message, 200)
                    : new GitHubException(GitHubException.Kind.OTHER, message, 200);
        }
        return answer.path("data");
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
