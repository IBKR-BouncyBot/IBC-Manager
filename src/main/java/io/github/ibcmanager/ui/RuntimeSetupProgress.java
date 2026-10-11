package io.github.ibcmanager.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cancellation is cooperative before the atomic configuration commit, not Thread.stop. */
@SuppressWarnings("serial")
final class RuntimeSetupProgress extends JDialog {
    private final AtomicBoolean cancellation = new AtomicBoolean();
    private final JLabel message = new JLabel("Preparing runtime setup...");
    private final JButton cancel = new JButton("Cancel download/setup");

    RuntimeSetupProgress(Frame owner) {
        super(owner, "Managed runtime setup", false);
        setName("runtimeSetupProgress");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(12, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JProgressBar progress = new JProgressBar();
        progress.setIndeterminate(true);
        content.add(message, BorderLayout.NORTH);
        content.add(progress, BorderLayout.CENTER);
        content.add(cancel, BorderLayout.SOUTH);
        cancel.setName("cancelRuntimeSetupButton");
        cancel.addActionListener(event -> requestCancel());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { requestCancel(); }
        });
        setContentPane(content);
        setSize(650, 185);
        setLocationRelativeTo(owner);
    }

    boolean cancelled() { return cancellation.get(); }

    void updateMessage(String text) {
        if (cancelled()) return;
        message.setText("<html>" + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</html>");
        if (text.startsWith("Committing")) cancel.setEnabled(false);
    }

    private void requestCancel() {
        if (!cancel.isEnabled()) return;
        cancellation.set(true);
        cancel.setEnabled(false);
        message.setText("Cancellation requested; waiting for the current bounded operation to stop safely...");
    }
}
