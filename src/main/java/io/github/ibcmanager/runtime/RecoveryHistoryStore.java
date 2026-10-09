package io.github.ibcmanager.runtime;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.security.BoundedFileReader;
import io.github.ibcmanager.security.FilePermissionHardener;
import io.github.ibcmanager.security.SecureFileOperations;
import io.github.ibcmanager.storage.AtomicFileWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Persists the bounded automatic-recovery rate limit across Manager restarts. */
public final class RecoveryHistoryStore {
    public static final Duration WINDOW = Duration.ofHours(1);
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_FILE_BYTES = 4096;
    private static final int MAX_STORED_ATTEMPTS = 16;
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

    private final AppPaths paths;

    public RecoveryHistoryStore(AppPaths paths) {
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    public synchronized BeginResult beginAttempt(UUID profileId, Instant now, int maximumAttempts)
            throws IOException {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(now, "now");
        if (maximumAttempts < 1) throw new IllegalArgumentException("maximumAttempts must be positive");
        State state = load(profileId, now);
        if (state.awaitingHealthy()) {
            return new BeginResult(BeginDisposition.PREVIOUS_ATTEMPT_PENDING,
                    state.attempts().size(), maximumAttempts);
        }
        if (state.attempts().size() >= maximumAttempts) {
            return new BeginResult(BeginDisposition.RATE_LIMITED,
                    state.attempts().size(), maximumAttempts);
        }
        List<Instant> attempts = new ArrayList<>(state.attempts());
        attempts.add(now);
        write(profileId, new State(true, List.copyOf(attempts)));
        return new BeginResult(BeginDisposition.STARTED, attempts.size(), maximumAttempts);
    }

    public synchronized void markHealthy(UUID profileId, Instant now) throws IOException {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(now, "now");
        State state = load(profileId, now);
        if (!state.awaitingHealthy()) return;
        write(profileId, new State(false, state.attempts()));
    }

    public synchronized void resetPendingAttempt(UUID profileId, Instant now) throws IOException {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(now, "now");
        State state = load(profileId, now);
        if (!state.awaitingHealthy()) return;
        write(profileId, new State(false, state.attempts()));
    }

    public synchronized State snapshot(UUID profileId, Instant now) throws IOException {
        return load(profileId, now);
    }

    /** Clears recovery bookkeeping only after an explicit manual intervention. */
    public synchronized void clear(UUID profileId) throws IOException {
        Objects.requireNonNull(profileId, "profileId");
        Path file = paths.recoveryHistory(profileId);
        if (Files.isSymbolicLink(file)) {
            Files.deleteIfExists(file);
            throw new IOException("Removed an unsafe symbolic automatic-recovery history link: " + file);
        }
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            SecureFileOperations.requireRegularFile(file, "Automatic-recovery history");
        }
        Files.deleteIfExists(file);
    }

    private State load(UUID profileId, Instant now) throws IOException {
        Path file = paths.recoveryHistory(profileId);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new State(false, List.of());
        String text = BoundedFileReader.readString(file, StandardCharsets.UTF_8,
                MAX_FILE_BYTES, "Automatic-recovery history");
        Integer version = null;
        Boolean awaitingHealthy = null;
        List<Instant> attempts = new ArrayList<>();
        int lineNumber = 0;
        for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            lineNumber++;
            if (raw.isBlank() || raw.startsWith("#")) continue;
            int separator = raw.indexOf('=');
            if (separator <= 0) throw new IOException("Invalid automatic-recovery history line " + lineNumber);
            String key = raw.substring(0, separator);
            String value = raw.substring(separator + 1);
            switch (key) {
                case "formatVersion" -> {
                    if (version != null) throw new IOException("Duplicate recovery-history formatVersion");
                    version = parseInt(value, "formatVersion");
                }
                case "awaitingHealthy" -> {
                    if (awaitingHealthy != null) throw new IOException("Duplicate recovery-history awaitingHealthy");
                    awaitingHealthy = parseBoolean(value, "awaitingHealthy");
                }
                case "attemptEpochMillis" -> {
                    if (attempts.size() >= MAX_STORED_ATTEMPTS) {
                        throw new IOException("Automatic-recovery history contains too many attempts");
                    }
                    long epoch = parseLong(value, "attemptEpochMillis");
                    Instant attempt;
                    try {
                        attempt = Instant.ofEpochMilli(epoch);
                    } catch (RuntimeException ex) {
                        throw new IOException("Invalid automatic-recovery attempt timestamp", ex);
                    }
                    if (attempt.isAfter(now.plus(MAX_FUTURE_SKEW))) {
                        throw new IOException("Automatic-recovery history contains a timestamp too far in the future");
                    }
                    attempts.add(attempt);
                }
                default -> throw new IOException("Unknown automatic-recovery history key: " + key);
            }
        }
        if (version == null || version != FORMAT_VERSION) {
            throw new IOException("Unsupported automatic-recovery history format");
        }
        if (awaitingHealthy == null) throw new IOException("Missing automatic-recovery awaitingHealthy value");
        Instant cutoff = now.minus(WINDOW);
        List<Instant> retained = attempts.stream()
                .filter(attempt -> !attempt.isBefore(cutoff))
                .sorted(Comparator.naturalOrder())
                .toList();
        State state = new State(awaitingHealthy, retained);
        if (!retained.equals(attempts)) write(profileId, state);
        return state;
    }

    private void write(UUID profileId, State state) throws IOException {
        Path file = paths.recoveryHistory(profileId);
        SecureFileOperations.ensureDirectory(file.getParent());
        StringBuilder text = new StringBuilder();
        text.append("formatVersion=").append(FORMAT_VERSION).append('\n');
        text.append("awaitingHealthy=").append(state.awaitingHealthy()).append('\n');
        for (Instant attempt : state.attempts()) {
            text.append("attemptEpochMillis=").append(attempt.toEpochMilli()).append('\n');
        }
        AtomicFileWriter.write(file, text.toString().getBytes(StandardCharsets.UTF_8), false);
        FilePermissionHardener.hardenFile(file);
    }

    private static int parseInt(String value, String key) throws IOException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new IOException("Invalid integer for " + key, ex);
        }
    }

    private static long parseLong(String value, String key) throws IOException {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new IOException("Invalid long for " + key, ex);
        }
    }

    private static boolean parseBoolean(String value, String key) throws IOException {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IOException("Invalid boolean for " + key);
    }

    public enum BeginDisposition {
        STARTED,
        PREVIOUS_ATTEMPT_PENDING,
        RATE_LIMITED
    }

    public record BeginResult(BeginDisposition disposition, int attemptsInWindow, int maximumAttempts) {
        public BeginResult {
            Objects.requireNonNull(disposition, "disposition");
            if (attemptsInWindow < 0 || maximumAttempts < 1) {
                throw new IllegalArgumentException("Invalid recovery-attempt counts");
            }
        }
    }

    public record State(boolean awaitingHealthy, List<Instant> attempts) {
        public State {
            attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
        }
    }
}
