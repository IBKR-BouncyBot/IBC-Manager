package io.github.ibcmanager.runtime;

import io.github.ibcmanager.model.RuntimeState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Tracks an unsuccessful startup episode across StartIBC child replacements.
 * A new JVM without login/API progress is another attempt, not a new deadline.
 *
 * <p>This detector is observational and never starts or terminates a process.
 * Current-child checks and the controller's generation/ownership checks still
 * prevent destructive recovery against a replacement or progressing session.</p>
 */
final class StartupStallDetector {
    static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final Clock clock;
    private final Duration timeout;
    private long observedGeneration = Long.MIN_VALUE;
    private Instant applicationLaunchObservedAt;
    private int launchAttempts;
    private boolean currentLaunchCounted;
    private boolean currentChildProgress;

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
            currentLaunchCounted = false;
            currentChildProgress = false;
            // Do NOT clear the episode deadline here: StartIBC can relaunch a
            // failed child every 60 seconds, indefinitely avoiding a 5-minute
            // per-child timeout. Only genuine progress or an explicit reset
            // ends the accumulated no-progress episode.
        }
        RuntimeState latest = parser.latest().map(IbcLogStateParser.StateHint::state).orElse(null);
        if (parser.loginCompleted() || (parser.applicationLaunchObserved()
                && (latest == RuntimeState.WAITING_FOR_LOGIN
                        || latest == RuntimeState.WAITING_FOR_SECOND_FACTOR))) {
            recordProgress();
        }
        if (!currentChildProgress && parser.applicationLaunchObserved() && !currentLaunchCounted) {
            currentLaunchCounted = true;
            if (applicationLaunchObservedAt == null) applicationLaunchObservedAt = clock.instant();
            if (launchAttempts < Integer.MAX_VALUE) launchAttempts++;
        }
    }

    synchronized boolean isStalled(IbcLogStateParser parser) {
        Objects.requireNonNull(parser, "parser");
        observe(parser);
        if (applicationLaunchObservedAt == null || currentChildProgress || parser.loginCompleted()
                || parser.childExitObserved() || parser.restartPending()
                || parser.normalExitConfirmed() || parser.errorExitConfirmed()) {
            return false;
        }
        RuntimeState latest = parser.latest().map(IbcLogStateParser.StateHint::state).orElse(null);
        if (latest != RuntimeState.STARTING) return false;
        return deadlineExceeded();
    }

    synchronized boolean deadlineExceeded() {
        return applicationLaunchObservedAt != null && elapsed().compareTo(timeout) >= 0;
    }

    /** Genuine login/2FA progress, or a verified owned API listener; never mere port occupancy. */
    synchronized void recordProgress() {
        applicationLaunchObservedAt = null;
        launchAttempts = 0;
        currentChildProgress = true;
    }

    synchronized int launchAttempts() { return launchAttempts; }

    synchronized Duration elapsed() {
        if (applicationLaunchObservedAt == null) return Duration.ZERO;
        Duration elapsed = Duration.between(applicationLaunchObservedAt, clock.instant());
        return elapsed.isNegative() ? Duration.ZERO : elapsed;
    }

    synchronized void reset() {
        observedGeneration = Long.MIN_VALUE;
        applicationLaunchObservedAt = null;
        launchAttempts = 0;
        currentLaunchCounted = false;
        currentChildProgress = false;
    }
}
