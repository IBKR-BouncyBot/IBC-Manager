package ibcalpha.ibc;

import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import io.github.ibcmanager.tests.TestSupport;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Display-required top-level-window regressions; simulated controls, no broker connection. */
public final class ExpiredSessionTokenGuiSmoke {
    private static int checks;
    private static int scenarios;

    private ExpiredSessionTokenGuiSmoke() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--production-child")) {
            productionChild(Path.of(args[1]), args[2]);
            return;
        }
        Path config = Files.createTempFile("ibc-expiry-gui-", ".ini");
        try {
            Files.writeString(config, "LogStructureWhen=never\n", StandardCharsets.ISO_8859_1);
            Settings.initialise(new DefaultSettings(config.toString()));
            SessionManager.initialise(true);
            System.setProperty("restart", "expiry-fixture-only");
            SessionManager.startSession();
            SwingUtilities.invokeAndWait(() -> {
                actualGatewayDialog();
                topLevelRecognition();
                duplicateEventsAndWindows();
                queuedDialogDismissal();
                queuedStateProgress();
                queuedManagerReplacement();
                registrationAndLegacyRoutes();
            });
            productionRecovery("warm");
            productionRecovery("fresh");
            productionRecovery("disabled-menu");
            System.out.println("EXPIRED SESSION TOKEN GUI SMOKE PASSED: " + scenarios + " scenarios, " + checks + " checks");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) window.dispose();
            });
            MyScheduledExecutorService.getInstance().shutdownNow();
            Files.deleteIfExists(config);
        }
    }

    private static void actualGatewayDialog() {
        Fixture f = new Fixture();
        try {
            JDialog dialog = f.gatewayDialog();
            require(f.handler.recogniseWindow(dialog), "startup JTextPane + HTMLEditorKit inside JOptionPane Gateway dialog recognized");
            dialog.setTitle("IBKR Gateway");
            require(f.handler.recogniseWindow(dialog), "October 25 established-session title is recognized with the exact expiry body");
            require(f.handler.filterEvent(dialog, WindowEvent.WINDOW_OPENED), "opened accepted");
            require(f.handler.filterEvent(dialog, WindowEvent.WINDOW_ACTIVATED), "activated accepted");
            for (int event : new int[] {WindowEvent.WINDOW_CLOSED, WindowEvent.WINDOW_CLOSING,
                    WindowEvent.WINDOW_DEACTIVATED, WindowEvent.WINDOW_GAINED_FOCUS, WindowEvent.WINDOW_LOST_FOCUS}) {
                require(!f.handler.filterEvent(dialog, event), "non-trigger event excluded");
                f.handler.handleWindow(dialog, event);
            }
            require(f.queue.isEmpty(), "ignored events do not claim recovery");
            f.handler.handleWindow(dialog, WindowEvent.WINDOW_OPENED);
            require(f.queue.size() == 1 && f.context.restarts == 0, "recover only after deferred final recheck");
            f.flush();
            require(f.context.restarts == 1, "eligible actual-widget fixture triggers one action");
        } finally { f.close(); }
        scenarios++;
    }

    private static void topLevelRecognition() {
        Fixture f = new Fixture();
        try {
            JDialog dialog = f.gatewayDialog();
            for (String title : new String[] {"", "IB Gateway", "IBKR Gateway settings", "Gateway settings", "Login Error", "Second Factor Authentication"}) {
                dialog.setTitle(title);
                require(!f.handler.recogniseWindow(dialog), "expiry handler requires a known exact Gateway title: " + title);
            }
            dialog.setTitle("Gateway"); dialog.setVisible(false);
            require(!f.handler.recogniseWindow(dialog), "hidden dialog cannot authorize restart");
            dialog.dispose();
            require(!f.handler.recogniseWindow(dialog), "disposed dialog cannot authorize restart");
            JFrame frame = new JFrame("Gateway");
            try {
                JPanel panel = new JPanel(); panel.add(ExpiredSessionTokenTests.gatewayMessage()); panel.add(new JButton("OK"));
                frame.add(panel); frame.pack(); frame.setVisible(true);
                require(!f.handler.recogniseWindow(frame), "normal application frame excluded even with identical message");
            } finally { frame.dispose(); }
        } finally { f.close(); }
        scenarios++;
    }

    private static void duplicateEventsAndWindows() {
        Fixture f = new Fixture();
        try {
            JDialog first = f.gatewayDialog(), replacement = f.gatewayDialog();
            f.handler.handleWindow(first, WindowEvent.WINDOW_ACTIVATED);
            f.handler.handleWindow(first, WindowEvent.WINDOW_OPENED);
            f.handler.handleWindow(replacement, WindowEvent.WINDOW_OPENED);
            f.handler.handleWindow(replacement, WindowEvent.WINDOW_ACTIVATED);
            require(f.queue.size() == 1, "one registered-handler guard spans events and dialogs");
            f.flush();
            f.handler.handleWindow(first, WindowEvent.WINDOW_ACTIVATED); f.flush();
            require(f.context.restarts == 1, "duplicate events cannot request a second restart");
        } finally { f.close(); }
        scenarios++;
    }

    private static void queuedDialogDismissal() {
        Fixture f = new Fixture();
        try {
            JDialog dialog = f.gatewayDialog();
            f.handler.handleWindow(dialog, WindowEvent.WINDOW_OPENED);
            dialog.dispose(); f.flush();
            require(f.context.restarts == 0, "user dismissal before deferred callback cancels recovery");
        } finally { f.close(); }
        scenarios++;
    }

    private static void queuedStateProgress() {
        for (LoginManager.LoginState state : new LoginManager.LoginState[] {
                LoginManager.LoginState.TWO_FA_IN_PROGRESS, LoginManager.LoginState.LOGGED_IN,
                LoginManager.LoginState.LOGGING_IN, LoginManager.LoginState.LOGIN_FAILED}) {
            Fixture f = new Fixture();
            try {
                JDialog dialog = f.gatewayDialog(); f.handler.handleWindow(dialog, WindowEvent.WINDOW_OPENED);
                f.context.owner.setLoginState(state);
                // Returning to logged out must not erase the new login generation.
                f.context.owner.setLoginState(LoginManager.LoginState.LOGGED_OUT);
                f.flush();
                require(f.context.restarts == 0, "progress through " + state + " cancels stale token callback");
            } finally { f.close(); }
        }
        scenarios++;
    }

    private static void queuedManagerReplacement() {
        Fixture f = new Fixture();
        try {
            JDialog dialog = f.gatewayDialog(); f.handler.handleWindow(dialog, WindowEvent.WINDOW_OPENED);
            f.context.owner = new DefaultLoginManager(); f.flush();
            require(f.context.restarts == 0, "replacement owner cancels callback even with same initial state/generation");
        } finally { f.close(); }
        scenarios++;
    }

    private static void registrationAndLegacyRoutes() {
        List<WindowHandler> handlers = handlers();
        require(handlers.stream().filter(h -> h instanceof ExpiredSessionTokenDialogHandler).count() == 1,
                "one expiry handler registered per engine");
        int expiry = indexOf(handlers, ExpiredSessionTokenDialogHandler.class);
        require(indexOf(handlers, InvalidTwsInfoDialogHandler.class) < expiry, "invalid twsInfo keeps precedence");
        require(expiry < indexOf(handlers, GatewayDialogHandler.class), "specific expiry recognized before generic Gateway dialog");
        Fixture f = new Fixture();
        try {
            require(firstHandler(handlers, f.gatewayDialog()) instanceof ExpiredSessionTokenDialogHandler,
                    "real registry selects new handler for recorded rejection");
            require(firstHandler(handlers, f.simpleDialog("Gateway", "Connection to server failed")) instanceof GatewayDialogHandler,
                    "existing generic connection failure path retained");
            require(firstHandler(handlers, f.simpleDialog("Login Error", "An existing login failure")) instanceof LoginErrorDialogHandler,
                    "existing Login Error handler retained");
            require(firstHandler(handlers, f.simpleDialog("Gateway", "invalid twsInfo")) instanceof InvalidTwsInfoDialogHandler,
                    "invalid twsInfo remains a reported rejection, never token recovery");
            require(firstHandler(handlers, f.simpleDialog("Gateway", "Invalid username or password")) instanceof GatewayDialogHandler,
                    "unrelated Gateway message remains on its existing path");
        } finally { f.close(); }
        scenarios++;
    }

    /** Real engine shutdown in a disposable child; no broker or installed Gateway. */
    private static void productionRecovery(String mode) throws Exception {
        Path directory = Files.createTempDirectory("ibc-expiry-postlogin-gui-");
        Path output = directory.resolve("child-output.txt");
        Process child = null;
        try {
            child = new ProcessBuilder(TestSupport.javaExecutable().toString(), "-Djava.awt.headless=false",
                    "-classpath", System.getProperty("java.class.path"), ExpiredSessionTokenGuiSmoke.class.getName(),
                    "--production-child", directory.toString(), mode)
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            require(child.waitFor(15, TimeUnit.SECONDS), "post-login " + mode + " child completes graceful close");
            String log = Files.readString(output, StandardCharsets.UTF_8);
            require(child.exitValue() == 0, "post-login " + mode + " child succeeds: " + log);
            require(log.lines().filter(line -> line.startsWith(EngineEvents.PREFIX)
                    && line.endsWith("|SESSION_TOKEN_EXPIRED")).count() == 1,
                    "one established-session event for " + mode + " original launch");
            require(!log.contains("|LOGIN_RESTART_TOKEN_EXPIRED"), "post-login recovery does not impersonate bootstrap expiry");
            require(Files.exists(directory.resolve("TOKENEXPIREDexpiry-postlogin-fixture")),
                    "real shutdown creates the wrapper-consumed expiry marker");
            require(Files.exists(directory.resolve("modal-dismissed.txt")), "only verified expiry modal is dismissed");
            if (mode.equals("disabled-menu")) {
                require(!Files.exists(directory.resolve("file-close.txt")), "disabled menu never clicked");
                require(log.contains("graceful shutdown deadline reached; terminating current Gateway JVM"),
                        "disabled menu has bounded and observable same-JVM fallback");
            } else {
                require(Files.exists(directory.resolve("file-close.txt")), "actual File > Close action completed");
                require(log.contains("EXPIRY_POSTLOGIN_GRACEFUL_PASSED"), "thread and state checks completed before child exit");
            }
            require(Files.exists(directory.resolve("relogin-suppressed.txt")), "real relogin handler is suppressed after shutdown claim");
            require(!Files.exists(directory.resolve("relogin-clicked.txt")), "expired old session cannot resubmit a stale relogin");
            require(!log.contains("fixture-private-password"), "private input is never logged");
        } finally {
            if (child != null && child.isAlive()) { child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS); }
            TestSupport.deleteTree(directory);
        }
        scenarios++;
    }

    private static void productionChild(Path directory, String mode) throws Exception {
        if (!List.of("warm", "fresh", "disabled-menu").contains(mode)) throw new IllegalArgumentException("unknown production fixture mode");
        Path config = directory.resolve("fixture.ini");
        Files.writeString(config, "LogStructureWhen=never\nLoginDialogDisplayTimeout=60\n", StandardCharsets.ISO_8859_1);
        Settings.initialise(new DefaultSettings(config.toString()));
        System.setProperty("jtsConfigDir", directory.toString());
        System.setProperty("ibcsessionid", "expiry-postlogin-fixture");
        if (!mode.equals("fresh")) System.setProperty("restart", "fixture-only"); else System.clearProperty("restart");
        SessionManager.initialise(true);
        SessionManager.startSession();
        new CommandServer(); // Only constructs shutdown target; no socket is bound.
        MainWindowManager.setDefault();
        LoginManager.initialise(new DefaultLoginManager());
        AtomicReference<JDialog> expiry = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("IBKR Gateway");
            frame.setSize(500, 200);
            JMenuBar menu = new JMenuBar(); JMenu file = new JMenu("File"); JMenuItem close = new JMenuItem("Close");
            close.addActionListener(event -> {
                try {
                    if (!SwingUtilities.isEventDispatchThread()) throw new AssertionError("menu callback must run on EDT");
                    if (expiry.get().isShowing()) throw new AssertionError("expiry modal was not dismissed before File Close");
                    if (!StopTask.shutdownInProgress()) throw new AssertionError("shutdown ownership was not claimed");
                    if (LoginManager.loginManager().getLoginState() != LoginManager.LoginState.LOGGED_IN)
                        throw new AssertionError("established session must not be faked as logged out");
                    if (!Files.exists(directory.resolve("TOKENEXPIREDexpiry-postlogin-fixture")))
                        throw new AssertionError("cold marker missing at graceful close");
                    Files.writeString(directory.resolve("file-close.txt"), "EDT callback after worker close request\n");
                    System.out.println("EXPIRY_POSTLOGIN_GRACEFUL_PASSED"); System.out.flush();
                    Runtime.getRuntime().halt(0);
                } catch (Throwable failure) {
                    failure.printStackTrace(System.err); System.err.flush(); Runtime.getRuntime().halt(91);
                }
            });
            if (mode.equals("disabled-menu")) close.setEnabled(false);
            file.add(close); menu.add(file); frame.setJMenuBar(menu);
            frame.setVisible(true); MainWindowManager.mainWindowManager().setMainWindow(frame);
            LoginManager.loginManager().setLoginState(LoginManager.LoginState.LOGGED_IN);
            // Reconnect attempts do not emit LOGGED_OUT in the recorded October 25 incident.
            System.out.println("EXPIRY_FIXTURE_RECONNECT_WHILE_LOGGED_IN");
            JDialog dialog = new JDialog(frame, "IBKR Gateway", true); expiry.set(dialog);
            JPanel panel = new JPanel(); panel.add(new JLabel("IBKR GATEWAY"));
            panel.add(ExpiredSessionTokenTests.gatewayMessage());
            panel.add(new javax.swing.JPasswordField("fixture-private-password"));
            JButton ok = new JButton("OK");
            ok.addActionListener(event -> {
                try { Files.writeString(directory.resolve("modal-dismissed.txt"), "verified OK\n"); }
                catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                dialog.dispose();
                JDialog relogin = new JDialog(frame, "Re-login is required", true);
                JButton again = new JButton("Re-login");
                again.addActionListener(ignored -> {
                    try { Files.writeString(directory.resolve("relogin-clicked.txt"), "unexpected relogin"); }
                    catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                });
                relogin.add(again); relogin.pack();
                new ReloginDialogHandler().handleWindow(relogin, WindowEvent.WINDOW_OPENED);
                if (LoginManager.loginManager().getLoginState() != LoginManager.LoginState.LOGGED_IN)
                    throw new AssertionError("relogin handler changed a claimed established-session recovery");
                try { Files.writeString(directory.resolve("relogin-suppressed.txt"), "real handler suppressed\n"); }
                catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                // A real modal OK listener can synchronously enter another modal event loop.
                // Recovery worker and watchdog must already be running before this callback.
                relogin.setVisible(true);
            });
            panel.add(ok); dialog.add(panel); dialog.pack();
            ExpiredSessionTokenDialogHandler handler = new ExpiredSessionTokenDialogHandler(3000);
            Toolkit.getDefaultToolkit().addAWTEventListener(new TwsListener(List.of(handler)), AWTEvent.WINDOW_EVENT_MASK);
            SwingUtilities.invokeLater(() -> dialog.setVisible(true));
        });
        // Parent owns the deadline and terminates this isolated process if production recovery fails.
        new java.util.concurrent.CountDownLatch(1).await();
    }

    private static List<WindowHandler> handlers() {
        try {
            var factory = IbcTws.class.getDeclaredMethod("createWindowHandlers"); factory.setAccessible(true);
            @SuppressWarnings("unchecked") List<WindowHandler> result = (List<WindowHandler>) factory.invoke(null);
            return result;
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static int indexOf(List<WindowHandler> handlers, Class<?> type) {
        for (int i = 0; i < handlers.size(); i++) if (type.isInstance(handlers.get(i))) return i;
        throw new AssertionError("missing handler " + type.getSimpleName());
    }
    private static WindowHandler firstHandler(List<WindowHandler> handlers, Window window) {
        for (WindowHandler handler : handlers) if (handler.recogniseWindow(window)) return handler;
        return null;
    }
    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class Fixture implements AutoCloseable {
        final Context context = new Context();
        final List<Runnable> queue = new ArrayList<>();
        final List<JDialog> dialogs = new ArrayList<>();
        final ExpiredSessionTokenDialogHandler handler = new ExpiredSessionTokenDialogHandler(context, queue::add);

        JDialog gatewayDialog() {
            // Startup control construction is verified from ibgateway.aw.b(String).
            // The October 25 screenshot establishes its title/body, not its private widget class.
            JOptionPane pane = new JOptionPane(ExpiredSessionTokenTests.gatewayMessage(), JOptionPane.ERROR_MESSAGE);
            JDialog dialog = pane.createDialog(null, "Gateway");
            dialog.setModal(false); dialog.setVisible(true); dialogs.add(dialog); return dialog;
        }
        JDialog simpleDialog(String title, String text) {
            JDialog dialog = new JDialog(); dialog.setTitle(title);
            JPanel panel = new JPanel(); panel.add(new JLabel(text)); panel.add(new JButton("OK"));
            dialog.add(panel); dialog.pack(); dialog.setVisible(true); dialogs.add(dialog); return dialog;
        }
        void flush() { List<Runnable> tasks = List.copyOf(queue); queue.clear(); tasks.forEach(Runnable::run); }
        @Override public void close() { dialogs.forEach(Window::dispose); }
    }
    private static final class Context implements ExpiredSessionTokenDialogHandler.Context {
        DefaultLoginManager owner = new DefaultLoginManager();
        int restarts;
        @Override public boolean isEligible() { return true; }
        @Override public Object loginOwner() { return owner; }
        @Override public long loginGeneration() { return owner.loginGeneration(); }
        @Override public void requestColdRestart(Runnable dismissDialog) { restarts++; }
    }
}
