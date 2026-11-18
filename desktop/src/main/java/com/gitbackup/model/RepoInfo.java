package com.gitbackup.model;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A repository as reported by the hosting service.
 *
 * <p>GitHub repositories keep the original layout ({@code destination/owner/name}, state key
 * {@code owner/name}), so backups made by earlier versions and by the PowerShell scripts stay
 * valid. Other hosts get their own top folder ({@code destination/gitlab/group/name}) and a
 * state key that names the host ({@code gitlab:group/name}).
 *
 * @param owner     account, organisation, GitLab group path (may contain "/") or Bitbucket workspace
 * @param host      {@link #GITHUB}, {@link #GITLAB} or {@link #BITBUCKET}
 * @param hasIssues the repository has its issue tracker turned on
 * @param hasWiki   the repository has its wiki turned on
 */
public record RepoInfo(
        String owner,
        String name,
        boolean isPrivate,
        boolean fork,
        boolean archived,
        long sizeKb,
        Instant pushedAt,
        String defaultBranch,
        String cloneUrl,
        String host,
        boolean hasIssues,
        boolean hasWiki) {

    public static final String GITHUB = "github";
    public static final String GITLAB = "gitlab";
    public static final String BITBUCKET = "bitbucket";

    /** A GitHub repository without issues or wiki information. */
    public RepoInfo(String owner, String name, boolean isPrivate, boolean fork, boolean archived, long sizeKb,
                    Instant pushedAt, String defaultBranch, String cloneUrl) {
        this(owner, name, isPrivate, fork, archived, sizeKb, pushedAt, defaultBranch, cloneUrl, GITHUB, false, false);
    }

    public RepoInfo {
        host = host == null || host.isBlank() ? GITHUB : host;
    }

    public String fullName() {
        return owner + "/" + name;
    }

    public boolean onGitHub() {
        return GITHUB.equals(host);
    }

    /** The name used in backup-state.json and in the saved selection. */
    public String key() {
        return keyOf(host, fullName());
    }

    /** The folder that receives this repository's ZIP files. */
    public Path folderIn(Path destination) {
        return folderFor(destination, key());
    }

    /** The clone address of the wiki (GitHub keeps it in a separate repository). */
    public String wikiUrl() {
        String url = cloneUrl == null ? "" : cloneUrl;
        return (url.endsWith(".git") ? url.substring(0, url.length() - 4) : url) + ".wiki.git";
    }

    public static String keyOf(String host, String fullName) {
        return host == null || GITHUB.equals(host) ? fullName : host + ":" + fullName;
    }

    /** Host part of a state key. */
    public static String hostOf(String key) {
        int colon = key.indexOf(':');
        return colon > 0 ? key.substring(0, colon) : GITHUB;
    }

    /** owner/name part of a state key. */
    public static String fullNameOf(String key) {
        int colon = key.indexOf(':');
        return colon > 0 ? key.substring(colon + 1) : key;
    }

    public static Path folderFor(Path destination, String key) {
        String host = hostOf(key);
        Path base = GITHUB.equals(host) ? destination : destination.resolve(host);
        Path folder = base;
        for (String part : fullNameOf(key).split("/")) {
            if (!part.isBlank()) {
                folder = folder.resolve(part);
            }
        }
        return folder;
    }

    /** "GitHub", "GitLab" or "Bitbucket". */
    public static String hostLabel(String host) {
        return switch (host == null ? GITHUB : host) {
            case GITLAB -> "GitLab";
            case BITBUCKET -> "Bitbucket";
            default -> "GitHub";
        };
    }
}
