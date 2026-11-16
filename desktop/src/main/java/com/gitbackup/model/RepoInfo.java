package com.gitbackup.model;

import java.time.Instant;

/** A repository as reported by the hosting service. */
public record RepoInfo(
        String owner,
        String name,
        boolean isPrivate,
        boolean fork,
        boolean archived,
        long sizeKb,
        Instant pushedAt,
        String defaultBranch,
        String cloneUrl) {

    public String fullName() {
        return owner + "/" + name;
    }
}
