package io.github.ibcmanager.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Immutable identity used to prevent PID reuse from targeting an unrelated process. */
public record ProcessHandleIdentity(long pid, Instant startedAt, String fingerprint) {
    public ProcessHandleIdentity {
        if (pid < 1) throw new IllegalArgumentException("pid must be positive");
        Objects.requireNonNull(startedAt, "startedAt");
        fingerprint = Objects.requireNonNullElse(fingerprint, "");
    }

    public static Optional<ProcessHandleIdentity> capture(ProcessHandle handle) {
        Objects.requireNonNull(handle, "handle");
        Optional<Instant> started = handle.info().startInstant();
        if (started.isEmpty()) return Optional.empty();
        return Optional.of(new ProcessHandleIdentity(handle.pid(), started.get(), fingerprint(handle.info())));
    }

    public Optional<ProcessHandle> resolve(Duration startTimeTolerance) {
        Objects.requireNonNull(startTimeTolerance, "startTimeTolerance");
        if (startTimeTolerance.isNegative()) {
            throw new IllegalArgumentException("startTimeTolerance must not be negative");
        }
        Optional<ProcessHandle> candidate = ProcessHandle.of(pid);
        if (candidate.isEmpty() || !candidate.get().isAlive()) return Optional.empty();
        Optional<Instant> actualStart = candidate.get().info().startInstant();
        if (actualStart.isEmpty()) return Optional.empty();
        long differenceNanos;
        try {
            differenceNanos = Math.abs(Duration.between(startedAt, actualStart.get()).toNanos());
        } catch (ArithmeticException overflow) {
            return Optional.empty();
        }
        if (differenceNanos > startTimeTolerance.toNanos()) return Optional.empty();
        if (!fingerprint.isEmpty()) {
            String actualFingerprint = fingerprint(candidate.get().info());
            if (!fingerprintsCompatible(fingerprint, actualFingerprint)) return Optional.empty();
        }
        return candidate;
    }

    public boolean matches(ProcessHandle handle, Duration startTimeTolerance) {
        return handle != null && handle.pid() == pid
                && resolve(startTimeTolerance).map(candidate -> candidate.equals(handle)).orElse(false);
    }

    static boolean fingerprintsCompatible(String expected, String actual) {
        String normalizedExpected = Objects.requireNonNullElse(expected, "");
        String normalizedActual = Objects.requireNonNullElse(actual, "");
        // Process metadata can disappear briefly while an already-captured process is exiting.
        // PID plus start time remain the authoritative identity in that case. Reject only an
        // explicit, non-empty mismatch so cleanup cannot report success while the original
        // process is still alive but its command/user metadata has become unavailable.
        return normalizedExpected.isEmpty() || normalizedActual.isEmpty()
                || normalizedExpected.equals(normalizedActual);
    }

    static String fingerprint(ProcessHandle.Info info) {
        String command = info.command().orElse("");
        if (command.isEmpty()) return "";
        String material = command;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
