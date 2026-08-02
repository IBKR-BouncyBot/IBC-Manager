package io.github.ibcmanager.ui;

import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import java.awt.Font;

public final class UiTheme {
    private UiTheme() { }

    public static void install() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException
                 | UnsupportedLookAndFeelException ignored) {
            // Swing's cross-platform look and feel remains available.
        }
        Font defaultFont = UIManager.getFont("Label.font");
        if (defaultFont != null && defaultFont.getSize() < 13) {
            Font larger = defaultFont.deriveFont(13f);
            for (Object key : UIManager.getDefaults().keySet()) {
                Object value = UIManager.get(key);
                if (value instanceof Font) UIManager.put(key, larger);
            }
        }
    }
}
