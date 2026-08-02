package io.github.ibcmanager.tests;

import io.github.ibcmanager.app.SingleInstanceLock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

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
            case "spawn-child" -> spawnChild(longArgument(arguments, 1));
            case "try-lock" -> tryLock(stringArgument(arguments, 1));
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

    private static void spawnChild(long sleepMillis) throws Exception {
        Process child = new ProcessBuilder(TestSupport.javaCommand("sleep", Long.toString(sleepMillis))).start();
        System.out.println("CHILD:" + child.pid());
        System.out.flush();
        int result = child.waitFor();
        System.exit(result);
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
