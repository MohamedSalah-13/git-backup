package com.gitbackup.hosts;

import com.fasterxml.jackson.databind.JsonNode;
import com.gitbackup.model.RepoInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

/**
 * Bitbucket Cloud with an Atlassian API token (scopes read:user:bitbucket and
 * read:repository:bitbucket). The REST API takes the account e-mail with the token; git
 * takes the fixed user name Bitbucket documents for API tokens.
 */
public final class BitbucketClient implements HostClient {
    private static final String API = "https://api.bitbucket.org/2.0";
    static final String GIT_USER = "x-bitbucket-api-token-auth";

    private final String token;
    private final boolean includeWorkspaces;
    private final HttpJson http;

    /**
     * @param email             e-mail of the Atlassian account the token belongs to
     * @param includeWorkspaces also list repositories of workspaces the account is a member of
     */
    public BitbucketClient(String email, String token, boolean includeWorkspaces) {
        this.token = token;
        this.includeWorkspaces = includeWorkspaces;
        String basic = Base64.getEncoder().encodeToString((email + ":" + token).getBytes(StandardCharsets.UTF_8));
        this.http = new HttpJson("Bitbucket", Map.of("Authorization", "Basic " + basic, "Accept", "application/json"));
    }

    @Override
    public String host() {
        return RepoInfo.BITBUCKET;
    }

    @Override
    public String login() throws IOException {
        JsonNode user = http.get(API + "/user");
        String name = user.path("username").asText("");
        return name.isEmpty() ? user.path("display_name").asText() : name;
    }

    @Override
    public CredentialsProvider credentials() {
        return new UsernamePasswordCredentialsProvider(GIT_USER, token);
    }

    @Override
    public List<RepoInfo> listRepos() throws IOException {
        String role = includeWorkspaces ? "member" : "owner";
        List<RepoInfo> repos = new ArrayList<>();
        String next = API + "/repositories?role=" + role + "&pagelen=100";
        for (int pages = 0; next != null && !next.isBlank() && pages < 200; pages++) {
            JsonNode page = http.get(next);
            for (JsonNode node : page.path("values")) {
                repos.add(toRepo(node));
            }
            next = page.path("next").asText(null);
        }
        repos.sort(Comparator.comparing(RepoInfo::fullName, String.CASE_INSENSITIVE_ORDER));
        return repos;
    }

    static RepoInfo toRepo(JsonNode node) {
        String fullName = node.path("full_name").asText();
        String workspace = node.path("workspace").path("slug").asText("");
        if (workspace.isEmpty() && fullName.contains("/")) {
            workspace = fullName.substring(0, fullName.indexOf('/'));
        }
        String slug = node.path("slug").asText();
        String updated = node.path("updated_on").asText("");
        return new RepoInfo(
                workspace,
                slug,
                node.path("is_private").asBoolean(),
                node.hasNonNull("parent"),
                false,
                node.path("size").asLong(0) / 1024,
                updated.isEmpty() ? null : OffsetDateTime.parse(updated).toInstant(),
                node.path("mainbranch").path("name").asText(""),
                // The clone links carry a user name; git gets the credentials separately.
                "https://bitbucket.org/" + workspace + "/" + slug + ".git",
                RepoInfo.BITBUCKET,
                false,
                false);
    }
}
