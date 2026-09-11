package io.github.ibcmanager.ui;

import io.github.ibcmanager.app.Version;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridLayout;

public final class AboutDialog {
    private AboutDialog() { }

    public static void show(Frame owner) {
        JDialog dialog = new JDialog(owner, "About IBC Manager", true);
        JPanel body = new JPanel(new GridLayout(0, 1, 0, 7));
        body.setBorder(new EmptyBorder(18, 24, 18, 24));
        JLabel title = new JLabel("IBC Manager");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        body.add(title);
        body.add(new JLabel("Version " + Version.VERSION));
        body.add(new JLabel("Installs the latest official IBC release from GitHub"));
        body.add(new JLabel("IBC compatibility floor " + Version.IBC_MINIMUM_SUPPORTED_VERSION
                + "; Java 17+"));
        body.add(new JLabel("Windows-first profile, configuration, process, and diagnostics manager."));
        body.add(new JLabel("IBC remains the unmodified automation engine and is licensed under GPLv3."));
        body.add(new JLabel("This is an unofficial project and is not affiliated with Interactive Brokers."));
        dialog.add(body, BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        JButton close = new JButton("Close");
        close.addActionListener(event -> dialog.dispose());
        buttons.add(close);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }
}
