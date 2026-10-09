// IBC Manager maintained engine; GPL-3.0-or-later.
package ibcalpha.ibc;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * One cancellable, monotonic deadline per observed 2FA challenge. UI work is always
 * dispatched to the GUI executor. A still-open dialog does not suppress the deadline.
 * No process termination, credentials, or wall-clock arithmetic lives in this class.
 */
final class SecondFactorRetry implements AutoCloseable {
    interface Cancellation { void cancel(); }
    interface Scheduler { Cancellation schedule(Runnable task, long delayNanos); }
    interface Target {
        boolean isPending();
        boolean hasCredentials();
        boolean isChallengeShowing();
        boolean cancelChallenge();
        default void prepareLogin() { }
        boolean isLoginReady();
        boolean retryLogin();
        void event(String event);
    }

    private static final long READY_WAIT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final ScheduledThreadPoolExecutor TIMER = newTimer();
    private final Scheduler scheduler;
    private final Executor gui;
    private final LongSupplier nanoTime;
    private final Target target;
    private final long delayNanos;
    private long startedAt;
    private long readyWaitStartedAt;
    private long ticket;
    private boolean started;
    private volatile boolean closed;
    private Cancellation pending;

    SecondFactorRetry(Target target, int timeoutSeconds) {
        this((task, delay) -> {
            var future = TIMER.schedule(task, delay, TimeUnit.NANOSECONDS);
            return () -> future.cancel(false);
        }, GuiDeferredExecutor.instance(), System::nanoTime, target, timeoutSeconds);
    }

    SecondFactorRetry(Scheduler scheduler, Executor gui, LongSupplier nanoTime,
            Target target, int timeoutSeconds) {
        this.scheduler = Objects.requireNonNull(scheduler);
        this.gui = Objects.requireNonNull(gui);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.target = Objects.requireNonNull(target);
        if (timeoutSeconds < 1 || timeoutSeconds > 3600) {
            throw new IllegalArgumentException("2FA retry timeout must be between 1 and 3600 seconds");
        }
        delayNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds);
    }

    synchronized void start() {
        if (started || closed) return;
        started = true;
        startedAt = nanoTime.getAsLong();
        target.event("SECOND_FACTOR_RETRY_ARMED");
        schedule(this::deadline, delayNanos);
    }

    private void deadline() {
        if (!allowed()) return;
        long remaining = delayNanos - (nanoTime.getAsLong() - startedAt);
        if (remaining > 0) {
            schedule(this::deadline, remaining);
            return;
        }
        if (!target.hasCredentials()) {
            blocked();
            return;
        }
        target.event("SECOND_FACTOR_RETRY_DUE");
        // Recheck after event publication and immediately before a UI action.
        if (!allowed()) return;
        if (target.isChallengeShowing() && !target.cancelChallenge()) {
            blocked();
            return;
        }
        readyWaitStartedAt = nanoTime.getAsLong();
        // Give Cancel and the application's resulting window events a GUI turn.
        schedule(this::awaitLogin, POLL_NANOS);
    }

    private void awaitLogin() {
        if (!allowed()) return;
        if (!target.isChallengeShowing()) {
            target.prepareLogin();
            if (!allowed()) return;
        }
        if (!target.isChallengeShowing() && target.isLoginReady()) {
            if (!allowed() || !target.hasCredentials()) {
                close();
                return;
            }
            close(); // Claim this attempt before invoking the reentrant login handler.
            if (target.retryLogin()) target.event("SECOND_FACTOR_RETRY_STARTED");
            else target.event("SECOND_FACTOR_RETRY_BLOCKED");
            return;
        }
        if (nanoTime.getAsLong() - readyWaitStartedAt >= READY_WAIT_NANOS) {
            blocked();
            return;
        }
        schedule(this::awaitLogin, POLL_NANOS);
    }

    private boolean allowed() {
        if (closed) return false;
        if (!target.isPending()) {
            close();
            return false;
        }
        return true;
    }

    private void blocked() {
        close();
        target.event("SECOND_FACTOR_RETRY_BLOCKED");
    }

    private synchronized void schedule(Runnable step, long delay) {
        if (closed) return;
        if (pending != null) pending.cancel();
        long expected = ++ticket;
        pending = scheduler.schedule(() -> gui.execute(() -> {
            synchronized (SecondFactorRetry.this) {
                if (closed || expected != ticket) return;
            }
            try {
                step.run();
            } catch (RuntimeException failure) {
                // Do not leak arbitrary exception text or let a UI mismatch crash Gateway.
                blocked();
            }
        }), Math.max(0, delay));
    }

    @Override
    public synchronized void close() {
        closed = true;
        ticket++;
        if (pending != null) pending.cancel();
        pending = null;
    }

    private static ScheduledThreadPoolExecutor newTimer() {
        var timer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "ibc-second-factor-deadline");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return timer;
    }
}
