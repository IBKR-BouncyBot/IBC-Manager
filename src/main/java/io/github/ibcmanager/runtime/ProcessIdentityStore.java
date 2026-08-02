package io.github.ibcmanager.runtime;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.storage.AtomicFileWriter;
import io.github.ibcmanager.security.FilePermissionHardener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class ProcessIdentityStore {
    private final AppPaths paths;

    public ProcessIdentityStore(AppPaths paths) {
        this.paths = paths;
    }

    public void save(UUID profileId, ManagedProcess process) throws IOException {
        Instant started = process.startInstant().orElseGet(Instant::now);
        String content = "pid=" + process.pid() + "\nstartedAt=" + started + "\n";
        Path target = paths.runtimeState(profileId);
        FilePermissionHardener.hardenDirectory(target.getParent());
        AtomicFileWriter.write(target, content.getBytes(StandardCharsets.UTF_8), false);
        FilePermissionHardener.hardenFile(target);
    }

    public Optional<ProcessIdentity> load(UUID profileId) {
        Path source = paths.runtimeState(profileId);
        if (!Files.isRegularFile(source)) return Optional.empty();
        try {
            long pid = -1;
            Instant started = null;
            for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                int separator = line.indexOf('=');
                if (separator < 0) continue;
                String key = line.substring(0, separator);
                String value = line.substring(separator + 1);
                if (key.equals("pid")) pid = Long.parseLong(value);
                if (key.equals("startedAt")) started = Instant.parse(value);
            }
            if (pid < 1 || started == null) return Optional.empty();
            return Optional.of(new ProcessIdentity(pid, started));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    public Optional<ManagedProcess> reattach(UUID profileId) {
        Optional<ProcessIdentity> identity = load(profileId);
        if (identity.isEmpty()) return Optional.empty();
        Optional<ProcessHandle> handle = ProcessHandle.of(identity.get().pid());
        if (handle.isEmpty() || !handle.get().isAlive()) return Optional.empty();
        Optional<Instant> actualStart = handle.get().info().startInstant();
        if (actualStart.isEmpty()) return Optional.empty();
        long difference = Math.abs(actualStart.get().toEpochMilli() - identity.get().startedAt().toEpochMilli());
        if (difference > 2000) return Optional.empty();
        return Optional.of(new ReattachedManagedProcess(handle.get()));
    }

    public void delete(UUID profileId) throws IOException {
        Files.deleteIfExists(paths.runtimeState(profileId));
    }
}
