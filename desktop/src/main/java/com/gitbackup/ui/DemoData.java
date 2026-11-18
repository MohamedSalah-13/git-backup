package com.gitbackup.ui;

import com.gitbackup.backup.BackupService;
import com.gitbackup.backup.BackupService.RepoStatus;
import com.gitbackup.backup.BackupService.Status;
import com.gitbackup.github.TokenStore;
import com.gitbackup.model.RepoInfo;
import java.time.Instant;
import java.util.List;

/**
 * Made-up repositories for the documentation pictures. Used only when the application is
 * started with {@code -Dgitbackup.demo=true}; nothing here touches an account.
 */
final class DemoData {

    private DemoData() {
    }

    static boolean enabled() {
        return Boolean.getBoolean("gitbackup.demo");
    }

    static BackupService.Listing listing() {
        List<RepoStatus> repos = List.of(
                repo("acme-labs", "web-store", true, false, 18_400, "2026-11-17T18:20:00Z", Status.CURRENT, "2026-11-17T21:00:00Z"),
                repo("acme-labs", "mobile-app", true, false, 52_900, "2026-11-18T07:45:00Z", Status.CHANGED, "2026-11-17T21:00:00Z"),
                repo("acme-labs", "api-gateway", true, false, 9_600, "2026-11-12T10:05:00Z", Status.CURRENT, "2026-11-17T21:00:00Z"),
                repo("acme-labs", "design-system", false, false, 7_300, "2026-11-16T14:30:00Z", Status.CURRENT, "2026-11-17T21:00:00Z"),
                repo("acme-labs", "docs", false, false, 2_100, "2026-11-18T06:10:00Z", Status.CHANGED, "2026-11-16T21:00:00Z"),
                repo("acme-labs", "infra-scripts", true, false, 1_250, "2026-10-29T09:00:00Z", Status.MISSING, "2026-11-01T21:00:00Z"),
                repo("acme-labs", "data-pipeline", true, false, 31_800, "2026-11-15T20:40:00Z", Status.CURRENT, "2026-11-17T21:00:00Z"),
                repo("acme-labs", "blog", false, false, 4_700, "2026-11-18T05:30:00Z", Status.NEVER, null),
                repo("acme-labs", "playground", false, false, 640, "2026-09-02T12:00:00Z", Status.NEVER, null),
                repo("acme-labs", "legacy-portal", true, true, 88_000, "2026-06-20T08:15:00Z", Status.CURRENT, "2026-11-17T21:00:00Z"));
        return new BackupService.Listing(repos,
                List.of(new BackupService.AccountInfo(RepoInfo.GITHUB, "acme-labs", TokenStore.Source.SAVED_TOKEN)),
                List.of(), true);
    }

    private static RepoStatus repo(String owner, String name, boolean isPrivate, boolean archived, long sizeKb,
                                   String pushed, Status status, String lastBackup) {
        RepoInfo info = new RepoInfo(owner, name, isPrivate, false, archived, sizeKb, Instant.parse(pushed),
                "main", "https://github.com/" + owner + "/" + name + ".git");
        return new RepoStatus(info, status != Status.NEVER, status, lastBackup);
    }
}
