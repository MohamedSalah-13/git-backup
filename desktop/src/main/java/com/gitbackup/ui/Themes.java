package com.gitbackup.ui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import atlantafx.base.theme.Theme;
import javafx.application.Application;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.scene.Scene;

/** Light, dark or "like Windows", using the AtlantaFX Primer themes (GitHub's own look). */
final class Themes {
    static final String SYSTEM = "system";
    static final String LIGHT = "light";
    static final String DARK = "dark";
    static final String[] CHOICES = {SYSTEM, LIGHT, DARK};

    private Themes() {
    }

    /** Applies the theme to every window of the application and returns whether it is dark. */
    static boolean apply(String choice) {
        boolean dark = isDark(choice);
        Theme theme = dark ? new PrimerDark() : new PrimerLight();
        Application.setUserAgentStylesheet(theme.getUserAgentStylesheet());
        return dark;
    }

    static boolean isDark(String choice) {
        return switch (choice == null ? SYSTEM : choice) {
            case DARK -> true;
            case LIGHT -> false;
            default -> Platform.getPreferences().getColorScheme() == ColorScheme.DARK;
        };
    }

    /** Makes the window frame (title bar) follow the theme as well. */
    static void decorate(Scene scene, boolean dark) {
        if (scene != null) {
            scene.getPreferences().setColorScheme(dark ? ColorScheme.DARK : ColorScheme.LIGHT);
        }
    }
}
