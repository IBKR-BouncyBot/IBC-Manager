package io.github.ibcmanager.ui;

import io.github.ibcmanager.install.IbcInstallResult;
import io.github.ibcmanager.install.IbcInstallerService;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

final class IbcInstallerDialog {
    private IbcInstallerDialog() { }

    static Optional<IbcInstallResult> installDefault(Component parent, IbcInstallerService service) {
        if (!service.isAvailable()) {
            JOptionPane.showMessageDialog(parent,
                    "Automatic IBC installation is available on Windows.",
                    "IBC installation unavailable", JOptionPane.INFORMATION_MESSAGE);
            return Optional.empty();
        }

        Path destination = IbcInstallerService.DEFAULT_WINDOWS_DIRECTORY;
        String message = "Resolve GitHub's latest published official IBC release and install it in:\n\n"
                + destination + "\n\n"
                + "If this folder already contains that exact compatible release, it will be reused. "
                + "A different non-empty installation will not be overwritten.\n\n"
                + "IB Gateway/TWS is not included. Continue?";
        int confirmation = JOptionPane.showConfirmDialog(parent, message,
                "Install latest official IBC",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (confirmation != JOptionPane.YES_OPTION) return Optional.empty();

        ProgressUi progressUi = createProgressDialog(parent);
        AtomicReference<SwingWorker<IbcInstallResult, ProgressUpdate>> workerReference = new AtomicReference<>();
        SwingWorker<IbcInstallResult, ProgressUpdate> worker = new SwingWorker<>() {
            @Override
            protected IbcInstallResult doInBackground() throws Exception {
                return service.installDefault((text, completed, total) -> {
                    if (isCancelled()) throw new CancellationException("IBC installation was cancelled");
                    publish(new ProgressUpdate(text, completed, total));
                });
            }

            @Override
            protected void process(List<ProgressUpdate> updates) {
                if (updates.isEmpty()) return;
                ProgressUpdate update = updates.get(updates.size() - 1);
                progressUi.status().setText("<html>" + escapeHtml(update.message()) + "</html>");
                if (update.message().startsWith("Installing validated")) {
                    progressUi.cancel().setEnabled(false);
                }
                if (update.totalBytes() > 0 && update.completedBytes() >= 0) {
                    progressUi.progressBar().setIndeterminate(false);
                    progressUi.progressBar().setMaximum(1000);
                    long scaled = Math.min(1000, update.completedBytes() * 1000 / update.totalBytes());
                    progressUi.progressBar().setValue((int) scaled);
                } else {
                    progressUi.progressBar().setIndeterminate(true);
                }
            }

            @Override
            protected void done() {
                progressUi.dialog().dispose();
            }
        };
        workerReference.set(worker);

        Runnable cancel = () -> {
            SwingWorker<IbcInstallResult, ProgressUpdate> active = workerReference.get();
            if (active == null || active.isDone()) return;
            progressUi.cancel().setEnabled(false);
            progressUi.status().setText("Cancelling IBC installation...");
            active.cancel(true);
        };
        progressUi.cancel().addActionListener(event -> cancel.run());
        progressUi.dialog().addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                cancel.run();
            }
        });

        worker.execute();
        progressUi.dialog().setVisible(true);
        try {
            IbcInstallResult result = worker.get();
            JOptionPane.showMessageDialog(parent, successMessage(result),
                    "IBC installation ready", JOptionPane.INFORMATION_MESSAGE);
            return Optional.of(result);
        } catch (CancellationException ex) {
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            UiUtil.showError(parent, "IBC installation interrupted", ex);
            return Optional.empty();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof CancellationException) return Optional.empty();
            UiUtil.showError(parent, "IBC installation failed", cause);
            return Optional.empty();
        }
    }

    static String successMessage(IbcInstallResult result) {
        String action = result.downloaded() ? "Downloaded and installed" : "Selected existing installation";
        String checksumDescription = result.downloaded()
                ? "Downloaded ZIP SHA-256: "
                : "Existing IBC.jar SHA-256: ";
        return action + " IBC " + result.version() + " in:\n"
                + result.installationDirectory() + "\n\n"
                + "Asset: " + result.assetName() + "\n"
                + checksumDescription + result.sha256() + "\n\n"
                + "The installation structure, version file, launcher, and IBC.jar contents were validated.";
    }

    private static ProgressUi createProgressDialog(Component parent) {
        Window owner = SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner, "Installing IBC", JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JLabel status = new JLabel("Preparing IBC installation...");
        JProgressBar progress = new JProgressBar();
        progress.setIndeterminate(true);
        JButton cancel = new JButton("Cancel");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.add(cancel);
        JPanel body = new JPanel(new BorderLayout(8, 10));
        body.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        body.add(status, BorderLayout.NORTH);
        body.add(progress, BorderLayout.CENTER);
        body.add(buttons, BorderLayout.SOUTH);
        dialog.add(body, BorderLayout.CENTER);
        dialog.setMinimumSize(new Dimension(540, 170));
        dialog.setSize(640, 180);
        dialog.setLocationRelativeTo(parent);
        return new ProgressUi(dialog, status, progress, cancel);
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private record ProgressUi(JDialog dialog, JLabel status, JProgressBar progressBar, JButton cancel) { }

    private record ProgressUpdate(String message, long completedBytes, long totalBytes) { }
}
