package io.github.ibcmanager.runtime;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProcessTreeTerminator {
    public boolean terminate(ManagedProcess process, Duration gracefulTimeout, Duration forceTimeout)
            throws InterruptedException {
        if (process == null || !process.isAlive()) return true;
        List<ProcessHandle> originalDescendants = process.descendants();
        destroyAlive(originalDescendants, false);
        process.destroy();
        boolean rootStopped = process.waitFor(gracefulTimeout);
        if (rootStopped && waitForExit(originalDescendants, gracefulTimeout)) return true;

        Map<Long, ProcessHandle> remaining = new LinkedHashMap<>();
        for (ProcessHandle child : originalDescendants) remaining.put(child.pid(), child);
        for (ProcessHandle child : process.descendants()) remaining.put(child.pid(), child);
        destroyAlive(List.copyOf(remaining.values()), true);
        if (process.isAlive()) process.destroyForcibly();
        rootStopped = process.waitFor(forceTimeout) || !process.isAlive();
        boolean descendantsStopped = waitForExit(List.copyOf(remaining.values()), forceTimeout);
        return rootStopped && descendantsStopped;
    }

    private static void destroyAlive(List<ProcessHandle> handles, boolean forcibly) {
        for (ProcessHandle handle : handles) {
            if (!handle.isAlive()) continue;
            if (forcibly) handle.destroyForcibly();
            else handle.destroy();
        }
    }

    private static boolean waitForExit(List<ProcessHandle> handles, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            boolean anyAlive = handles.stream().anyMatch(ProcessHandle::isAlive);
            if (!anyAlive) return true;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            long sleepMillis = Math.min(25, Math.max(1, remaining / 1_000_000));
            Thread.sleep(sleepMillis);
        }
    }
}
