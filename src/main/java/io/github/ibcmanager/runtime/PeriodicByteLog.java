package io.github.ibcmanager.runtime;

import io.github.ibcmanager.security.FilePermissionHardener;
import io.github.ibcmanager.security.SecureFileOperations;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

final class PeriodicByteLog implements AutoCloseable {
    static final int MAX_PENDING_BYTES = 8 * 1024 * 1024;
    private static final byte[] DROP_PREFIX = "[IBC Manager dropped ".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] DROP_SUFFIX = " buffered process-log byte(s) because the in-memory limit was reached]\n"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private final Object monitor = new Object();
    private final Path file;
    private final ScheduledExecutorService scheduler;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream(8192);
    private long droppedBytes;
    private boolean closed;

    PeriodicByteLog(Path file, Duration interval, byte[] initialBytes) throws IOException {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative() || interval.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Log interval must be between 1 millisecond and 1 hour");
        }
        Path parent = this.file.getParent();
        if (parent == null) throw new IOException("Log file has no parent directory: " + file);
        FilePermissionHardener.hardenDirectory(parent);
        if (Files.exists(this.file, LinkOption.NOFOLLOW_LINKS)) {
            SecureFileOperations.requireRegularFile(this.file, "Process log");
        }
        if (initialBytes != null && initialBytes.length > 0) appendBounded(initialBytes, 0, initialBytes.length);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "ibc-manager-process-log-flush");
            thread.setDaemon(true);
            return thread;
        };
        scheduler = Executors.newSingleThreadScheduledExecutor(factory);
        long delay = Math.max(1L, interval.toMillis());
        scheduler.scheduleWithFixedDelay(this::flushSafely, delay, delay, TimeUnit.MILLISECONDS);
    }

    void append(byte[] bytes, int offset, int length) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return;
        synchronized (monitor) {
            if (closed) return;
            appendBounded(bytes, offset, length);
        }
    }

    private void appendBounded(byte[] bytes, int offset, int length) {
        if (length >= MAX_PENDING_BYTES) {
            droppedBytes += pending.size() + (long) length - MAX_PENDING_BYTES;
            pending.reset();
            pending.write(bytes, offset + length - MAX_PENDING_BYTES, MAX_PENDING_BYTES);
            return;
        }
        int overflow = pending.size() + length - MAX_PENDING_BYTES;
        if (overflow > 0) {
            byte[] existing = pending.toByteArray();
            pending.reset();
            pending.write(existing, overflow, existing.length - overflow);
            java.util.Arrays.fill(existing, (byte) 0);
            droppedBytes += overflow;
        }
        pending.write(bytes, offset, length);
    }

    void flush() throws IOException {
        synchronized (monitor) {
            if (closed || (pending.size() == 0 && droppedBytes == 0)) return;
            writePendingLocked();
        }
    }

    private void flushSafely() {
        try { flush(); }
        catch (IOException ignored) {
            // Preserve the bounded pending data for the next interval/final close.
        }
    }

    @Override
    public void close() throws IOException {
        scheduler.shutdownNow();
        synchronized (monitor) {
            if (closed) return;
            if (pending.size() > 0 || droppedBytes > 0) writePendingLocked();
            closed = true;
        }
    }

    private void writePendingLocked() throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            SecureFileOperations.requireRegularFile(file, "Process log");
        }
        byte[] payload = pending.toByteArray();
        try {
            try (FileChannel channel = FileChannel.open(file,
                    Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                            StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS))) {
                if (droppedBytes > 0) {
                    writeAll(channel, DROP_PREFIX);
                    writeAll(channel, Long.toString(droppedBytes)
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    writeAll(channel, DROP_SUFFIX);
                }
                writeAll(channel, payload);
                channel.force(false);
            }
        } finally {
            java.util.Arrays.fill(payload, (byte) 0);
        }
        pending.reset();
        droppedBytes = 0;
        FilePermissionHardener.hardenFile(file);
    }

    private static void writeAll(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
    }
}
