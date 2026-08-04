package io.github.ibcmanager.tests;

import io.github.ibcmanager.app.SingleInstanceLock;
import io.github.ibcmanager.config.ConfigValueValidator;
import io.github.ibcmanager.runtime.DefaultProcessLauncher;
import io.github.ibcmanager.runtime.LaunchSpec;
import io.github.ibcmanager.runtime.ManagedProcess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small Java subprocess used by integration tests so the suite does not depend
 * on bash, cmd.exe, PowerShell, or other platform-specific shell tools.
 */
public final class SubprocessFixture {
    private static final int OUTPUT_BUFFER_SIZE = 8192;

    private SubprocessFixture() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 0) {
            throw new IllegalArgumentException("A subprocess-fixture mode is required");
        }
        switch (arguments[0]) {
            case "capture" -> captureInput();
            case "sleep" -> Thread.sleep(longArgument(arguments, 1));
            case "output" -> writeOutput(intArgument(arguments, 1));
            case "exit" -> System.exit(intArgument(arguments, 1));
            case "stdout-stderr" -> writeBothStreams();
            case "line-then-sleep" -> lineThenSleep(stringArgument(arguments, 1), longArgument(arguments, 2));
            case "launch-buffered-and-exit" -> launchBufferedAndExit(
                    stringArgument(arguments, 1), longArgument(arguments, 2));
            case "spawn-child" -> spawnChild(longArgument(arguments, 1));
            case "spawn-child-after-signal" -> spawnChildAfterSignal(
                    stringArgument(arguments, 1), longArgument(arguments, 2));
            case "write-pid-and-sleep" -> writePidAndSleep(
                    stringArgument(arguments, 1), longArgument(arguments, 2));
            case "try-lock" -> tryLock(stringArgument(arguments, 1));
            case "validate-documented-time" -> validateDocumentedTime();
            default -> throw new IllegalArgumentException("Unknown subprocess-fixture mode: " + arguments[0]);
        }
    }

    private static void captureInput() throws Exception {
        String input = new String(System.in.readAllBytes(), StandardCharsets.UTF_8)
                .replaceFirst("[\\r\\n]+\\z", "");
        System.out.println("OUT:" + input);
        System.err.println("ERR:" + input);
        System.out.flush();
        System.err.flush();
        System.exit(7);
    }

    private static void writeOutput(int size) throws Exception {
        if (size < 0) throw new IllegalArgumentException("Output size must not be negative");
        byte[] buffer = new byte[OUTPUT_BUFFER_SIZE];
        Arrays.fill(buffer, (byte) 'x');
        int remaining = size;
        while (remaining > 0) {
            int count = Math.min(remaining, buffer.length);
            System.out.write(buffer, 0, count);
            remaining -= count;
        }
        System.out.flush();
    }

    private static void writeBothStreams() {
        System.out.println("stdout");
        System.err.println("stderr");
        System.out.flush();
        System.err.flush();
    }

    private static void lineThenSleep(String line, long sleepMillis) throws Exception {
        System.out.println(line);
        System.out.flush();
        Thread.sleep(sleepMillis);
    }

    private static void launchBufferedAndExit(String logValue, long flushMillis) throws Exception {
        Path log = Path.of(logValue).toAbsolutePath().normalize();
        Files.createDirectories(log.getParent());
        Path stableWorkingDirectory = Path.of(System.getProperty("user.dir", "."))
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(stableWorkingDirectory)) {
            throw new IOException("Detached-relay fixture working directory is unavailable: "
                    + stableWorkingDirectory);
        }
        // Keep the detached relay's current directory outside the disposable log tree. Windows
        // can retain a terminating process's current-directory handle briefly after isAlive()
        // becomes false, which must not turn successful relay behavior into a cleanup failure.
        LaunchSpec spec = new LaunchSpec(
                TestSupport.javaCommand("line-then-sleep", "detached-buffered-line", "600"),
                stableWorkingDirectory, Map.of(), stableWorkingDirectory.resolve("none"),
                "detached test");
        ManagedProcess relay = new DefaultProcessLauncher(Duration.ofMillis(flushMillis))
                .launch(spec, log, "detached-session-header\n");
        System.out.println("RELAY:" + relay.pid());
        System.out.flush();
    }

    private static void spawnChild(long sleepMillis) throws Exception {
        Process child = new ProcessBuilder(TestSupport.javaCommand("sleep", Long.toString(sleepMillis))).start();
        System.out.println("CHILD:" + child.pid());
        System.out.flush();
        int result = child.waitFor();
        System.exit(result);
    }

    private static void writePidAndSleep(String pidFile, long sleepMillis) throws Exception {
        writePidAtomically(Path.of(pidFile), ProcessHandle.current().pid());
        Thread.sleep(sleepMillis);
    }

    private static void spawnChildAfterSignal(String childPidFile, long childSleepMillis) throws Exception {
        Path pidFile = Path.of(childPidFile).toAbsolutePath().normalize();
        Files.createDirectories(pidFile.getParent());
        System.out.println("READY");
        System.out.flush();
        if (System.in.read() < 0) return;

        Process child = new ProcessBuilder(
                TestSupport.javaCommand("sleep", Long.toString(childSleepMillis))).start();
        writePidAtomically(pidFile, child.pid());
        System.out.println("CHILD:" + child.pid());
        System.out.flush();
        Thread.sleep(750);
    }

    private static void writePidAtomically(Path value, long pid) throws IOException {
        Path target = value.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, Long.toString(pid), StandardCharsets.US_ASCII);
            try {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void tryLock(String value) throws Exception {
        SingleInstanceLock acquired;
        try {
            acquired = SingleInstanceLock.acquire(Path.of(value));
        } catch (IOException ex) {
            System.out.println("CONFLICT");
            System.out.flush();
            System.exit(23);
            return;
        }
        try {
            System.out.println("ACQUIRED:" + ProcessHandle.current().pid());
            System.out.flush();
        } finally {
            acquired.close();
        }
    }

    private static void validateDocumentedTime() {
        ConfigValueValidator validator = new ConfigValueValidator();
        var validIssues = validator.validate(Map.of("AutoRestartTime", "11:45 PM"));
        if (!validIssues.isEmpty()) {
            throw new IllegalStateException("Documented IBC time was rejected: " + validIssues);
        }
        var invalidIssues = validator.validate(Map.of("AutoRestartTime", "11:45 pm"));
        if (invalidIssues.isEmpty()) {
            throw new IllegalStateException("Lowercase am/pm was unexpectedly accepted");
        }
        Locale locale = Locale.getDefault(Locale.Category.FORMAT);
        System.out.println("TIME-VALIDATION-OK:" + locale.toLanguageTag());
        System.out.flush();
    }

    private static String stringArgument(String[] arguments, int index) {
        if (index >= arguments.length) throw new IllegalArgumentException("Missing string argument");
        return arguments[index];
    }

    private static int intArgument(String[] arguments, int index) {
        if (index >= arguments.length) throw new IllegalArgumentException("Missing integer argument");
        return Integer.parseInt(arguments[index]);
    }

    private static long longArgument(String[] arguments, int index) {
        if (index >= arguments.length) throw new IllegalArgumentException("Missing long argument");
        return Long.parseLong(arguments[index]);
    }
}
