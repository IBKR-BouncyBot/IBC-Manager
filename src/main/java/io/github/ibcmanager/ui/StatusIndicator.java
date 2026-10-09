package io.github.ibcmanager.ui;

import io.github.ibcmanager.model.ProfileStatus;
import io.github.ibcmanager.model.RuntimeState;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Objects;

/**
 * Prominent, text-backed status presentation for the selected profile.
 * Color is supplementary: the state is always stated in text for accessibility.
 */
@SuppressWarnings("serial")
final class StatusIndicator extends JPanel {
    static final Color GREEN = new Color(20, 125, 63);
    static final Color YELLOW = new Color(181, 116, 0);
    static final Color RED = new Color(176, 38, 38);
    private static final int LARGE_DOT_SIZE = 20;
    static final String API_MONITORING_TOOLTIP = "IBC Manager monitors Gateway and its API listener without "
            + "opening an API connection. Your trading application verifies its own API connection, "
            + "account and permissions.";

    private final JLabel dot = new JLabel();
    private final JLabel headline = new JLabel("No profile selected");
    private final JLabel detail = new JLabel("Select a profile to view its state");
    private Presentation presentation = presentationFor(null);

    StatusIndicator() {
        super(new BorderLayout(10, 0));
        setName("profileStatusIndicator");
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(presentation.color(), 2, true),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));

        dot.setName("profileStatusIndicatorDot");
        dot.setHorizontalAlignment(SwingConstants.CENTER);
        dot.setPreferredSize(new Dimension(28, 28));
        add(dot, BorderLayout.WEST);

        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setOpaque(false);
        headline.setName("profileStatusIndicatorHeadline");
        headline.setAlignmentX(LEFT_ALIGNMENT);
        headline.setFont(headline.getFont().deriveFont(
                Font.BOLD, Math.max(15f, headline.getFont().getSize2D() + 2f)));
        detail.setName("profileStatusIndicatorDetail");
        detail.setAlignmentX(LEFT_ALIGNMENT);
        text.add(headline);
        text.add(detail);
        add(text, BorderLayout.CENTER);
        updateStatus(null);
    }

    void updateStatus(ProfileStatus status) {
        presentation = presentationForStatus(status);
        dot.setIcon(icon(presentation.color(), LARGE_DOT_SIZE));
        headline.setText(presentation.headline());
        headline.setForeground(presentation.color());
        String message = status == null ? "Select a profile to view its state" : status.message();
        detail.setText(message == null || message.isBlank() ? presentation.headline() : message);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(presentation.color(), 2, true),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        String accessible = presentation.headline() + ". " + detail.getText();
        getAccessibleContext().setAccessibleName("Profile status");
        getAccessibleContext().setAccessibleDescription(accessible);
        String tooltip = status != null && status.state() == RuntimeState.API_LISTENER_DETECTED
                ? accessible + " " + API_MONITORING_TOOLTIP : accessible;
        setToolTipText(tooltip);
        headline.setToolTipText(tooltip);
        detail.setToolTipText(tooltip);
        dot.getAccessibleContext().setAccessibleName(presentation.headline() + " status indicator");
        revalidate();
        repaint();
    }

    Presentation presentation() {
        return presentation;
    }

    static Presentation presentationForStatus(ProfileStatus status) {
        if (status != null && status.state() == RuntimeState.RUNNING
                && status.apiListenerState() == io.github.ibcmanager.model.PortListenerState.UNKNOWN) {
            return new Presentation(Tone.YELLOW, YELLOW, "Logged in \u2014 checking API listener");
        }
        return presentationFor(status == null ? null : status.state());
    }

    static Presentation presentationFor(RuntimeState state) {
        if (state == null) return new Presentation(Tone.YELLOW, YELLOW, "No profile selected");
        return switch (state) {
            case API_LISTENER_DETECTED -> new Presentation(Tone.GREEN, GREEN, "Gateway running \u2014 API listener available");
            case RUNNING -> new Presentation(Tone.YELLOW, YELLOW, "Logged in \u2014 API listener unavailable");
            case ERROR -> new Presentation(Tone.RED, RED, "Action required");
            case STARTUP_STALLED -> new Presentation(Tone.RED, RED, "Gateway startup stalled");
            case AUTO_RECOVERY_STOPPING -> new Presentation(Tone.YELLOW, YELLOW, "Recovering \u2014 stopping Gateway");
            case AUTO_RECOVERY_FORCE_CLEANUP -> new Presentation(Tone.YELLOW, YELLOW, "Recovering \u2014 clearing stalled processes");
            case AUTO_RECOVERY_COOLDOWN -> new Presentation(Tone.YELLOW, YELLOW, "Recovering \u2014 waiting to restart");
            case STARTING_FRESH -> new Presentation(Tone.YELLOW, YELLOW, "Starting a new Gateway session");
            case RECOVERY_FAILED -> new Presentation(Tone.RED, RED, "Automatic recovery failed");
            case STOPPED -> new Presentation(Tone.RED, RED, "Gateway stopped");
            case VALIDATING -> new Presentation(Tone.YELLOW, YELLOW, "Checking profile");
            case STARTING -> new Presentation(Tone.YELLOW, YELLOW, "Starting Gateway");
            case RESTARTING -> new Presentation(Tone.YELLOW, YELLOW, "Restarting Gateway");
            case WAITING_FOR_LOGIN -> new Presentation(Tone.YELLOW, YELLOW, "Waiting for Gateway login");
            case WAITING_FOR_SECOND_FACTOR -> new Presentation(Tone.YELLOW, YELLOW, "Waiting for 2FA approval");
            case PAUSED -> new Presentation(Tone.YELLOW, YELLOW, "Gateway paused");
            case PAUSING -> new Presentation(Tone.YELLOW, YELLOW, "Pausing Gateway");
            case STOPPING -> new Presentation(Tone.YELLOW, YELLOW, "Stopping Gateway");
            case UNKNOWN -> new Presentation(Tone.YELLOW, YELLOW, "Checking Gateway status");
        };
    }

    /** Compact wording for the profile list and Overview status row. */
    static String shortLabel(RuntimeState state) {
        if (state == null) return "No profile selected";
        return switch (state) {
            case API_LISTENER_DETECTED -> "Running";
            case RUNNING -> "Waiting for API listener";
            case ERROR -> "Action required";
            case STARTUP_STALLED -> "Startup stalled";
            case AUTO_RECOVERY_STOPPING -> "Recovery: stopping";
            case AUTO_RECOVERY_FORCE_CLEANUP -> "Recovery: cleanup";
            case AUTO_RECOVERY_COOLDOWN -> "Recovery: waiting to restart";
            case STARTING_FRESH -> "Starting new session";
            case RECOVERY_FAILED -> "Recovery failed";
            case STOPPED -> "Stopped";
            case VALIDATING -> "Checking profile";
            case STARTING -> "Starting";
            case RESTARTING -> "Restarting";
            case WAITING_FOR_LOGIN -> "Waiting for login";
            case WAITING_FOR_SECOND_FACTOR -> "Waiting for 2FA approval";
            case PAUSED -> "Paused";
            case PAUSING -> "Pausing";
            case STOPPING -> "Stopping";
            case UNKNOWN -> "Checking status";
        };
    }

    static Icon iconFor(RuntimeState state, int size) {
        return icon(presentationFor(state).color(), size);
    }

    private static Icon icon(Color color, int size) {
        return new DotIcon(color, size);
    }

    enum Tone {
        GREEN,
        YELLOW,
        RED
    }

    record Presentation(Tone tone, Color color, String headline) {
        Presentation {
            Objects.requireNonNull(tone, "tone");
            Objects.requireNonNull(color, "color");
            headline = Objects.requireNonNull(headline, "headline");
        }
    }

    private static final class DotIcon implements Icon {
        private final Color color;
        private final int size;

        private DotIcon(Color color, int size) {
            this.color = Objects.requireNonNull(color, "color");
            if (size < 6) throw new IllegalArgumentException("Status dot size must be at least 6 pixels");
            this.size = size;
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                copy.setColor(color);
                copy.fillOval(x + 1, y + 1, size - 2, size - 2);
                copy.setColor(color.darker());
                copy.setStroke(new BasicStroke(1.2f));
                copy.drawOval(x + 1, y + 1, size - 2, size - 2);
            } finally {
                copy.dispose();
            }
        }
    }
}
