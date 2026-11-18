package com.gitbackup.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.gitbackup.github.GitHubClient;
import com.gitbackup.github.GitHubException;
import com.gitbackup.hosts.HttpJson;
import com.gitbackup.model.RepoInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Issues (with pull requests and comments) and releases of a GitHub repository, saved as
 * JSON inside the ZIP. Release files are downloaded only when asked for.
 */
public final class GitHubExtras implements ExtrasSource {
    private final GitHubClient client;
    private final boolean issues;
    private final boolean releases;
    private final boolean releaseAssets;

    public GitHubExtras(GitHubClient client, boolean issues, boolean releases, boolean releaseAssets) {
        this.client = client;
        this.issues = issues;
        this.releases = releases;
        this.releaseAssets = releases && releaseAssets;
    }

    public boolean active() {
        return issues || releases;
    }

    @Override
    public String fingerprint(RepoInfo repo) throws Exception {
        if (!repo.onGitHub()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        if (issues && repo.hasIssues()) {
            text.append("issues:").append(orEmpty(() -> client.latestIssueChange(repo))).append('\n');
        }
        if (releases) {
            text.append("releases").append(releaseAssets ? "+files" : "").append(':');
            // Every page, as collect() saves every page: a change on any of them counts.
            JsonNode list = orEmptyArray(() -> client.getAll(GitHubClient.repoPath(repo) + "/releases"));
            for (JsonNode release : list) {
                text.append(release.path("id").asText()).append('@').append(release.path("published_at").asText())
                        .append('#').append(release.path("name").asText()).append('/')
                        .append(release.path("body").asText().hashCode());
                for (JsonNode asset : release.path("assets")) {
                    text.append(',').append(asset.path("id").asText()).append(':').append(asset.path("size").asText());
                }
                text.append(';');
            }
            text.append('\n');
        }
        return text.toString();
    }

    @Override
    public List<ExtraFile> collect(RepoInfo repo, Path workDir, BackupLog log) throws Exception {
        List<ExtraFile> files = new ArrayList<>();
        if (!repo.onGitHub()) {
            return files;
        }
        String path = GitHubClient.repoPath(repo);
        if (issues && repo.hasIssues()) {
            ArrayNode all = orEmptyArray(() -> client.getAll(path + "/issues?state=all&direction=asc"));
            files.add(write(workDir, "issues/issues.json", all));
            files.add(write(workDir, "issues/comments.json",
                    orEmptyArray(() -> client.getAll(path + "/issues/comments?direction=asc"))));
            files.add(write(workDir, "issues/review-comments.json",
                    orEmptyArray(() -> client.getAll(path + "/pulls/comments?direction=asc"))));
            log.info("  issues and pull requests: " + all.size());
        }
        if (releases) {
            ArrayNode all = orEmptyArray(() -> client.getAll(path + "/releases"));
            files.add(write(workDir, "releases/releases.json", all));
            int downloaded = 0;
            if (releaseAssets) {
                Set<String> used = new HashSet<>();
                for (JsonNode release : all) {
                    String tag = release.path("tag_name").asText("untagged");
                    for (JsonNode asset : release.path("assets")) {
                        String name = assetPath(used, tag, release.path("id").asText(), asset.path("name").asText(),
                                asset.path("id").asText());
                        Path target = workDir.resolve(name);
                        client.downloadAsset(asset.path("url").asText(), target);
                        files.add(new ExtraFile(name, target));
                        downloaded++;
                    }
                }
            }
            log.info("  releases: " + all.size() + (releaseAssets ? ", files: " + downloaded : ""));
        }
        return files;
    }

    private static ExtraFile write(Path workDir, String name, JsonNode content) throws IOException {
        Path file = workDir.resolve(name);
        Files.createDirectories(file.getParent());
        ObjectWriter writer = HttpJson.mapper().writerWithDefaultPrettyPrinter();
        writer.writeValue(file.toFile(), content);
        return new ExtraFile(name, file);
    }

    /**
     * releases/tag/file inside the ZIP. Tags or file names that differ only in characters
     * Windows does not allow (release/1 and release_1), or only in case, would land on the
     * same path; the release or asset id then keeps them apart.
     *
     * @param used paths already handed out, compared without case; updated here
     */
    static String assetPath(Set<String> used, String tag, String releaseId, String fileName, String assetId) {
        String folder = safe(tag);
        String file = safe(fileName);
        String path = "releases/" + folder + "/" + file;
        if (used.contains(path.toLowerCase(Locale.ROOT))) {
            path = "releases/" + folder + "_" + releaseId + "/" + file;
        }
        if (used.contains(path.toLowerCase(Locale.ROOT))) {
            path = "releases/" + folder + "_" + releaseId + "/" + assetId + "_" + file;
        }
        used.add(path.toLowerCase(Locale.ROOT));
        return path;
    }

    /** Characters Windows does not allow in file names become "_". */
    static String safe(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..") ? "_" : cleaned;
    }

    private interface Call<T> {
        T get() throws IOException;
    }

    /** A turned-off issue tracker answers 404 or 410; that simply means there is nothing. */
    private static ArrayNode orEmptyArray(Call<JsonNode> call) throws IOException {
        try {
            JsonNode node = call.get();
            return node instanceof ArrayNode array ? array : HttpJson.mapper().createArrayNode();
        } catch (GitHubException e) {
            if (e.kind() == GitHubException.Kind.NOT_FOUND) {
                return HttpJson.mapper().createArrayNode();
            }
            throw e;
        }
    }

    private static String orEmpty(Call<String> call) throws IOException {
        try {
            return call.get();
        } catch (GitHubException e) {
            if (e.kind() == GitHubException.Kind.NOT_FOUND) {
                return "";
            }
            throw e;
        }
    }
}
