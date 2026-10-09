package io.github.ibcmanager.runtime;

import java.time.Duration;
import java.util.Objects;

@FunctionalInterface
public interface RecoverySleeper {
    void sleep(Duration duration) throws InterruptedException;

    static RecoverySleeper system() {
        return duration -> {
            Objects.requireNonNull(duration, "duration");
            if (duration.isNegative()) throw new IllegalArgumentException("Sleep duration must not be negative");
            long seconds = duration.getSeconds();
            int nanos = duration.getNano();
            long millis = Math.addExact(Math.multiplyExact(seconds, 1_000L), nanos / 1_000_000L);
            int remainingNanos = nanos % 1_000_000;
            Thread.sleep(millis, remainingNanos);
        };
    }
}
