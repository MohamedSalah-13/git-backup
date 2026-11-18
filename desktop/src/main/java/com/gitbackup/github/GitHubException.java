package com.gitbackup.github;

import java.io.IOException;

/** A request to GitHub, GitLab or Bitbucket that was answered, but not with success. */
public class GitHubException extends IOException {
    public enum Kind { NOT_SIGNED_IN, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, OTHER }

    private final Kind kind;
    private final int status;

    public GitHubException(Kind kind, String message) {
        this(kind, message, 0);
    }

    public GitHubException(Kind kind, String message, int status) {
        super(message);
        this.kind = kind;
        this.status = status;
    }

    public Kind kind() {
        return kind;
    }

    /** The HTTP status, or 0 when there was none. */
    public int status() {
        return status;
    }
}
