package com.gitbackup;

import com.gitbackup.ui.MainApp;
import java.util.Arrays;
import javafx.application.Application;

/**
 * Entry point. A separate class from the JavaFX {@link Application} so the program also
 * starts from a plain "java -jar".
 *
 * <pre>
 *   (no arguments)       opens the window
 *   --backup [--force]   backs up the saved selection without a window (for scheduling)
 *   --check              checks the latest backup file of every repository, without a window
 * </pre>
 */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--backup") || Arrays.asList(args).contains("--check")) {
            System.exit(HeadlessRunner.run(args));
        }
        Application.launch(MainApp.class, args);
    }
}
