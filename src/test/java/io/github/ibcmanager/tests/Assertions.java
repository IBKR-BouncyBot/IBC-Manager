package io.github.ibcmanager.tests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Callable;

public final class Assertions {
    private static final ThreadLocal<Integer> COUNT = ThreadLocal.withInitial(() -> 0);

    private Assertions() { }

    static void resetCount() { COUNT.set(0); }
    static int count() { return COUNT.get(); }
    private static void countOne() { COUNT.set(COUNT.get() + 1); }

    public static void isTrue(boolean condition, String message) {
        countOne();
        if (!condition) fail(message);
    }

    public static void isFalse(boolean condition, String message) {
        isTrue(!condition, message);
    }

    public static void equals(Object expected, Object actual, String message) {
        countOne();
        if (!Objects.equals(expected, actual)) {
            fail(message + " expected=<" + expected + "> actual=<" + actual + ">");
        }
    }

    public static void notEquals(Object first, Object second, String message) {
        countOne();
        if (Objects.equals(first, second)) fail(message + " both=<" + first + ">");
    }

    public static void arrayEquals(char[] expected, char[] actual, String message) {
        countOne();
        if (!Arrays.equals(expected, actual)) fail(message);
    }

    public static void contains(String text, String expected, String message) {
        countOne();
        if (text == null || !text.contains(expected)) fail(message + " missing=<" + expected + ">");
    }

    public static void notContains(String text, String unexpected, String message) {
        countOne();
        if (text != null && text.contains(unexpected)) fail(message + " found=<" + unexpected + ">");
    }

    public static void fileExists(Path path, String message) {
        countOne();
        if (!Files.isRegularFile(path)) fail(message + " path=" + path);
    }

    public static void directoryExists(Path path, String message) {
        countOne();
        if (!Files.isDirectory(path)) fail(message + " path=" + path);
    }

    public static <T extends Throwable> T throwsType(Class<T> type, ThrowingRunnable action, String message) {
        countOne();
        try {
            action.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) return type.cast(throwable);
            fail(message + " wrong exception: " + throwable, throwable);
        }
        fail(message + " no exception was thrown");
        return null;
    }

    public static void eventually(Duration timeout, Callable<Boolean> condition, String message) throws Exception {
        countOne();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.call()) return;
            Thread.sleep(10);
        }
        fail(message);
    }

    public static void fail(String message) {
        throw new AssertionError(message);
    }

    public static void fail(String message, Throwable cause) {
        AssertionError error = new AssertionError(message);
        error.initCause(cause);
        throw error;
    }
}
