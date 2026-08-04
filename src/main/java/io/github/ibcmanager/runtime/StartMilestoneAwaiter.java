package io.github.ibcmanager.runtime;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;

/** Waits for the StartIBC launch marker while an offline-installation start lock is held. */
final class StartMilestoneAwaiter {
    @FunctionalInterface
    interface LogReader {
        void read() throws IOException;
    }

    private StartMilestoneAwaiter() { }

    static void await(ManagedProcess process, IbcLogStateParser parser, LogReader logReader,
            Duration timeout) throws IOException, InterruptedException {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(logReader, "logReader");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("start milestone timeout must be positive");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            logReader.read();
            if (parser.launchCommandObserved()) return;
            if (!process.isAlive()) {
                OptionalInt exitCode = process.exitCode();
                throw new IOException("StartIBC exited before reporting its IBC launch command"
                        + (exitCode.isPresent() ? " (exit code " + exitCode.getAsInt() + ")" : ""));
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IOException("Timed out waiting for StartIBC to reach its serialized launch milestone; "
                        + "the partially started wrapper must be terminated before releasing the shared "
                        + "offline-installation lock");
            }
            Thread.sleep(Math.min(50L, Math.max(1L, remaining / 1_000_000L)));
        }
    }
}
