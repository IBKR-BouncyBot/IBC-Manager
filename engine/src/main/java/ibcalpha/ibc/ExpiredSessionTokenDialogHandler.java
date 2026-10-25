// IBC Manager integration; GPL-3.0-or-later, like the upstream engine.
package ibcalpha.ibc;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.JTextComponent;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

/** Recover only an explicit expired-token rejection of Gateway authentication. */
public final class ExpiredSessionTokenDialogHandler implements WindowHandler {
    static final int MAX_COMPONENTS = 512;
    static final int MAX_TEXT_LENGTH = 4096;
    private static final String EXPIRED =
            "the security tokens associated with your login credentials have expired";
    private static final String MANUAL_LOGIN =
            "please manually enter your username and password to access ibkr gateway";

    interface Context {
        boolean isEligible();
        Object loginOwner();
        long loginGeneration();
        void requestColdRestart(Runnable dismissDialog);
    }

    private final Context context;
    private final Consumer<Runnable> enqueue;
    // IbcTws registers one handler for the engine lifetime. This covers all
    // dialogs and repeated window events, not just one window's identity.
    private boolean recoveryClaimed;

    public ExpiredSessionTokenDialogHandler() {
        this(30_000L);
    }

    // Package-private deadline seam for isolated process/GUI regression tests.
    ExpiredSessionTokenDialogHandler(long gracefulTimeoutMillis) {
        this(new EngineContext(gracefulTimeoutMillis), SwingUtilities::invokeLater);
    }

    ExpiredSessionTokenDialogHandler(Context context, Consumer<Runnable> enqueue) {
        this.context = context;
        this.enqueue = enqueue;
    }

    @Override public boolean filterEvent(Window window, int eventId) {
        return eventId == WindowEvent.WINDOW_OPENED || eventId == WindowEvent.WINDOW_ACTIVATED;
    }

    @Override public boolean recogniseWindow(Window window) {
        return window instanceof JDialog dialog
                && ("Gateway".equals(dialog.getTitle()) || "IBKR Gateway".equals(dialog.getTitle()))
                && dialog.isDisplayable() && dialog.isShowing() && containsExpiry(dialog);
    }

    @Override public void handleWindow(Window window, int eventId) {
        requireEdt();
        if (filterEvent(window, eventId)) handleCandidate(() -> recogniseWindow(window), () -> {
            if (!SwingUtils.clickButton(window, "OK")) {
                Utils.logError("Expired-token dialog could not be dismissed; cold-restart deadline remains active");
            }
        });
    }

    void handleCandidate(BooleanSupplier stillRecognised) {
        handleCandidate(stillRecognised, () -> { });
    }

    void handleCandidate(BooleanSupplier stillRecognised, Runnable dismissDialog) {
        requireEdt();
        if (recoveryClaimed || !stillRecognised.getAsBoolean() || !context.isEligible()) return;
        Object owner = context.loginOwner();
        long generation = context.loginGeneration();
        recoveryClaimed = true;
        enqueue.accept(() -> {
            requireEdt();
            // A manual stop, login transition (even back to LOGGED_OUT), new
            // LoginManager, dismissed dialog or changed message cancels this action.
            if (context.loginOwner() != owner || context.loginGeneration() != generation
                    || !context.isEligible() || !stillRecognised.getAsBoolean()) return;
            context.requestColdRestart(dismissDialog);
        });
    }

    static boolean containsExpiry(Container root) {
        ArrayDeque<Component> queue = new ArrayDeque<>();
        queue.add(root);
        StringBuilder displayed = new StringBuilder();
        int visited = 0;
        boolean ok = false;
        while (!queue.isEmpty() && ++visited <= MAX_COMPONENTS) {
            Component component = queue.removeFirst();
            if (!component.isVisible()) continue;
            String text = null;
            if (component instanceof JLabel label) text = label.getText();
            // Never read credentials, even if an application marks a password
            // field noneditable. Only inspect ordinary display text controls.
            if (component instanceof JTextComponent field && !(field instanceof JPasswordField)
                    && !field.isEditable()) {
                Document document = field.getDocument();
                int length = document.getLength();
                if (length <= MAX_TEXT_LENGTH) {
                    try {
                        // Read the bounded display document, not serialized HTML
                        // (whose attributes can be much larger than its text).
                        text = document.getText(0, length);
                    } catch (BadLocationException changedDocument) {
                        return false;
                    }
                }
            }
            if (text != null && text.length() <= MAX_TEXT_LENGTH) {
                displayed.append(normalize(text)).append(' ');
            }
            if (component instanceof JButton button && button.isEnabled()
                    && "OK".equals(button.getText())) ok = true;
            if (component instanceof Container container) {
                if (visited + queue.size() + container.getComponentCount() > MAX_COMPONENTS) return false;
                for (Component child : container.getComponents()) queue.addLast(child);
            }
        }
        return queue.isEmpty() && ok && matches(displayed.toString());
    }

    static boolean isMessage(String text) {
        return text != null && text.length() <= MAX_TEXT_LENGTH && matches(normalize(text));
    }

    private static boolean matches(String text) {
        String plain = text.replaceAll("[\\s\\p{Z}]+", " ").toLowerCase(Locale.ROOT);
        return plain.contains(EXPIRED) && plain.contains(MANUAL_LOGIN);
    }

    private static String normalize(String text) {
        StringBuilder plain = new StringBuilder();
        try {
            // Swing's own HTML parser decodes named/numeric entities and omits
            // attributes and comments. No browser, URL or external file is used.
            new ParserDelegator().parse(new StringReader(text), new HTMLEditorKit.ParserCallback() {
                @Override public void handleText(char[] data, int pos) { plain.append(data); }
                @Override public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int pos) {
                    if (tag == HTML.Tag.BR || tag == HTML.Tag.HR) plain.append(' ');
                }
                @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int pos) {
                    if (tag.isBlock()) plain.append(' ');
                }
                @Override public void handleEndTag(HTML.Tag tag, int pos) {
                    if (tag.isBlock()) plain.append(' ');
                }
            }, true);
        } catch (IOException impossibleForStringReader) {
            return "";
        }
        return plain.toString().replaceAll("[\\s\\p{Z}]+", " ").trim();
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Token recovery outside EDT");
    }

    private static final class EngineContext implements Context {
        private final long gracefulTimeoutMillis;

        EngineContext(long gracefulTimeoutMillis) {
            if (gracefulTimeoutMillis <= 0) throw new IllegalArgumentException("Nonpositive graceful shutdown deadline");
            this.gracefulTimeoutMillis = gracefulTimeoutMillis;
        }

        @Override public boolean isEligible() {
            if (!SessionManager.isGateway() || StopTask.shutdownInProgress() || RestartTask.restartInProgress()) return false;
            LoginManager.LoginState state = LoginManager.loginManager().getLoginState();
            return state == LoginManager.LoginState.LOGGED_IN
                    || (state == LoginManager.LoginState.LOGGED_OUT && SessionManager.isRestart());
        }
        @Override public Object loginOwner() { return LoginManager.loginManager(); }
        @Override public long loginGeneration() { return LoginManager.loginManager().loginGeneration(); }
        @Override public void requestColdRestart(Runnable dismissDialog) {
            // Claim on the EDT after the final state/message check. Established
            // sessions then close on a worker, preserving the real login state.
            ExpiredSessionTokenRecovery.start(
                    LoginManager.loginManager().getLoginState() == LoginManager.LoginState.LOGGED_IN,
                    dismissDialog, gracefulTimeoutMillis);
        }
    }
}
