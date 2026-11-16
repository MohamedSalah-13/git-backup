package com.gitbackup.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gitbackup.model.BackupState;
import com.gitbackup.model.RepoInfo;
import com.gitbackup.store.JsonStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupServiceTest {
    private static final RepoInfo REPO = new RepoInfo("Acme", "clinic-system", false, false, false, 1, null,
            "main", "https://example.invalid/Acme/clinic-system.git");

    @Test
    void patternsMatchNameOrFullNameIgnoringCase() {
        assertTrue(BackupService.matchesAny(REPO, List.of("clinic-system")));
        assertTrue(BackupService.matchesAny(REPO, List.of("acme/CLINIC-system")));
        assertTrue(BackupService.matchesAny(REPO, List.of("other", "clinic-*")));
        assertFalse(BackupService.matchesAny(REPO, List.of("clinic")));
        assertFalse(BackupService.matchesAny(REPO, List.of("", "  ")));
        // A dot is a literal dot, not "any character".
        assertFalse(BackupService.matchesAny(REPO, List.of("clinic.system")));
    }

    /** The PowerShell version writes this file; the application must read it as-is. */
    @Test
    void readsStateFileWrittenByThePowerShellVersion(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("backup-state.json");
        Files.writeString(file, """
                {
                    "version":  1,
                    "updated":  "2026-11-16T05:21:05",
                    "repos":  {
                                  "Acme/clinic-system":  {
                                                             "fingerprint":  "abc123",
                                                             "branch":  "main",
                                                             "head":  "36f1043",
                                                             "file":  "clinic-system_2026-11-16_052059_36f1043.zip",
                                                             "size":  138240,
                                                             "backedUp":  "2026-11-16T05:21:00"
                                                         }
                              }
                }
                """);

        BackupState state = JsonStore.readState(file);

        BackupState.Entry entry = state.repos.get("Acme/clinic-system");
        assertEquals("abc123", entry.fingerprint);
        assertEquals(138240, entry.size);
        assertEquals("clinic-system_2026-11-16_052059_36f1043.zip", entry.file);
    }
}
