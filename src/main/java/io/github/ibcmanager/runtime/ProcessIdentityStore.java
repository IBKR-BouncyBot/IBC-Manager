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
import java.util.Optional;
import java.util.UUID;

public final class ProcessIdentityStore {
    private static final int MAX_IDENTITY_BYTES = 4096;
    private static final Duration START_TOLERANCE = Duration.ofSeconds(2);
    private final AppPaths paths;

    public ProcessIdentityStore(AppPaths paths) { this.paths = java.util.Objects.requireNonNull(paths, "paths"); }

    public void save(UUID profileId, ManagedProcess process) throws IOException {
        Instant started = process.startInstant().orElseGet(Instant::now);
        String fingerprint = ProcessHandle.of(process.pid())
                .filter(ProcessHandle::isAlive)
                .flatMap(ProcessHandleIdentity::capture)
                .filter(identity -> withinTolerance(identity.startedAt(), started))
                .map(ProcessHandleIdentity::fingerprint)
                .orElse("");
        String content = "pid=" + process.pid() + "\nstartedAt=" + started
                + "\nfingerprint=" + fingerprint + "\n";
        Path target = paths.runtimeState(profileId);
        FilePermissionHardener.hardenDirectory(target.getParent());
        AtomicFileWriter.write(target, content.getBytes(StandardCharsets.UTF_8), false);
        FilePermissionHardener.hardenFile(target);
    }

    public Optional<ProcessIdentity> load(UUID profileId) {
        Path source = paths.runtimeState(profileId);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            String text = BoundedFileReader.readString(source, StandardCharsets.UTF_8,
                    MAX_IDENTITY_BYTES, "Process identity");
            long pid = -1;
            Instant started = null;
            String fingerprint = "";
            for (String line : text.split("\\R")) {
                int separator = line.indexOf('=');
                if (separator < 0) continue;
                String key = line.substring(0, separator);
                String value = line.substring(separator + 1);
                if (key.equals("pid")) pid = Long.parseLong(value);
                if (key.equals("startedAt")) started = Instant.parse(value);
                if (key.equals("fingerprint")) fingerprint = value;
            }
            if (pid < 1 || started == null || !fingerprint.matches("(?:|[0-9a-f]{64})")) {
                throw new IOException("Process identity is malformed");
            }
            return Optional.of(new ProcessIdentity(pid, started, fingerprint));
        } catch (Exception ex) {
            deleteInvalidQuietly(source);
            return Optional.empty();
        }
    }

    public Optional<ManagedProcess> reattach(UUID profileId) {
        Optional<ProcessIdentity> stored = load(profileId);
        if (stored.isEmpty()) return Optional.empty();
        ProcessIdentity identity = stored.get();
        ProcessHandleIdentity strong = new ProcessHandleIdentity(
                identity.pid(), identity.startedAt(), identity.fingerprint());
        Optional<ProcessHandle> handle = strong.resolve(START_TOLERANCE);
        if (handle.isEmpty()) {
            deleteInvalidQuietly(paths.runtimeState(profileId));
            return Optional.empty();
        }
        if (identity.fingerprint().isEmpty()) {
            // Upgrade an identity written by an earlier version after it has passed PID/start checks.
            try { save(profileId, new ReattachedManagedProcess(handle.get())); }
            catch (IOException ignored) { }
        }
        return Optional.of(new ReattachedManagedProcess(handle.get()));
    }

    public void delete(UUID profileId) throws IOException {
        Path state = paths.runtimeState(profileId);
        if (Files.isSymbolicLink(state)) {
            Files.deleteIfExists(state);
            throw new IOException("Removed an unsafe symbolic process-identity link: " + state);
        }
        if (Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
            SecureFileOperations.requireRegularFile(state, "Process identity");
        }
        Files.deleteIfExists(state);
    }

    private static boolean withinTolerance(Instant first, Instant second) {
        try { return Duration.between(first, second).abs().compareTo(START_TOLERANCE) <= 0; }
        catch (ArithmeticException ex) { return false; }
    }

    private static void deleteInvalidQuietly(Path source) {
        try {
            if (Files.isSymbolicLink(source) || SecureFileOperations.isRegularFile(source)) {
                Files.deleteIfExists(source);
            }
        } catch (IOException ignored) { }
    }
}
