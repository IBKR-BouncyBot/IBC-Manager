package io.github.ibcmanager.ui;

import io.github.ibcmanager.config.ConfigValueValidator;
import io.github.ibcmanager.config.IbcConfigDocument;
import io.github.ibcmanager.config.ManagedConfigService;
import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.Severity;
import io.github.ibcmanager.model.ValidationIssue;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

@SuppressWarnings("serial")
public final class ManagedConfigDialog extends JDialog {
    private final JTextArea editor = new JTextArea();
    private IbcConfigDocument result;

    private ManagedConfigDialog(Frame owner, Profile profile, ManagedConfigService service) throws IOException {
        super(owner, "Managed config.ini - " + profile.name(), true);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        buildUi();
        IbcConfigDocument document = service.loadManagedConfig(profile);
        editor.setText(document.render());
        editor.setCaretPosition(0);
        setMinimumSize(new Dimension(720, 520));
        setSize(980, 760);
        setLocationRelativeTo(owner);
    }

    public static Optional<IbcConfigDocument> show(Frame owner, Profile profile, ManagedConfigService service) {
        if (profile.credentialMode() == CredentialMode.EXISTING_CONFIG) {
            JOptionPane.showMessageDialog(owner,
                    "This profile uses an external config.ini. IBC Manager will not modify that file.",
                    "External configuration", JOptionPane.INFORMATION_MESSAGE);
            return Optional.empty();
        }
        try {
            ManagedConfigDialog dialog = new ManagedConfigDialog(owner, profile, service);
            dialog.setVisible(true);
            return Optional.ofNullable(dialog.result);
        } catch (IOException ex) {
            UiUtil.showError(owner, "Could not open managed configuration", ex);
            return Optional.empty();
        }
    }

    private void buildUi() {
        JLabel notice = new JLabel("<html>Comments, ordering, unknown keys, and line endings are preserved when "
                + "their formatting has the same meaning as IBC's full-file Java Properties parser. Ambiguous "
                + "syntax must be corrected or explicitly canonicalized. Persistent passwords are rejected. "
                + "Saved Profile-tab values and advanced settings are synchronized back to the Profile editor.</html>");
        notice.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 8, 4, 8));
        add(notice, BorderLayout.NORTH);
        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        editor.setTabSize(4);
        add(new JScrollPane(editor), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(event -> dispose());
        JButton save = new JButton("Validate and save");
        save.addActionListener(event -> accept());
        buttons.add(cancel);
        buttons.add(save);
        add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(save);
    }

    private void accept() {
        try {
            IbcConfigDocument document = IbcConfigDocument.parse(editor.getText());
            if (!document.formattingMatchesIbcSemantics()) {
                int choice = JOptionPane.showConfirmDialog(this,
                        "IBC's full-file Java Properties parser and the formatting-preservation scanner "
                                + "interpret this text differently. Saving it unchanged could alter IBC behavior.\n\n"
                                + "Canonicalize the authoritative IBC settings now? All comments, ordering, "
                                + "and custom formatting will be removed; only the authoritative settings remain.",
                        "Ambiguous Java Properties syntax", JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.YES_OPTION) return;
                document = document.canonicalizedCopy();
                editor.setText(document.render());
            }
            if (!confirmIssues(new ConfigValueValidator().validateManagedConfig(document))) return;
            result = document;
            dispose();
        } catch (RuntimeException ex) {
            UiUtil.showError(this, "Configuration was not accepted", ex);
        }
    }

    /** Returns true when the document may be saved. Errors block; warnings ask. */
    private boolean confirmIssues(List<ValidationIssue> issues) {
        List<ValidationIssue> errors = issues.stream()
                .filter(issue -> issue.severity() == Severity.ERROR).toList();
        if (!errors.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "This configuration was not saved because it contains "
                            + errors.size() + " error(s):\n\n" + describe(errors),
                    "Invalid configuration", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        List<ValidationIssue> warnings = issues.stream()
                .filter(issue -> issue.severity() == Severity.WARNING).toList();
        if (warnings.isEmpty()) return true;
        int choice = JOptionPane.showConfirmDialog(this,
                "This configuration has " + warnings.size() + " warning(s):\n\n"
                        + describe(warnings) + "\nSave it anyway?",
                "Configuration warnings", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return choice == JOptionPane.YES_OPTION;
    }

    private static String describe(List<ValidationIssue> issues) {
        StringBuilder text = new StringBuilder();
        int shown = Math.min(issues.size(), 15);
        for (int index = 0; index < shown; index++) {
            ValidationIssue issue = issues.get(index);
            text.append("  [").append(issue.field()).append("] ").append(issue.message()).append('\n');
        }
        if (issues.size() > shown) text.append("  ... and ").append(issues.size() - shown).append(" more\n");
        return text.toString();
    }
}
