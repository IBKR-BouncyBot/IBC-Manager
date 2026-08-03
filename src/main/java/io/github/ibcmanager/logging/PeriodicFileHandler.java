package io.github.ibcmanager.logging;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.ErrorManager;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/** Buffers application records and normally commits them once every configured interval. */
final class PeriodicFileHandler extends Handler {
    static final int MAX_PENDING_CHARACTERS = 4 * 1024 * 1024;
    static final int MAX_RECORD_CHARACTERS = 256 * 1024;
    private final Object monitor = new Object();
    private final String pattern;
    private final int limit;
    private final int count;
    private final Runnable afterWrite;
    private final ScheduledExecutorService scheduler;
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private int pendingCharacters;
    private long droppedRecords;
    private boolean closed;

    PeriodicFileHandler(String pattern, int limit, int count, Duration interval, Runnable afterWrite) {
        this.pattern = Objects.requireNonNull(pattern, "pattern");
        if (limit <= 0) throw new IllegalArgumentException("Log rotation limit must be positive");
        if (count <= 0) throw new IllegalArgumentException("Log rotation count must be positive");
        Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative() || interval.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Log flush interval must be between 1 millisecond and 1 hour");
        }
        this.limit = limit;
        this.count = count;
        this.afterWrite = Objects.requireNonNull(afterWrite, "afterWrite");
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "ibc-manager-log-flush");
            thread.setDaemon(true);
            return thread;
        };
        scheduler = Executors.newSingleThreadScheduledExecutor(factory);
        long delayMillis = Math.max(1L, interval.toMillis());
        scheduler.scheduleWithFixedDelay(this::flush, delayMillis, delayMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || !isLoggable(record)) return;
        String formatted;
        try {
            formatted = getFormatter().format(record);
        } catch (RuntimeException failure) {
            reportError("Could not format application log record", failure, ErrorManager.FORMAT_FAILURE);
            return;
        }
        if (formatted.length() > MAX_RECORD_CHARACTERS) {
            formatted = formatted.substring(0, MAX_RECORD_CHARACTERS)
                    + System.lineSeparator() + "[IBC Manager truncated an oversized log record]"
                    + System.lineSeparator();
        }
        synchronized (monitor) {
            if (closed) return;
            pending.addLast(formatted);
            pendingCharacters += formatted.length();
            while (pendingCharacters > MAX_PENDING_CHARACTERS && !pending.isEmpty()) {
                pendingCharacters -= pending.removeFirst().length();
                droppedRecords++;
            }
        }
    }

    @Override
    public void flush() {
        synchronized (monitor) {
            if (closed || (pending.isEmpty() && droppedRecords == 0)) return;
            commitPendingLocked(ErrorManager.WRITE_FAILURE,
                    "Could not commit buffered application log records");
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        synchronized (monitor) {
            if (closed) return;
            closed = true;
            if (pending.isEmpty() && droppedRecords == 0) return;
            commitPendingLocked(ErrorManager.CLOSE_FAILURE,
                    "Could not commit final buffered application log records");
        }
    }

    private void commitPendingLocked(int errorCode, String errorMessage) {
        StringBuilder payload = new StringBuilder(Math.min(MAX_PENDING_CHARACTERS + 256,
                pendingCharacters + 256));
        if (droppedRecords > 0) {
            payload.append("[IBC Manager dropped ").append(droppedRecords)
                    .append(" buffered application log record(s) because the in-memory limit was reached]")
                    .append(System.lineSeparator());
        }
        for (String record : pending) payload.append(record);
        try {
            writePayload(payload.toString());
            pending.clear();
            pendingCharacters = 0;
            droppedRecords = 0;
        } catch (IOException | RuntimeException failure) {
            reportError(errorMessage, asException(failure), errorCode);
            return;
        }
        try {
            afterWrite.run();
        } catch (RuntimeException failure) {
            reportError("Application log was written, but post-write maintenance failed",
                    failure, ErrorManager.GENERIC_FAILURE);
        }
    }

    private void writePayload(String payload) throws IOException {
        FileHandler target = new FileHandler(pattern, limit, count, true);
        try {
            target.setEncoding("UTF-8");
            target.setLevel(Level.ALL);
            target.setFormatter(RawFormatter.INSTANCE);
            target.publish(new LogRecord(Level.INFO, payload));
        } finally {
            target.close();
        }
    }

    private static Exception asException(Throwable failure) {
        return failure instanceof Exception exception ? exception : new Exception(failure);
    }

    private static final class RawFormatter extends Formatter {
        private static final RawFormatter INSTANCE = new RawFormatter();
        @Override public String format(LogRecord record) { return record.getMessage(); }
    }
}
