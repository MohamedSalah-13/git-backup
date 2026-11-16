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
 * </pre>
 */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--backup")) {
            System.exit(HeadlessRunner.run(args));
        }
        Application.launch(MainApp.class, args);
    }
}
