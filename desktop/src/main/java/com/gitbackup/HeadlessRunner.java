package com.gitbackup;

import com.gitbackup.backup.BackupService;
import com.gitbackup.model.AppConfig;
import com.gitbackup.store.AppPaths;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/** Runs the backup of the saved selection, or the check of the backup files, without a window. */
final class HeadlessRunner {
    private HeadlessRunner() {
    }

    /** Returns the process exit code: 0 = all good, 1 = some failed, 2 = could not start. */
    static int run(String[] args) {
        List<String> options = Arrays.asList(args);
        BackupService service = new BackupService(AppPaths.defaults());
        if (options.contains("--check")) {
            BackupService.CheckSummary summary =
                    service.check("check", (level, line) -> System.out.println(line), () -> false);
            if (summary.started()) {
                try {
                    AppConfig config = service.loadConfig();
                    config.lastVerify = LocalDate.now().toString();
                    service.saveConfig(config);
                } catch (IOException e) {
                    System.out.println("Could not record the date of the check: " + e.getMessage());
                }
            }
            return summary.exitCode();
        }
        boolean force = options.contains("--force");
        return service.run(force, "command", (level, line) -> System.out.println(line), () -> false).exitCode();
    }
}
