package ibcalpha.ibc;

import java.awt.Dialog;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

/** Real engine, timer, event handler and login handler; synthetic Swing controls, no IBKR requests. */
public final class SecondFactorRetryGuiSmoke {
    private static final ByteArrayOutputStream TRACE = new ByteArrayOutputStream();
    private static int checks;
    private static int scenarios;
    private static Path config;

    private SecondFactorRetryGuiSmoke() { }

    public static void main(String[] args) throws Exception {
        PrintStream console = System.out;
        System.setOut(new PrintStream(new Tee(console), true, StandardCharsets.UTF_8));
        config = Files.createTempFile("engine-2fa-gui-", ".ini");
        try {
            SessionManager.initialise(true);
            settings(true, true, 1);
            System.setProperty("restart", "engine-test-only");
            SessionManager.startSession();
            new TwsListener(List.of()); // Initialize the real engine's window-log policy.
            TradingModeManager.initialise(new DefaultTradingModeManager("live"));
            if (args.length == 1 && args[0].equals("--real-five-minutes")) {
                openChallenge(300);
            } else if (args.length == 1 && args[0].equals("--returned-login-regressions")) {
                clearedCredentialForm();
                replacedLoginFrame();
                inlineWithoutLoginHeading();
                delayedCredentialValidation();
                hiddenOldChallengeHeading();
            } else {
                openChallenge(1);
                naturalClose();
                approval();
                queuedApproval();
                blockedCancel();
                manualCredentials();
                disabled();
                cancelFollowedByApproval();
                repeated();
                staleProvider();
                inlineChallenge();
                duplicateCancel();
                queuedLoginCancelled();
                failedLogin();
                deviceSelection();
                competingModal();
                missingLoginFrame();
                missingGatewaySelector();
                clearedCredentialForm();
                replacedLoginFrame();
                inlineWithoutLoginHeading();
                delayedCredentialValidation();
                hiddenOldChallengeHeading();
            }
            console.println("SECOND FACTOR GUI SMOKE PASSED: " + scenarios + " scenarios, " + checks + " checks");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                LoginManager.loginManager().cancelSecondFactorRetry();
                for (Window window : Window.getWindows()) window.dispose();
            });
            MyScheduledExecutorService.getInstance().shutdownNow();
            Files.deleteIfExists(config);
            System.setOut(console);
        }
    }

    private static void settings(boolean enabled, boolean password, int seconds) throws IOException {
        Files.writeString(config, "TradingMode=live\nIbLoginId=fixture-user\nIbPassword="
                + (password ? "fixture-password" : "") + "\nReloginAfterSecondFactorAuthenticationTimeout="
                + (enabled ? "yes" : "no") + "\nSecondFactorAuthenticationTimeout=" + seconds + "\n",
                StandardCharsets.ISO_8859_1);
        Settings.initialise(new DefaultSettings(config.toString()));
    }

    private static void openChallenge(int seconds) throws Exception {
        settings(true, true, seconds);
        try (Fixture f = new Fixture()) {
            if (seconds == 300) onEdt(() -> {
                f.requireFilledFields = true;
                f.asyncValidation = true;
                f.loginControls();
            });
            int armed = eventCount("SECOND_FACTOR_RETRY_ARMED");
            onEdt(() -> f.showChallenge("Cancel", true));
            waitFor(() -> eventCount("SECOND_FACTOR_RETRY_ARMED") > armed, 5_000, "challenge armed");
            // Duplicate events from the same visible window must not shift the deadline.
            onEdt(() -> f.reportOpen(f.challenge));
            require(eventCount("SECOND_FACTOR_RETRY_ARMED") == armed + 1, "duplicate open not rearmed");
            waitFor(() -> f.logins.get() == 1, seconds * 1000L + 15_000, "actual native login click");
            require(f.cancels.get() == 1, "one Cancel on still-open modal challenge");
            require(f.closedBeforeCancel.get() == 0, "the old missing WINDOW_CLOSED trigger is not required");
            long elapsed = f.cancelAt.get() - f.openedAt.get();
            require(elapsed >= TimeUnit.SECONDS.toNanos(seconds), "deadline does not fire early");
            require(f.owner.getLoginState() == LoginManager.LoginState.LOGGING_IN, "native login path used");
            require(f.uiOffEdt.get() == 0, "all mutations stay on the event thread");
            if (seconds == 300) {
                require(f.passwordEdits.get() == 1, "real-duration retry restores empty credentials only once");
                System.out.println("REAL-DURATION FORM: cleared password, disabled Log In, asynchronous validation");
            }
            System.out.println("MEASURED 2FA Cancel elapsed milliseconds: " + elapsed / 1_000_000.0
                    + "; configured seconds=" + seconds);
        }
        scenarios++;
    }

    private static void naturalClose() throws Exception {
        settings(true, true, 1);
        try (Fixture f = new Fixture()) {
            int armed = eventCount("SECOND_FACTOR_RETRY_ARMED");
            onEdt(() -> f.showChallenge("Cancel", false));
            waitFor(() -> eventCount("SECOND_FACTOR_RETRY_ARMED") > armed, 5_000, "armed");
            onEdt(() -> f.challenge.dispose());
            Thread.sleep(200);
            require(f.logins.get() == 0, "early close does not immediately retry");
            waitFor(() -> f.logins.get() == 1, 5_000, "closed prompt retried at original deadline");
            require(f.cancels.get() == 0, "already closed window not clicked");
        }
        scenarios++;
    }

    private static void approval() throws Exception {
        settings(true, true, 1);
        try (Fixture f = new Fixture()) {
            onEdt(() -> f.showChallenge("Cancel", false));
            waitFor(() -> f.openedAt.get() != 0, 5_000, "opened");
            onEdt(() -> { f.owner.setLoginState(LoginManager.LoginState.LOGGED_IN); f.challenge.dispose(); });
            Thread.sleep(1300);
            require(f.cancels.get() == 0 && f.logins.get() == 0, "successful authentication cancels timer");
            onEdt(() -> f.reportOpen(f.challenge));
            require(f.owner.getLoginState() == LoginManager.LoginState.LOGGED_IN, "late open cannot regress login");
        }
        scenarios++;
    }

    private static void queuedApproval() throws Exception {
        settings(true, true, 1);
        try (Fixture f = new Fixture()) {
            onEdt(() -> f.showChallenge("Cancel", false));
            waitFor(() -> f.openedAt.get() != 0, 5_000, "opened");
            // Hold the event queue beyond the timer deadline; approval must invalidate
            // the expired callback before the event thread becomes available again.
            onEdt(() -> {
                try { Thread.sleep(1300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                f.owner.setLoginState(LoginManager.LoginState.LOGGED_IN);
                f.challenge.dispose();
            });
            onEdt(() -> { });
            require(f.cancels.get() == 0 && f.logins.get() == 0, "queued deadline cannot undo approval");
        }
        scenarios++;
    }

    private static void blockedCancel() throws Exception {
        settings(true, true, 1);
        try (Fixture f = new Fixture()) {
            int blocked = eventCount("SECOND_FACTOR_RETRY_BLOCKED");
            onEdt(() -> f.showChallenge("Unexpected label", false));
            waitFor(() -> eventCount("SECOND_FACTOR_RETRY_BLOCKED") > blocked, 5_000, "unsupported control reported");
            require(f.cancels.get() == 0 && f.logins.get() == 0, "no guessed click or process restart");
            onEdt(() -> require(f.challenge.isShowing(), "unrecognized prompt left untouched"));
        }
        scenarios++;
    }

    private static void manualCredentials() throws Exception {
        settings(true, false, 1);
        try (Fixture f = new Fixture()) {
            int blocked = eventCount("SECOND_FACTOR_RETRY_BLOCKED");
            onEdt(() -> f.showChallenge("Cancel", false));
            waitFor(() -> eventCount("SECOND_FACTOR_RETRY_BLOCKED") > blocked, 5_000, "manual password reported");
            require(f.cancels.get() == 0 && f.logins.get() == 0, "do not discard request with missing password");
        }
        scenarios++;
    }

    private static void disabled() throws Exception {
        settings(false, true, 1);
        try (Fixture f = new Fixture()) {
            int armed = eventCount("SECOND_FACTOR_RETRY_ARMED");
            onEdt(() -> f.showChallenge("Cancel", false));
            waitFor(() -> f.openedAt.get() != 0, 5_000, "opened");
            Thread.sleep(1300);
            require(eventCount("SECOND_FACTOR_RETRY_ARMED") == armed, "disabled policy creates no deadline");
            require(f.cancels.get() == 0 && f.logins.get() == 0, "disabled policy leaves session alone");
        }
        scenarios++;
    }

    private static void cancelFollowedByApproval() throws Exception {
        settings(true, true, 1);
        try (Fixture f = new Fixture()) {
            f.approveDuringCancel = true;
            onEdt(() -> f.showChallenge("Cancel", false));
            waitFor(() -> f.cancels.get() == 1, 5_000, "Cancel invoked");
            Thread.sleep(300);
            require(f.logins.get() == 0, "approval during cancellation prevents retry submission");
        }
        scenarios++;
    }

    private static void repeated() throws Exception {
        settings(true, true, 1);
        try (Fixture f = new Fixture()) {
            f.newPromptAfterLogin = true;
            onEdt(() -> f.showChallenge("Cancel", false));
            waitFor(() -> f.logins.get() == 2, 8_000, "two distinct challenges retried");
            onEdt(() -> { f.owner.setLoginState(LoginManager.LoginState.LOGGED_IN); f.challenge.dispose(); });
            Thread.sleep(1300);
            require(f.logins.get() == 2 && f.cancels.get() == 2, "success stops subsequent repeated deadlines");
        }
        scenarios++;
    }

    private static void staleProvider() throws Exception {
        settings(true, true, 1);
        try (Fixture f = new Fixture()) {
            onEdt(() -> f.showChallenge("Cancel", false));
            waitFor(() -> f.openedAt.get() != 0, 5_000, "opened");
            onEdt(() -> LoginManager.initialise(new DefaultLoginManager()));
            Thread.sleep(1300);
            require(f.cancels.get() == 0 && f.logins.get() == 0, "retired provider cannot operate old window");
        }
        scenarios++;
    }

    private static void inlineChallenge() throws Exception {
        settings(true, true, 1);
        try (Fixture f = new Fixture()) {
            f.inline = true;
            onEdt(() -> { f.loginControls(); f.handler.initiateLogin(f.frame); });
            waitFor(() -> f.logins.get() == 2, 8_000, "in-frame 2FA is independently retried");
            require(f.cancels.get() == 1, "actual Cancel on transformed frame");
            require(f.uiOffEdt.get() == 0, "inline detector and actions use EDT");
            onEdt(() -> f.owner.setLoginState(LoginManager.LoginState.LOGGED_IN));
        }
        scenarios++;
    }

    private static void duplicateCancel() throws Exception {
        settings(true, true, 1);
        try (Fixture f = new Fixture()) {
            int blocked = eventCount("SECOND_FACTOR_RETRY_BLOCKED");
            onEdt(() -> {
                f.showChallenge("Cancel", false);
                ((JPanel) f.challenge.getContentPane().getComponent(0)).add(new JButton("Cancel"));
                f.challenge.pack();
            });
            waitFor(() -> eventCount("SECOND_FACTOR_RETRY_BLOCKED") > blocked, 5_000, "ambiguous Cancel reported");
            require(f.cancels.get() == 0 && f.logins.get() == 0, "ambiguous controls are not clicked");
        }
        scenarios++;
    }

    private static void queuedLoginCancelled() throws Exception {
        settings(true, true, 1);
        try (Fixture f = new Fixture()) {
            onEdt(() -> {
                f.handler.initiateLogin(f.frame);
                f.owner.setLoginState(LoginManager.LoginState.LOGGED_IN);
            });
            onEdt(() -> { });
            require(f.logins.get() == 0, "queued login click cannot run after approval");
        }
        scenarios++;
    }

    private static void failedLogin() throws Exception {
        settings(true, true, 1);
        try (Fixture f = new Fixture()) {
            onEdt(() -> f.showChallenge("Cancel", false));
            waitFor(() -> f.openedAt.get() != 0, 5_000, "opened");
            onEdt(() -> f.owner.setLoginState(LoginManager.LoginState.LOGIN_FAILED));
            Thread.sleep(1300);
            require(f.cancels.get() == 0 && f.logins.get() == 0, "failed login cancels scheduled action");
        }
        scenarios++;
    }

    private static void deviceSelection() throws Exception {
        settings(true, true, 1);
        try (Fixture f = new Fixture()) {
            int armed = eventCount("SECOND_FACTOR_RETRY_ARMED");
            onEdt(() -> {
                f.showChallenge("Cancel", false);
                JPanel panel = (JPanel) f.challenge.getContentPane().getComponent(0);
                panel.add(new javax.swing.JTextArea("Select second factor device"));
                panel.add(new javax.swing.JList<>(new String[] {"IBKR Mobile"}));
                f.challenge.pack();
            });
            waitFor(() -> f.openedAt.get() != 0, 5_000, "device selector opened");
            Thread.sleep(1300);
            require(eventCount("SECOND_FACTOR_RETRY_ARMED") == armed, "device selection is not an active challenge");
            require(f.cancels.get() == 0 && f.logins.get() == 0, "selection remains available for manual action");
        }
        scenarios++;
    }

    private static void competingModal() throws Exception {
        settings(true, true, 1);
        try (Fixture f = new Fixture()) {
            int blocked = eventCount("SECOND_FACTOR_RETRY_BLOCKED");
            final JDialog[] overlay = new JDialog[1];
            onEdt(() -> {
                f.showChallenge("Cancel", false);
                overlay[0] = new JDialog(f.frame, "Other application message", Dialog.ModalityType.APPLICATION_MODAL);
                overlay[0].add(new JLabel("Resolve this first")); overlay[0].pack();
                SwingUtilities.invokeLater(() -> overlay[0].setVisible(true));
            });
            try {
                waitFor(() -> eventCount("SECOND_FACTOR_RETRY_BLOCKED") > blocked, 5_000, "modal overlay blocks automation");
                require(f.cancels.get() == 0 && f.logins.get() == 0, "do not click behind another modal dialog");
            } finally { onEdt(() -> overlay[0].dispose()); }
        }
        scenarios++;
    }

    private static void missingLoginFrame() throws Exception {
        settings(true, true, 1);
        try (Fixture f = new Fixture()) {
            int blocked = eventCount("SECOND_FACTOR_RETRY_BLOCKED");
            onEdt(() -> { f.owner.setLoginFrame(null); f.showChallenge("Cancel", false); });
            waitFor(() -> eventCount("SECOND_FACTOR_RETRY_BLOCKED") > blocked, 5_000, "missing login context reported");
            require(f.cancels.get() == 0 && f.logins.get() == 0, "do not cancel a request that cannot be resubmitted");
        }
        scenarios++;
    }

    private static void missingGatewaySelector() throws Exception {
        settings(true, true, 1);
        try (Fixture f = new Fixture()) {
            int blocked = eventCount("SECOND_FACTOR_RETRY_BLOCKED");
            onEdt(() -> {
                JPanel panel = (JPanel) f.frame.getContentPane();
                panel.remove(0); // IB API selector is deliberately absent in this unknown layout.
                f.frame.pack();
                f.showChallenge("Cancel", false);
            });
            waitFor(() -> eventCount("SECOND_FACTOR_RETRY_BLOCKED") > blocked, 16_000, "incomplete login layout blocked");
            require(f.cancels.get() == 1 && f.logins.get() == 0,
                    "do not invoke an upstream fatal missing-selector path");
        }
        scenarios++;
    }


    private static void clearedCredentialForm() throws Exception {
        settings(true, true, 1);
        try (Fixture f = new Fixture()) {
            onEdt(() -> {
                f.requireFilledFields = true;
                f.loginControls();
                f.showChallenge("Cancel", false);
            });
            waitFor(() -> f.openedAt.get() != 0, 5_000, "opened");
            onEdt(() -> f.challenge.dispose());
            Thread.sleep(200);
            require(f.logins.get() == 0, "returned login does not submit before original deadline");
            waitFor(() -> f.logins.get() == 1, 14_000,
                    "cleared password and disabled login button are repopulated then submitted");
            require(f.cancels.get() == 0, "expired prompt not cancelled again");
            onEdt(() -> f.owner.setLoginState(LoginManager.LoginState.LOGGED_IN));
        }
        scenarios++;
    }

    private static void replacedLoginFrame() throws Exception {
        settings(true, true, 1);
        try (Fixture f = new Fixture()) {
            int armed = eventCount("SECOND_FACTOR_RETRY_ARMED");
            onEdt(() -> f.showChallenge("Cancel", false));
            waitFor(() -> f.openedAt.get() != 0, 5_000, "opened");
            onEdt(() -> {
                f.frame.dispose();
                f.frame = new JFrame("IB Gateway");
                f.requireFilledFields = true;
                f.loginControls();
                f.frame.setVisible(true);
                // Exercise the actual event filter: observing a returned form must
                // not submit credentials early or retire the challenge deadline.
                if (f.handler.filterEvent(f.frame, WindowEvent.WINDOW_OPENED)) {
                    f.handler.handleWindow(f.frame, WindowEvent.WINDOW_OPENED);
                }
            });
            require(eventCount("SECOND_FACTOR_RETRY_ARMED") == armed + 1,
                    "reconstructed login frame retains the original deadline");
            waitFor(() -> f.logins.get() == 1, 14_000, "new recognized login frame is retried");
            require(f.cancels.get() == 0, "disposed old frame not clicked");
            onEdt(() -> f.owner.setLoginState(LoginManager.LoginState.LOGGED_IN));
        }
        scenarios++;
    }

    private static void inlineWithoutLoginHeading() throws Exception {
        settings(true, true, 1);
        try (Fixture f = new Fixture()) {
            f.inline = true;
            f.omitLoginHeading = true;
            onEdt(() -> { f.loginControls(); f.handler.initiateLogin(f.frame); });
            waitFor(() -> f.logins.get() == 2, 8_000,
                    "inline challenge detected even without a preceding LOGIN label");
            require(f.cancels.get() == 1, "one Cancel for observed challenge");
            onEdt(() -> f.owner.setLoginState(LoginManager.LoginState.LOGGED_IN));
        }
        scenarios++;
    }

    private static void delayedCredentialValidation() throws Exception {
        settings(true, true, 1);
        try (Fixture f = new Fixture()) {
            onEdt(() -> {
                f.requireFilledFields = true;
                f.asyncValidation = true;
                f.loginControls();
                f.showChallenge("Cancel", false);
            });
            waitFor(() -> f.openedAt.get() != 0, 5_000, "opened");
            onEdt(() -> f.challenge.dispose());
            waitFor(() -> f.logins.get() == 1, 14_000, "deferred field validation enables one retry click");
            require(f.passwordEdits.get() == 1, "prepared credentials are not rewritten before the login click");
            onEdt(() -> f.owner.setLoginState(LoginManager.LoginState.LOGGED_IN));
        }
        scenarios++;
    }

    private static void hiddenOldChallengeHeading() throws Exception {
        settings(true, true, 1);
        try (Fixture f = new Fixture()) {
            f.inline = true;
            onEdt(() -> { f.loginControls(); f.handler.initiateLogin(f.frame); });
            waitFor(() -> f.owner.getLoginState() == LoginManager.LoginState.TWO_FA_IN_PROGRESS,
                    5_000, "inline challenge observed");
            onEdt(() -> {
                f.requireFilledFields = true;
                f.loginControls();
                // A retained card can keep an old challenge heading in the tree.
                JLabel retired = new JLabel("SECOND FACTOR AUTHENTICATION");
                retired.setVisible(false);
                f.frame.getContentPane().add(retired);
                f.frame.pack();
            });
            waitFor(() -> f.logins.get() == 2, 14_000, "hidden retired challenge does not block restored login");
            require(f.cancels.get() == 0, "do not cancel an already returned inline form");
            onEdt(() -> f.owner.setLoginState(LoginManager.LoginState.LOGGED_IN));
        }
        scenarios++;
    }

    private static int eventCount(String event) {
        String suffix = "|" + event;
        return (int) TRACE.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> line.startsWith(EngineEvents.PREFIX) && line.endsWith(suffix)).count();
    }

    private static void waitFor(BooleanSupplier ready, long millis, String message) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!ready.getAsBoolean() && System.nanoTime() - end < 0) Thread.sleep(10);
        require(ready.getAsBoolean(), message);
    }
    private static void onEdt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    private static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static final class Fixture implements AutoCloseable {
        final DefaultLoginManager owner = new DefaultLoginManager();
        final GatewayLoginFrameHandler handler = new GatewayLoginFrameHandler();
        final AtomicInteger cancels = new AtomicInteger(), logins = new AtomicInteger(), passwordEdits = new AtomicInteger();
        final AtomicInteger closedBeforeCancel = new AtomicInteger(), uiOffEdt = new AtomicInteger();
        final AtomicLong openedAt = new AtomicLong(), cancelAt = new AtomicLong();
        JFrame frame;
        JDialog challenge;
        boolean approveDuringCancel, newPromptAfterLogin, inline, requireFilledFields, omitLoginHeading, asyncValidation;
        Fixture() throws Exception {
            onEdt(() -> {
                LoginManager.initialise(owner);
                owner.setLoginHandler(handler);
                frame = new JFrame("IB Gateway");
                owner.setLoginFrame(frame);
                loginControls();
                frame.setVisible(true);
            });
        }
        void loginControls() {
            JPanel panel = new JPanel();
            if (inline && !omitLoginHeading) panel.add(new JLabel("LOGIN"));
            panel.add(new JToggleButton("IB API", true));
            JTextField user = new JTextField(12);
            JPasswordField password = new JPasswordField(12);
            panel.add(user);
            panel.add(password);
            JButton login = new JButton("Log In");
            if (requireFilledFields) {
                login.setEnabled(false);
                javax.swing.event.DocumentListener listener = new javax.swing.event.DocumentListener() {
                    private void update() {
                        login.setEnabled(false);
                        if (asyncValidation) {
                            javax.swing.Timer validation = new javax.swing.Timer(250, e ->
                                    login.setEnabled(!user.getText().isEmpty() && password.getDocument().getLength() > 0));
                            validation.setRepeats(false); validation.start();
                        } else login.setEnabled(!user.getText().isEmpty() && password.getDocument().getLength() > 0);
                    }
                    @Override public void insertUpdate(javax.swing.event.DocumentEvent e) {
                        if (e.getDocument() == password.getDocument()) passwordEdits.incrementAndGet();
                        update();
                    }
                    @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { update(); }
                    @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { update(); }
                };
                user.getDocument().addDocumentListener(listener);
                password.getDocument().addDocumentListener(listener);
            }
            login.addActionListener(event -> {
                if (!SwingUtilities.isEventDispatchThread()) uiOffEdt.incrementAndGet();
                logins.incrementAndGet();
                if (inline) {
                    JPanel auth = new JPanel(); auth.add(new JLabel("SECOND FACTOR AUTHENTICATION"));
                    JButton cancel = new JButton("Cancel");
                    cancel.addActionListener(e -> { cancels.incrementAndGet(); loginControls(); });
                    auth.add(cancel); frame.setContentPane(auth); frame.pack();
                } else if (newPromptAfterLogin) {
                    showChallenge("Cancel", false);
                }
            });
            panel.add(login); frame.setContentPane(panel); frame.pack();
        }
        void showChallenge(String label, boolean modal) {
            challenge = new JDialog(frame, "Second Factor Authentication", Dialog.ModalityType.MODELESS);
            if (modal) challenge.setModalityType(Dialog.ModalityType.APPLICATION_MODAL);
            JDialog exact = challenge;
            JPanel panel = new JPanel(); panel.add(new JLabel("Approve the fixture request"));
            JButton cancel = new JButton(label);
            cancel.addActionListener(event -> {
                if (!SwingUtilities.isEventDispatchThread()) uiOffEdt.incrementAndGet();
                cancels.incrementAndGet(); cancelAt.compareAndSet(0, System.nanoTime());
                if (approveDuringCancel) owner.setLoginState(LoginManager.LoginState.LOGGED_IN);
                exact.dispose();
            });
            panel.add(cancel); exact.add(panel); exact.pack();
            exact.addWindowListener(new WindowAdapter() {
                @Override public void windowOpened(WindowEvent event) { reportOpen(exact); }
                @Override public void windowClosed(WindowEvent event) {
                    if (cancels.get() == 0) closedBeforeCancel.incrementAndGet();
                    SecondFactorAuthenticationDialogHandler.getInstance().handleWindow(exact, WindowEvent.WINDOW_CLOSED);
                }
            });
            // A modal dialog runs its own event loop; do not block the test caller.
            SwingUtilities.invokeLater(() -> exact.setVisible(true));
        }
        void reportOpen(Window window) {
            openedAt.compareAndSet(0, System.nanoTime());
            SecondFactorAuthenticationDialogHandler.getInstance().handleWindow(window, WindowEvent.WINDOW_OPENED);
        }
        @Override public void close() {
            try {
                onEdt(() -> {
                    owner.setLoginState(LoginManager.LoginState.LOGGED_OUT);
                    owner.cancelSecondFactorRetry();
                    if (challenge != null) challenge.dispose();
                    frame.dispose();
                });
            } catch (Exception failure) { throw new AssertionError(failure); }
        }
    }
    private static final class Tee extends OutputStream {
        private final PrintStream console;
        Tee(PrintStream console) { this.console = console; }
        @Override public synchronized void write(int value) { console.write(value); TRACE.write(value); }
        @Override public synchronized void write(byte[] bytes, int start, int length) {
            console.write(bytes, start, length); TRACE.write(bytes, start, length);
        }
    }
}
