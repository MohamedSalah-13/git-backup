package com.gitbackup.ui;

import java.awt.AWTException;
import java.awt.EventQueue;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.IOException;
import java.io.InputStream;
import javafx.application.Platform;
import javax.imageio.ImageIO;

/**
 * The icon next to the clock. Everything AWT runs on the AWT thread; the actions it
 * triggers are handed to the JavaFX thread.
 */
final class TrayIconSupport {
    record Labels(String tooltip, String open, String backupNow, String exit) {
    }

    private final Runnable onOpen;
    private final Runnable onBackup;
    private final Runnable onExit;
    private volatile TrayIcon icon;

    TrayIconSupport(Runnable onOpen, Runnable onBackup, Runnable onExit) {
        this.onOpen = onOpen;
        this.onBackup = onBackup;
        this.onExit = onExit;
    }

    static boolean supported() {
        try {
            return SystemTray.isSupported();
        } catch (RuntimeException | Error e) {
            return false;
        }
    }

    /** Adds the icon, or replaces its texts when it is already there (language change). */
    void show(Labels labels) {
        EventQueue.invokeLater(() -> {
            try {
                PopupMenu menu = new PopupMenu();
                menu.add(item(labels.open(), onOpen));
                menu.add(item(labels.backupNow(), onBackup));
                menu.addSeparator();
                menu.add(item(labels.exit(), onExit));
                if (icon != null) {
                    icon.setPopupMenu(menu);
                    icon.setToolTip(labels.tooltip());
                    return;
                }
                TrayIcon created = new TrayIcon(loadImage(), labels.tooltip(), menu);
                created.setImageAutoSize(true);
                created.addActionListener(event -> Platform.runLater(onOpen)); // double-click
                SystemTray.getSystemTray().add(created);
                icon = created;
            } catch (AWTException | IOException | RuntimeException e) {
                icon = null; // no tray: the window then closes the application as usual
            }
        });
    }

    boolean active() {
        return icon != null;
    }

    void remove() {
        TrayIcon current = icon;
        icon = null;
        if (current != null) {
            EventQueue.invokeLater(() -> SystemTray.getSystemTray().remove(current));
        }
    }

    void notify(String title, String text, boolean problem) {
        TrayIcon current = icon;
        if (current != null) {
            EventQueue.invokeLater(() -> current.displayMessage(title, text,
                    problem ? TrayIcon.MessageType.WARNING : TrayIcon.MessageType.INFO));
        }
    }

    private static MenuItem item(String label, Runnable action) {
        MenuItem item = new MenuItem(label);
        item.addActionListener(event -> Platform.runLater(action));
        return item;
    }

    private static Image loadImage() throws IOException {
        try (InputStream in = TrayIconSupport.class.getResourceAsStream("/icon-32.png")) {
            if (in == null) {
                throw new IOException("icon-32.png is missing");
            }
            return ImageIO.read(in);
        }
    }
}
