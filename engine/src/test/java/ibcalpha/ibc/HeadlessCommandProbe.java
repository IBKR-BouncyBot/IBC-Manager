package ibcalpha.ibc;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Fresh-JVM exercise of the real dispatcher; never launches Gateway or reads real credentials. */
public final class HeadlessCommandProbe {
    private HeadlessCommandProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("mode fixture.ini required");
        if (!java.awt.GraphicsEnvironment.isHeadless()) throw new AssertionError("Probe must run headless");
        if (args[0].equals("init")) {
            Class.forName("ibcalpha.ibc.CommandDispatcher");
            System.out.println("DISPATCHER INITIALIZED WITHOUT A DESKTOP");
            return;
        }
        IbcTws.setupDefaultEnvironment(new String[] {args[1]}, true);
        // Mark a fixture session as started without scheduling login timeout or invoking GWClient.
        System.setProperty("restart", "test-only-no-gateway");
        SessionManager.startSession();
        new CommandServer(); // Same singleton installed by IbcTws.load before dispatch. No listening port opened here.
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(10000);
            if (args[0].equals("stop")) {
                System.out.println("PROBE_PORT=" + server.getLocalPort());
                System.out.flush();
                try (Socket accepted = server.accept()) {
                    accepted.setSoTimeout(10000);
                    new CommandDispatcher(new CommandChannel(accepted)).run();
                }
                throw new AssertionError("STOP must halt the not-logged-in fixture process");
            }
            if (!args[0].equals("commands")) throw new IllegalArgumentException("Unknown mode");
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
                    Socket accepted = server.accept()) {
                client.setSoTimeout(5000); accepted.setSoTimeout(5000);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread dispatcher = new Thread(() -> {
                    try { new CommandDispatcher(new CommandChannel(accepted)).run(); }
                    catch (Throwable ex) { failure.set(ex); }
                });
                dispatcher.setDaemon(true);
                dispatcher.start();
                client.getOutputStream().write(("RECONNECTDATA\nRECONNECTACCOUNT\nENABLEAPI\nINVALID\nEXIT\n")
                        .getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                List<String> replies = new ArrayList<>();
                BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                String line;
                while ((line = reader.readLine()) != null) replies.add(line);
                dispatcher.join(5000);
                if (dispatcher.isAlive() || failure.get() != null) throw new AssertionError("Dispatcher failed", failure.get());
                List<String> expected = List.of("ERROR Reconnect requires an available graphical desktop",
                        "ERROR Reconnect requires an available graphical desktop",
                        "ERROR ENABLEAPI is not valid for the IB Gateway", "ERROR Command invalid", "OK Goodbye");
                if (!expected.equals(replies)) throw new AssertionError("Unexpected replies: " + replies);
                System.out.println("HEADLESS COMMANDS PASSED: " + replies.size() + " exact replies");
            }
        }
    }
}
