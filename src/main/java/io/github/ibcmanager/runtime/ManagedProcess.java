package io.github.ibcmanager.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

public interface ManagedProcess {
    long pid();
    boolean isAlive();
    Optional<Instant> startInstant();
    List<ProcessHandle> descendants();
    CompletableFuture<ProcessHandle> onExit();
    boolean waitFor(Duration timeout) throws InterruptedException;
    void destroy();
    void destroyForcibly();
    OptionalInt exitCode();
    default boolean hasLiveOutput() { return false; }
    default List<String> drainOutputLines() { return List.of(); }
}
