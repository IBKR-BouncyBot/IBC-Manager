package ibcalpha.ibc;

import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;

import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class HeadlessCommandTests implements TestSuite {
    @Override public String name() { return "Real engine command dispatch in a headless JVM"; }
    @Override public List<NamedTest> tests() {
        return List.of(
                new NamedTest("dispatcher class initializes without Toolkit shortcut access", () -> probe("init")),
                new NamedTest("headless reconnect is rejected but subsequent EXIT remains usable", () -> probe("commands")),
                new NamedTest("real STOP is acknowledged and exits a headless pre-login engine", () -> probe("stop")));
    }
    private void probe(String mode) throws Exception {
        Path dir = TestSupport.tempDirectory("headless-commands"); Process child = null;
        try {
            Path config = dir.resolve("fixture.ini"); Path log = dir.resolve("child.log");
            Files.writeString(config, "FIX=no\nTradingMode=paper\nCommandPrompt=\nSuppressInfoMessages=yes\n");
            child = new ProcessBuilder(TestSupport.javaExecutable().toString(), "-Djava.awt.headless=true", "-cp",
                    System.getProperty("java.class.path"), HeadlessCommandProbe.class.getName(), mode, config.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (mode.equals("stop")) {
                Assertions.eventually(Duration.ofSeconds(8), () -> {
                    try { return Files.readString(log).contains("PROBE_PORT="); }
                    catch (java.io.IOException ex) { return false; }
                }, "real engine command socket available in headless mode");
                var match = Pattern.compile("PROBE_PORT=(\\d+)").matcher(Files.readString(log));
                Assertions.isTrue(match.find(), "port published");
                try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(match.group(1)))) {
                    socket.setSoTimeout(5000);
                    socket.getOutputStream().write("STOP\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    String reply = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                    Assertions.contains(reply, "OK Shutting down: STOP command", "STOP acknowledgement, not a rejected command");
                }
            }
            Assertions.isTrue(child.waitFor(10, TimeUnit.SECONDS), "fixture completes without hanging");
            String output = Files.readString(log);
            Assertions.equals(0, child.exitValue(), "fixture passed: " + output);
            Assertions.notContains(output, "ExceptionInInitializerError", "no graphical class-initialization failure");
            if (mode.equals("init")) Assertions.contains(output, "DISPATCHER INITIALIZED", "class loaded");
            if (mode.equals("commands")) Assertions.contains(output, "HEADLESS COMMANDS PASSED", "actual protocol checked");
            if (mode.equals("stop")) Assertions.contains(output, "Login has not completed: exiting immediately", "real pre-login stop path");
        } finally {
            if (child != null && child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
            TestSupport.deleteTree(dir);
        }
    }
}
