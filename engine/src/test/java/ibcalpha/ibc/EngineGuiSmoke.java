package ibcalpha.ibc;

import java.awt.Window;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Exercises maintained engine handlers with real Swing widgets, without Gateway binaries. */
public final class EngineGuiSmoke {
    private static int checks;

    private EngineGuiSmoke() { }

    public static void main(String[] args) throws Exception {
        Path config = Files.createTempFile("ibc-engine-gui-", ".ini");
        try {
            Files.writeString(config, "SuppressInfoMessages=yes\nCommandPrompt=\n", StandardCharsets.ISO_8859_1);
            Settings.initialise(new DefaultSettings(config.toString()));
            SessionManager.initialise(true);
            System.setProperty("restart", "engine-test-only");
            SessionManager.startSession();
            TestMainWindow main = new TestMainWindow();
            MainWindowManager.initialise(main);
            SwingUtilities.invokeAndWait(() -> testMainWindow(main));
            SwingUtilities.invokeAndWait(() -> testIncoming(config));
            SwingUtilities.invokeAndWait(() -> testBlindWarning(config));
            main.window = null;
            testDispatcher();
            System.out.println("ENGINE GUI SMOKE PASSED: 4 scenarios, " + checks + " checks");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) window.dispose();
            });
            Files.deleteIfExists(config);
        }
    }

    private static void testMainWindow(TestMainWindow main) {
        GatewayMainWindowFrameHandler handler = new GatewayMainWindowFrameHandler();
        JFrame frame = new JFrame("Gateway fixture");
        try {
            require(!handler.recogniseWindow(frame), "a generic frame is not Gateway");
            JMenuBar bar = new JMenuBar();
            JMenu help = new JMenu("Help");
            JMenuItem about = new JMenuItem("About IB Gateway");
            help.add(about);
            bar.add(help);
            frame.setJMenuBar(bar);
            require(handler.recogniseWindow(frame), "current Gateway menu must match");
            about.setText("About Gateway");
            require(handler.recogniseWindow(frame), "older Gateway menu must match");
            require(handler.filterEvent(frame, WindowEvent.WINDOW_OPENED), "opened accepted");
            require(!handler.filterEvent(frame, WindowEvent.WINDOW_CLOSED), "closed not accepted");
            handler.handleWindow(frame, WindowEvent.WINDOW_OPENED);
            require(main.window == frame, "actual handler passes the exact main window to SessionManager");
        } finally {
            frame.dispose();
        }
    }

    private static void testIncoming(Path config) {
        AcceptIncomingConnectionDialogHandler handler = new AcceptIncomingConnectionDialogHandler();
        JDialog dialog = new JDialog();
        JPanel panel = new JPanel();
        AtomicInteger yesCount = new AtomicInteger();
        AtomicInteger noCount = new AtomicInteger();
        JButton yes = new JButton("Yes");
        JButton no = new JButton("No");
        yes.addActionListener(event -> yesCount.incrementAndGet());
        no.addActionListener(event -> noCount.incrementAndGet());
        panel.add(new JLabel("Accept incoming connection"));
        panel.add(yes);
        panel.add(no);
        dialog.add(panel);
        try {
            require(handler.recogniseWindow(dialog), "incoming-connection dialog recognized");
            require(handler.filterEvent(dialog, WindowEvent.WINDOW_OPENED), "incoming opened");
            require(!handler.filterEvent(dialog, WindowEvent.WINDOW_CLOSED), "incoming closed excluded");
            settings(config, "AcceptIncomingConnectionAction=manual\n");
            handler.handleWindow(dialog, WindowEvent.WINDOW_OPENED);
            require(yesCount.get() == 0 && noCount.get() == 0, "manual must not click");
            settings(config, "AcceptIncomingConnectionAction=accept\n");
            handler.handleWindow(dialog, WindowEvent.WINDOW_OPENED);
            require(yesCount.get() == 1 && noCount.get() == 0, "accept clicks Yes only");
            yes.setText("OK");
            handler.handleWindow(dialog, WindowEvent.WINDOW_OPENED);
            require(yesCount.get() == 2, "accept supports OK variant");
            settings(config, "AcceptIncomingConnectionAction=reject\n");
            handler.handleWindow(dialog, WindowEvent.WINDOW_OPENED);
            require(noCount.get() == 1 && yesCount.get() == 2, "reject clicks No only");
        } finally {
            dialog.dispose();
        }
    }

    private static void testBlindWarning(Path config) {
        BlindTradingWarningDialogHandler handler = new BlindTradingWarningDialogHandler();
        JDialog dialog = new JDialog();
        JPanel panel = new JPanel();
        AtomicInteger count = new AtomicInteger();
        JButton yes = new JButton("Yes");
        yes.addActionListener(event -> count.incrementAndGet());
        panel.add(new JLabel("Warning: blind trading"));
        panel.add(new JLabel("Are you sure you want to submit this order?"));
        panel.add(yes);
        dialog.add(panel);
        try {
            require(handler.recogniseWindow(dialog), "actual blind-warning recognizer");
            require(handler.filterEvent(dialog, WindowEvent.WINDOW_OPENED), "blind opened");
            settings(config, "AllowBlindTrading=no\n");
            handler.handleWindow(dialog, WindowEvent.WINDOW_OPENED);
            require(count.get() == 0, "default safety policy does not dismiss warning");
            settings(config, "AllowBlindTrading=yes\n");
            handler.handleWindow(dialog, WindowEvent.WINDOW_OPENED);
            require(count.get() == 1, "explicit setting permits Yes");
            yes.setText("Override and Transmit");
            handler.handleWindow(dialog, WindowEvent.WINDOW_OPENED);
            require(count.get() == 2, "alternate button supported");
        } finally {
            dialog.dispose();
        }
    }

    private static void testDispatcher() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
                Socket accepted = server.accept()) {
            client.setSoTimeout(5000);
            accepted.setSoTimeout(5000);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread dispatcher = new Thread(() -> {
                try {
                    new CommandDispatcher(new CommandChannel(accepted)).run();
                } catch (Throwable problem) {
                    failure.set(problem);
                }
            }, "engine-gui-command-test");
            dispatcher.setDaemon(true);
            dispatcher.start();
            client.getOutputStream().write(("RECONNECTDATA\nRECONNECTACCOUNT\nENABLEAPI\n"
                    + "INVALID\nEXIT\n").getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            List<String> replies = new ArrayList<>();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    client.getInputStream(), StandardCharsets.US_ASCII));
            String line;
            while ((line = reader.readLine()) != null) replies.add(line);
            dispatcher.join(5000);
            require(!dispatcher.isAlive(), "dispatcher must exit without hanging");
            require(failure.get() == null, "dispatcher must not throw before main-window readiness");
            require(replies.size() == 5, "one result per command");
            require(replies.get(0).equals("ERROR Gateway main window is not ready"), "early data reconnect rejected");
            require(replies.get(1).equals("ERROR Gateway main window is not ready"), "early account reconnect rejected");
            require(replies.get(2).startsWith("ERROR ENABLEAPI is not valid"), "Gateway excludes TWS-only command");
            require(replies.get(3).equals("ERROR Command invalid"), "unknown command rejected");
            require(replies.get(4).equals("OK Goodbye"), "EXIT acknowledged");
        }
    }

    private static void settings(Path file, String content) {
        try {
            Files.writeString(file, content + "SuppressInfoMessages=yes\nCommandPrompt=\n", StandardCharsets.ISO_8859_1);
            Settings.initialise(new DefaultSettings(file.toString()));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class TestMainWindow extends MainWindowManager {
        private volatile JFrame window;
        @Override public void logDiagnosticMessage() { }
        @Override public JFrame getMainWindow(long timeout, TimeUnit unit) { return window; }
        @Override public JFrame getMainWindow() { return window; }
        @Override public void setMainWindow(JFrame value) { window = value; }
        @Override public void iconizeIfRequired() { }
    }
}
