package com.gitbackup;

import com.gitbackup.backup.BackupService;
import com.gitbackup.store.AppPaths;
import java.util.Arrays;

/** Runs the backup of the saved selection without a window and prints the log. */
final class HeadlessRunner {
    private HeadlessRunner() {
    }

    /** Returns the process exit code: 0 = all good, 1 = some failed, 2 = could not start. */
    static int run(String[] args) {
        boolean force = Arrays.asList(args).contains("--force");
        BackupService service = new BackupService(AppPaths.defaults());
        return service.run(force, (level, line) -> System.out.println(line), () -> false).exitCode();
    }
}
