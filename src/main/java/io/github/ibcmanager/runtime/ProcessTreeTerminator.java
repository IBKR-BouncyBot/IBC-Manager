package io.github.ibcmanager.runtime;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ProcessTreeTerminator {
    private static final Duration IDENTITY_TOLERANCE = Duration.ofSeconds(2);

    public boolean terminate(ManagedProcess process, Duration gracefulTimeout, Duration forceTimeout)
            throws InterruptedException {
        Objects.requireNonNull(gracefulTimeout, "gracefulTimeout");
        Objects.requireNonNull(forceTimeout, "forceTimeout");
        if (gracefulTimeout.isNegative() || forceTimeout.isNegative()) {
            throw new IllegalArgumentException("Process termination timeouts must not be negative");
        }
        if (process == null || !process.isAlive()) return true;

        Map<Long, ProcessHandleIdentity> known = new LinkedHashMap<>();
        captureInto(known, process.descendants());
        destroyAlive(List.copyOf(known.values()), false);
        process.destroy();

        boolean rootStopped = waitForRootAndCollect(process, known, gracefulTimeout, false);
        boolean descendantsStopped = waitForExit(List.copyOf(known.values()), gracefulTimeout);
        if (rootStopped && descendantsStopped) return true;

        if (process.isAlive()) captureInto(known, process.descendants());
        destroyAlive(List.copyOf(known.values()), true);
        if (process.isAlive()) process.destroyForcibly();
        rootStopped = waitForRootAndCollect(process, known, forceTimeout, true);
        descendantsStopped = waitForExit(List.copyOf(known.values()), forceTimeout);
        return rootStopped && descendantsStopped;
    }

    private static boolean waitForRootAndCollect(ManagedProcess process,
            Map<Long, ProcessHandleIdentity> known, Duration timeout, boolean forcibly)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (process.isAlive()) {
            captureInto(known, process.descendants());
            destroyAlive(List.copyOf(known.values()), forcibly);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            long waitMillis = Math.min(25, Math.max(1, remaining / 1_000_000));
            process.waitFor(Duration.ofMillis(waitMillis));
        }
        return true;
    }

    private static void captureInto(Map<Long, ProcessHandleIdentity> target, List<ProcessHandle> handles) {
        for (ProcessHandle handle : handles) {
            ProcessHandleIdentity.capture(handle).ifPresent(identity -> target.putIfAbsent(identity.pid(), identity));
        }
    }

    private static void destroyAlive(List<ProcessHandleIdentity> identities, boolean forcibly) {
        for (ProcessHandleIdentity identity : identities) {
            identity.resolve(IDENTITY_TOLERANCE).ifPresent(handle -> {
                if (forcibly) handle.destroyForcibly();
                else handle.destroy();
            });
        }
    }

    private static boolean waitForExit(List<ProcessHandleIdentity> identities, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            boolean anyAlive = identities.stream()
                    .anyMatch(identity -> identity.resolve(IDENTITY_TOLERANCE).isPresent());
            if (!anyAlive) return true;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            long sleepMillis = Math.min(25, Math.max(1, remaining / 1_000_000));
            Thread.sleep(sleepMillis);
        }
    }
}
