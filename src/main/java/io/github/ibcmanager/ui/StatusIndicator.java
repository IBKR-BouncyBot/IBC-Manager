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
        presentation = presentationFor(status == null ? null : status.state());
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
        setToolTipText(accessible);
        dot.getAccessibleContext().setAccessibleName(presentation.headline() + " status indicator");
        revalidate();
        repaint();
    }

    Presentation presentation() {
        return presentation;
    }

    static Presentation presentationFor(RuntimeState state) {
        if (state == null) return new Presentation(Tone.YELLOW, YELLOW, "No profile selected");
        return switch (state) {
            case API_SOCKET_OPEN -> new Presentation(Tone.GREEN, GREEN, "API TCP open");
            case RUNNING -> new Presentation(Tone.YELLOW, YELLOW, "Logged in; API closed");
            case ERROR -> new Presentation(Tone.RED, RED, "Error");
            case STOPPED -> new Presentation(Tone.RED, RED, "Stopped");
            case VALIDATING -> new Presentation(Tone.YELLOW, YELLOW, "Validating");
            case STARTING -> new Presentation(Tone.YELLOW, YELLOW, "Starting");
            case WAITING_FOR_LOGIN -> new Presentation(Tone.YELLOW, YELLOW, "Waiting for login");
            case WAITING_FOR_SECOND_FACTOR -> new Presentation(Tone.YELLOW, YELLOW, "Waiting for second factor");
            case PAUSED -> new Presentation(Tone.YELLOW, YELLOW, "Paused");
            case STOPPING -> new Presentation(Tone.YELLOW, YELLOW, "Stopping");
            case UNKNOWN -> new Presentation(Tone.YELLOW, YELLOW, "Checking status");
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
