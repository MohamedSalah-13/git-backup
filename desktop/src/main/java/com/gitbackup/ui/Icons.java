package com.gitbackup.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;

/** Small helpers for Ikonli (Feather) icons. */
final class Icons {
    private static final int SIZE = 16;

    private Icons() {
    }

    static FontIcon icon(Ikon ikon) {
        return icon(ikon, SIZE);
    }

    static FontIcon icon(Ikon ikon, int size) {
        FontIcon icon = new FontIcon(ikon);
        icon.setIconSize(size);
        return icon;
    }

    static Button button(String text, Ikon ikon) {
        return with(new Button(text), ikon);
    }

    static <L extends Labeled> L with(L control, Ikon ikon) {
        control.setGraphic(icon(ikon));
        control.setGraphicTextGap(6);
        return control;
    }
}
