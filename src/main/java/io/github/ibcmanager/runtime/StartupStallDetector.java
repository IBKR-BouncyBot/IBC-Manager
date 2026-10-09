package io.github.ibcmanager.runtime;

import io.github.ibcmanager.model.RuntimeState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Tracks one StartIBC child generation from the point where IBC reports that it
 * is starting TWS or Gateway until a later lifecycle milestone appears.
 *
 * <p>This detector is observational: it classifies one child generation but
 * never terminates or starts processes itself. The runtime controller may use
 * the resulting stalled classification to initiate the separately bounded,
 * exact-process-tree automatic-recovery policy.</p>
 */
final class StartupStallDetector {
    static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final Clock clock;
    private final Duration timeout;
    private long observedGeneration = Long.MIN_VALUE;
    private Instant applicationLaunchObservedAt;

    StartupStallDetector(Clock clock) {
        this(clock, DEFAULT_TIMEOUT);
    }

    StartupStallDetector(Clock clock, Duration timeout) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Startup-stall timeout must be positive");
        }
    }

    synchronized void observe(IbcLogStateParser parser) {
        Objects.requireNonNull(parser, "parser");
        long generation = parser.sessionGeneration();
        if (generation != observedGeneration) {
            observedGeneration = generation;
            applicationLaunchObservedAt = null;
        }
        if (parser.applicationLaunchObserved() && applicationLaunchObservedAt == null) {
            applicationLaunchObservedAt = clock.instant();
        }
    }

    synchronized boolean isStalled(IbcLogStateParser parser) {
        Objects.requireNonNull(parser, "parser");
        observe(parser);
        if (applicationLaunchObservedAt == null || parser.loginCompleted()
                || parser.childExitObserved() || parser.restartPending()
                || parser.normalExitConfirmed() || parser.errorExitConfirmed()) {
            return false;
        }
        RuntimeState latest = parser.latest().map(IbcLogStateParser.StateHint::state).orElse(null);
        if (latest != RuntimeState.STARTING) return false;
        return elapsed().compareTo(timeout) >= 0;
    }

    synchronized Duration elapsed() {
        if (applicationLaunchObservedAt == null) return Duration.ZERO;
        Duration elapsed = Duration.between(applicationLaunchObservedAt, clock.instant());
        return elapsed.isNegative() ? Duration.ZERO : elapsed;
    }

    synchronized void reset() {
        observedGeneration = Long.MIN_VALUE;
        applicationLaunchObservedAt = null;
    }
}
