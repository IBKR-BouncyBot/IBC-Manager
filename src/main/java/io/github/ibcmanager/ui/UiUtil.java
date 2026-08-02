package io.github.ibcmanager.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public final class UiUtil {
    private UiUtil() { }

    public static GridBagConstraints constraints(int x, int y) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = x;
        constraints.gridy = y;
        constraints.insets = new Insets(5, 7, 5, 7);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        return constraints;
    }

    public static JPanel formPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        return panel;
    }

    public static void addRow(JPanel panel, int row, String label, JComponent field) {
        GridBagConstraints labelConstraints = constraints(0, row);
        labelConstraints.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(label), labelConstraints);
        GridBagConstraints fieldConstraints = constraints(1, row);
        fieldConstraints.weightx = 1;
        panel.add(field, fieldConstraints);
    }

    public static JButton browseDirectoryButton(Component parent, javax.swing.JTextField field) {
        JButton button = new JButton("Browse...");
        button.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (!field.getText().isBlank()) chooser.setCurrentDirectory(Path.of(field.getText()).toFile());
            if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                field.setText(chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString());
            }
        });
        return button;
    }

    public static JButton browseFileButton(Component parent, javax.swing.JTextField field) {
        JButton button = new JButton("Browse...");
        button.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            if (!field.getText().isBlank()) chooser.setCurrentDirectory(Path.of(field.getText()).toFile().getParentFile());
            if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                field.setText(chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString());
            }
        });
        return button;
    }

    public static JPanel pathField(Component parent, javax.swing.JTextField field, boolean directory) {
        JPanel panel = new JPanel(new java.awt.BorderLayout(6, 0));
        panel.add(field, java.awt.BorderLayout.CENTER);
        panel.add(directory ? browseDirectoryButton(parent, field) : browseFileButton(parent, field),
                java.awt.BorderLayout.EAST);
        return panel;
    }

    public static <T> void runAsync(Component parent, String failureTitle, Callable<T> operation,
            Consumer<T> onSuccess, Runnable onFinished) {
        new javax.swing.SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return operation.call();
            }

            @Override
            protected void done() {
                try {
                    onSuccess.accept(get());
                } catch (java.util.concurrent.ExecutionException ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    showError(parent, failureTitle, cause);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    showError(parent, failureTitle, ex);
                } finally {
                    onFinished.run();
                }
            }
        }.execute();
    }

    public static void showError(Component parent, String title, Throwable error) {
        String message = error == null ? "Unknown error" : String.valueOf(error.getMessage());
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        JOptionPane.showMessageDialog(parent, message, title, JOptionPane.ERROR_MESSAGE);
    }

    public static void openPath(Component parent, Path path) {
        try {
            if (!Desktop.isDesktopSupported()) throw new IOException("Desktop integration is unavailable");
            Desktop.getDesktop().open(path.toFile());
        } catch (IOException | UnsupportedOperationException ex) {
            showError(parent, "Could not open path", ex);
        }
    }

    public static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run();
        else SwingUtilities.invokeLater(action);
    }
}
