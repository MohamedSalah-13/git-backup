package com.gitbackup.ui;

import java.awt.AWTException;
import java.awt.ComponentOrientation;
import java.awt.EventQueue;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.InputStream;
import javafx.application.Platform;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.UIManager;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

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
    private JPopupMenu menu;
    private JDialog anchor;

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
                menu = buildMenu(labels);
                if (icon != null) {
                    icon.setToolTip(labels.tooltip());
                    return;
                }
                TrayIcon created = new TrayIcon(loadImage(), labels.tooltip());
                created.setImageAutoSize(true);
                created.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent e) {
                        popup(e);
                    }

                    @Override
                    public void mouseReleased(MouseEvent e) {
                        popup(e);
                    }
                });
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

    /** A Swing menu instead of the AWT one: crisp Segoe UI text, and right-to-left for Arabic. */
    private JPopupMenu buildMenu(Labels labels) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // keeps the default look
        }
        Font font = new Font("Segoe UI", Font.PLAIN, 14);
        JPopupMenu popup = new JPopupMenu();
        popup.setFont(font);
        popup.add(item(labels.open(), onOpen, font));
        popup.add(item(labels.backupNow(), onBackup, font));
        popup.addSeparator();
        popup.add(item(labels.exit(), onExit, font));
        boolean rtl = labels.open().codePoints().anyMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.ARABIC);
        popup.applyComponentOrientation(rtl ? ComponentOrientation.RIGHT_TO_LEFT : ComponentOrientation.LEFT_TO_RIGHT);
        popup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                if (anchor != null) {
                    anchor.setVisible(false);
                }
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
                if (anchor != null) {
                    anchor.setVisible(false);
                }
            }
        });
        return popup;
    }

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger() || menu == null) {
            return;
        }
        // The menu needs an invisible always-on-top window to own it, so that it closes when
        // the user clicks anywhere else.
        if (anchor == null) {
            anchor = new JDialog();
            anchor.setUndecorated(true);
            anchor.setAlwaysOnTop(true);
            anchor.setSize(1, 1);
            anchor.setType(java.awt.Window.Type.UTILITY);
        }
        Point at = e.getPoint();
        Dimension size = menu.getPreferredSize();
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        Rectangle screen = gc.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        int x = Math.min(at.x, screen.x + screen.width - insets.right - size.width);
        int y = at.y - size.height;
        if (y < screen.y + insets.top) {
            y = at.y;
        }
        anchor.setLocation(x, y);
        anchor.setVisible(true);
        anchor.toFront();
        menu.show(anchor.getContentPane(), 0, 0);
        menu.setLocation(x, y);
    }

    private static JMenuItem item(String label, Runnable action, Font font) {
        JMenuItem item = new JMenuItem(label);
        item.setFont(font);
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
