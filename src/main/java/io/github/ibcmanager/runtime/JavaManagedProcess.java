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
    private static final Duration OUTPUT_DRAIN_TIMEOUT = Duration.ofSeconds(2);
    private final Process process;
    private final LiveProcessOutput liveOutput;
    private final CompletableFuture<ProcessHandle> exitFuture;

    JavaManagedProcess(Process process) {
        this(process, null);
    }

    JavaManagedProcess(Process process, LiveProcessOutput liveOutput) {
        this.process = process;
        this.liveOutput = liveOutput;
        this.exitFuture = process.toHandle().onExit().thenApply(handle -> {
            if (this.liveOutput != null) this.liveOutput.await(OUTPUT_DRAIN_TIMEOUT);
            return handle;
        });
    }

    @Override public long pid() { return process.pid(); }
    @Override public boolean isAlive() { return process.isAlive(); }
    @Override public Optional<Instant> startInstant() { return process.info().startInstant(); }
    @Override public List<ProcessHandle> descendants() {
        try (var stream = process.descendants()) {
            return stream.sorted(Comparator.comparingLong(ProcessHandle::pid).reversed()).toList();
        }
    }
    @Override public CompletableFuture<ProcessHandle> onExit() { return exitFuture; }
    @Override public boolean waitFor(Duration timeout) throws InterruptedException {
        boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (exited && liveOutput != null) liveOutput.await(OUTPUT_DRAIN_TIMEOUT);
        return exited;
    }
    @Override public void destroy() { process.destroy(); }
    @Override public void destroyForcibly() { process.destroyForcibly(); }
    @Override public OptionalInt exitCode() {
        if (process.isAlive()) return OptionalInt.empty();
        try { return OptionalInt.of(process.exitValue()); }
        catch (IllegalThreadStateException ex) { return OptionalInt.empty(); }
    }
    @Override public boolean hasLiveOutput() { return liveOutput != null; }
    @Override public List<String> drainOutputLines() {
        if (liveOutput == null) return List.of();
        if (!process.isAlive()) liveOutput.await(OUTPUT_DRAIN_TIMEOUT);
        return liveOutput.drain();
    }
}
