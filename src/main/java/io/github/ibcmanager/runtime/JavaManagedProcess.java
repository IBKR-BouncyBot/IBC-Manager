package io.github.ibcmanager.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class JavaManagedProcess implements ManagedProcess {
    private final Process process;

    JavaManagedProcess(Process process) {
        this.process = process;
    }

    @Override public long pid() { return process.pid(); }
    @Override public boolean isAlive() { return process.isAlive(); }
    @Override public Optional<Instant> startInstant() { return process.info().startInstant(); }
    @Override public List<ProcessHandle> descendants() {
        try (var stream = process.descendants()) {
            return stream.sorted(Comparator.comparingLong(ProcessHandle::pid).reversed()).toList();
        }
    }
    @Override public CompletableFuture<ProcessHandle> onExit() { return process.toHandle().onExit(); }
    @Override public boolean waitFor(Duration timeout) throws InterruptedException {
        return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
    @Override public void destroy() { process.destroy(); }
    @Override public void destroyForcibly() { process.destroyForcibly(); }
    @Override public OptionalInt exitCode() {
        if (process.isAlive()) return OptionalInt.empty();
        try { return OptionalInt.of(process.exitValue()); }
        catch (IllegalThreadStateException ex) { return OptionalInt.empty(); }
    }
}
