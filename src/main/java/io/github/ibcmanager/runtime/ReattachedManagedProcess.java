package io.github.ibcmanager.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class ReattachedManagedProcess implements ManagedProcess {
    private final ProcessHandle handle;

    ReattachedManagedProcess(ProcessHandle handle) {
        this.handle = handle;
    }

    @Override public long pid() { return handle.pid(); }
    @Override public boolean isAlive() { return handle.isAlive(); }
    @Override public Optional<Instant> startInstant() { return handle.info().startInstant(); }
    @Override public List<ProcessHandle> descendants() {
        try (var stream = handle.descendants()) {
            return stream.sorted(Comparator.comparingLong(ProcessHandle::pid).reversed()).toList();
        }
    }
    @Override public CompletableFuture<ProcessHandle> onExit() { return handle.onExit(); }
    @Override public boolean waitFor(Duration timeout) throws InterruptedException {
        try {
            handle.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException ex) {
            return false;
        } catch (java.util.concurrent.ExecutionException ex) {
            return !handle.isAlive();
        }
    }
    @Override public void destroy() { handle.destroy(); }
    @Override public void destroyForcibly() { handle.destroyForcibly(); }
    @Override public OptionalInt exitCode() { return OptionalInt.empty(); }
}
