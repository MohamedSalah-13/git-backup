package com.gitbackup.github;

import java.io.IOException;

/** A GitHub request that was answered, but not with success. */
public class GitHubException extends IOException {
    public enum Kind { NOT_SIGNED_IN, UNAUTHORIZED, FORBIDDEN, OTHER }

    private final Kind kind;

    public GitHubException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
