package io.github.ibcmanager.ui;

import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.TradingMode;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.util.Objects;

/**
 * Shared presentation for session toolbar actions and confirmation policy for
 * the actions that require explicit user approval.
 */
enum ProfileSessionAction {
    START(
            "Start",
            "startProfileButton",
            "Confirm start",
            "Start",
            new Color(0, 112, 60),
            "Launch IBC and the configured TWS/IB Gateway instance",
            "This launches IBC and the configured application. Complete any required authentication before relying on the session."),
    STOP(
            "Stop",
            "stopProfileButton",
            "Confirm stop",
            "Stop",
            new Color(160, 35, 35),
            "Stop the running IBC-managed session gracefully",
            "IBC Manager will request a graceful shutdown of the running IBC-managed session. API and market-data connectivity will stop. If the timeout expires, no process is killed automatically; Force Stop remains a separate recovery action."),
    RESTART(
            "Restart",
            "restartProfileButton",
            "Confirm restart",
            "Restart",
            new Color(166, 83, 0),
            "Restart the running IBC-managed session",
            "IBC Manager will perform a controlled graceful Stop followed by a fresh Start. This avoids IBC's native RESTART fallback changing the persistent auto-restart schedule. API and market-data connectivity will be interrupted."),
    PAUSE(
            "Pause",
            "pauseProfileButton",
            "Confirm pause",
            "Pause",
            new Color(0, 82, 150),
            "Pause the running session through IBC",
            "IBC will close TWS/IB Gateway cleanly while preserving resumable session state. Trading connectivity stops until the profile is started again.");

    static final int MINIMUM_BUTTON_WIDTH = 112;
    static final int MINIMUM_BUTTON_HEIGHT = 38;

    private final String buttonText;
    private final String componentName;
    private final String dialogTitle;
    private final String confirmationLabel;
    private final Color accent;
    private final String tooltip;
    private final String explanation;

    ProfileSessionAction(String buttonText, String componentName, String dialogTitle,
            String confirmationLabel, Color accent, String tooltip, String explanation) {
        this.buttonText = buttonText;
        this.componentName = componentName;
        this.dialogTitle = dialogTitle;
        this.confirmationLabel = confirmationLabel;
        this.accent = accent;
        this.tooltip = tooltip;
        this.explanation = explanation;
    }

    void configureButton(JButton button) {
        Objects.requireNonNull(button, "button");
        button.setText(buttonText);
        button.setName(componentName);
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(buttonText);
        button.getAccessibleContext().setAccessibleDescription(tooltip);
        Font font = button.getFont();
        if (font != null) button.setFont(font.deriveFont(Font.BOLD, Math.max(14f, font.getSize2D() + 1f)));
        button.setMargin(new Insets(7, 16, 7, 16));
        button.setForeground(accent);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(accent, 2, true),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)));
        button.setBorderPainted(true);
        Dimension calculated = button.getPreferredSize();
        Dimension actionSize = new Dimension(
                Math.max(MINIMUM_BUTTON_WIDTH, calculated.width),
                Math.max(MINIMUM_BUTTON_HEIGHT, calculated.height));
        button.setPreferredSize(actionSize);
        button.setMinimumSize(actionSize);
        button.setMaximumSize(actionSize);
    }

    Prompt prompt(Profile profile) {
        Objects.requireNonNull(profile, "profile");
        StringBuilder message = new StringBuilder();
        message.append(buttonText).append(" profile '").append(profile.name()).append("'?")
                .append("\n\nApplication: ").append(profile.targetType())
                .append("\nTrading mode: ").append(profile.tradingMode())
                .append("\nAPI port: ").append(profile.apiPort())
                .append("\n\n").append(explanation);
        if (this == START && profile.tradingMode() == TradingMode.LIVE) {
            message.append("\n\nThis is a LIVE trading profile. Verify the account and profile before continuing.");
        }
        int messageType = this == START && profile.tradingMode() == TradingMode.PAPER
                ? JOptionPane.QUESTION_MESSAGE
                : JOptionPane.WARNING_MESSAGE;
        return new Prompt(dialogTitle, message.toString(), confirmationLabel, messageType);
    }

    boolean confirm(Component parent, Profile profile) {
        Prompt prompt = prompt(profile);
        Object[] options = {prompt.confirmationLabel(), "Cancel"};
        int choice = JOptionPane.showOptionDialog(parent, prompt.message(), prompt.title(),
                JOptionPane.YES_NO_OPTION, prompt.messageType(), null, options, options[1]);
        return choice == 0;
    }

    Color accent() {
        return accent;
    }

    String componentName() {
        return componentName;
    }

    record Prompt(String title, String message, String confirmationLabel, int messageType) {
        Prompt {
            title = Objects.requireNonNull(title, "title");
            message = Objects.requireNonNull(message, "message");
            confirmationLabel = Objects.requireNonNull(confirmationLabel, "confirmationLabel");
        }
    }
}
