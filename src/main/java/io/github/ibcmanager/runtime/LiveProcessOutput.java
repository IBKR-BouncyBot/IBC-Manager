package io.github.ibcmanager.runtime;

import io.github.ibcmanager.security.SecretRedactor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class LiveProcessOutput {
    static final int MAX_QUEUED_LINES = 10_000;
    static final int MAX_QUEUED_CHARACTERS = 4 * 1024 * 1024;
    static final int MAX_UNTERMINATED_LINE_CHARACTERS = 64 * 1024;
    private final Object monitor = new Object();
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final CountDownLatch finished = new CountDownLatch(1);
    private int queuedCharacters;
    private long droppedLines;

    LiveProcessOutput(InputStream input) {
        Objects.requireNonNull(input, "input");
        Thread reader = new Thread(() -> read(input), "ibc-manager-live-process-output");
        reader.setDaemon(true);
        reader.start();
    }

    List<String> drain() {
        synchronized (monitor) {
            int extra = droppedLines > 0 ? 1 : 0;
            List<String> result = new ArrayList<>(lines.size() + extra);
            if (droppedLines > 0) {
                result.add("IBC Manager: " + droppedLines
                        + " live process log lines were dropped because the in-memory queue was full");
                droppedLines = 0;
            }
            result.addAll(lines);
            lines.clear();
            queuedCharacters = 0;
            return List.copyOf(result);
        }
    }

    void await(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        try { finished.await(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    private void read(InputStream input) {
        StringBuilder current = new StringBuilder();
        boolean previousCarriageReturn = false;
        boolean lineTruncated = false;
        try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                for (int index = 0; index < count; index++) {
                    char value = buffer[index];
                    if (value == '\r') {
                        addCompletedLine(current, lineTruncated);
                        current.setLength(0);
                        lineTruncated = false;
                        previousCarriageReturn = true;
                    } else if (value == '\n') {
                        if (!previousCarriageReturn) {
                            addCompletedLine(current, lineTruncated);
                            current.setLength(0);
                            lineTruncated = false;
                        }
                        previousCarriageReturn = false;
                    } else {
                        previousCarriageReturn = false;
                        if (current.length() < MAX_UNTERMINATED_LINE_CHARACTERS) current.append(value);
                        else lineTruncated = true;
                    }
                }
            }
            if (current.length() > 0 || lineTruncated) addCompletedLine(current, lineTruncated);
        } catch (IOException ex) {
            add("IBC Manager: live process output ended with an error: " + safeMessage(ex));
        } finally {
            finished.countDown();
        }
    }

    private void addCompletedLine(StringBuilder current, boolean truncated) {
        String suffix = truncated ? " [line truncated by IBC Manager]" : "";
        add(SecretRedactor.redact(current + suffix));
    }

    private void add(String line) {
        synchronized (monitor) {
            while (!lines.isEmpty() && (lines.size() >= MAX_QUEUED_LINES
                    || queuedCharacters + line.length() > MAX_QUEUED_CHARACTERS)) {
                queuedCharacters -= lines.removeFirst().length();
                droppedLines++;
            }
            if (line.length() > MAX_QUEUED_CHARACTERS) {
                line = line.substring(line.length() - MAX_QUEUED_CHARACTERS);
            }
            lines.addLast(line);
            queuedCharacters += line.length();
        }
    }

    private static String safeMessage(IOException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
