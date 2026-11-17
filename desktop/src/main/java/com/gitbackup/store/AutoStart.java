package com.gitbackup.store;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

/**
 * Starts the installed application with Windows, hidden in the tray, through the current
 * user's "Run" registry key. Only possible for the installed program: a development run
 * has no launcher to point at.
 */
public final class AutoStart {
    private static final String RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String VALUE_NAME = "GitBackupDesktop";

    private AutoStart() {
    }

    /** The installed launcher (set by the jpackage launcher), or null in a development run. */
    private static String launcher() {
        return System.getProperty("jpackage.app-path");
    }

    public static boolean available() {
        return Platform.isWindows() && launcher() != null;
    }

    public static boolean isEnabled() {
        try {
            return Platform.isWindows()
                    && Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String command() {
        return "\"" + launcher() + "\" --tray";
    }

    /**
     * Points an enabled entry at the running launcher. The entry keeps the path it was
     * written with, so after the program is installed elsewhere, or a copy it was enabled
     * from is deleted, Windows would silently start nothing at sign-in.
     *
     * @return true when the entry was rewritten
     */
    public static boolean repair() {
        if (!available() || !isEnabled()) {
            return false;
        }
        try {
            String current = Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME);
            if (command().equalsIgnoreCase(current)) {
                return false;
            }
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME, command());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** @throws RuntimeException when the registry cannot be changed */
    public static void setEnabled(boolean enabled) {
        if (enabled) {
            if (!available()) {
                throw new IllegalStateException("Start with Windows needs the installed application.");
            }
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME, command());
        } else if (isEnabled()) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME);
        }
    }
}
