package ibcalpha.ibc;

import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Virtual monotonic time tests execute the production deadline state machine. */
public final class SecondFactorRetryTests implements TestSuite {
    @Override public String name() { return "Engine independent second-factor retry deadline"; }
    @Override public List<NamedTest> tests() {
        return List.of(
                new NamedTest("open challenge triggers one retry at 300 seconds without a close event", this::openChallenge),
                new NamedTest("duplicate start does not postpone the existing deadline", this::duplicate),
                new NamedTest("early natural dialog closure does not bypass the deadline", this::earlyClose),
                new NamedTest("completed authentication cancels the due action", this::completed),
                new NamedTest("cancellation invalidates an already queued GUI callback", this::queued),
                new NamedTest("retired challenge callbacks cannot retry a replacement challenge", this::replacement),
                new NamedTest("missing credentials leave the pending request untouched", this::manual),
                new NamedTest("unknown cancellation control is reported without guessing or killing", this::unknownCancel),
                new NamedTest("unresponsive Cancel is bounded and never clicked repeatedly", this::cancelStuck),
                new NamedTest("authentication success during cancellation prevents login submission", this::successAfterCancel),
                new NamedTest("disabled policy at deadline prevents any action", this::disabled),
                new NamedTest("repeated challenges each receive a complete new five-minute interval", this::repeated),
                new NamedTest("an early scheduler callback cannot cause an early retry", this::earlyScheduler),
                new NamedTest("monotonic deadline works across signed nanoTime wrap", this::clockWrap),
                new NamedTest("UI exception is contained and reported only once", this::uiFailure),
                new NamedTest("stop or failed login during form readiness wait cancels retry", this::stopDuringWait),
                new NamedTest("invalid timeout values fail before any timer is scheduled", this::invalid));
    }

    private void openChallenge() {
        Fixture f = new Fixture(); f.start();
        f.clock.advance(299_999);
        Assertions.equals(0, f.target.cancels, "no early Cancel");
        f.clock.advance(1);
        Assertions.equals(1, f.target.cancels, "deadline cancels the still-open prompt");
        Assertions.equals(0, f.target.retries, "GUI transition gets a turn");
        f.clock.advance(100);
        Assertions.equals(1, f.target.retries, "one native relogin");
        f.clock.advance(900_000);
        Assertions.equals(1, f.target.cancels, "no Cancel loop");
        Assertions.equals(1, f.target.retries, "no submission loop");
        Assertions.equals(List.of("SECOND_FACTOR_RETRY_ARMED", "SECOND_FACTOR_RETRY_DUE",
                "SECOND_FACTOR_RETRY_STARTED"), f.target.events, "phases");
    }
    private void duplicate() {
        Fixture f = new Fixture(); f.start(); f.clock.advance(200_000); f.start();
        f.clock.advance(100_100);
        Assertions.equals(1, f.target.retries, "duplicate must not restart the clock");
    }
    private void earlyClose() {
        Fixture f = new Fixture(); f.start(); f.clock.advance(60_000); f.target.showing = false;
        f.clock.advance(239_999);
        Assertions.equals(0, f.target.retries, "closure does not cause immediate retry");
        f.clock.advance(101);
        Assertions.equals(0, f.target.cancels, "closed challenge needs no Cancel");
        Assertions.equals(1, f.target.retries, "retry at original deadline");
    }
    private void completed() {
        Fixture f = new Fixture(); f.start(); f.clock.advance(299_999); f.target.pending = false;
        f.clock.advance(1_000);
        Assertions.equals(0, f.target.cancels, "logged-in session untouched");
        Assertions.equals(0, f.target.retries, "no reauthentication");
    }
    private void queued() {
        List<Runnable> gui = new ArrayList<>(); Fixture f = new Fixture(gui::add);
        f.start(); f.clock.advance(300_000); f.retry.close();
        gui.forEach(Runnable::run);
        Assertions.equals(0, f.target.cancels, "already-queued callback invalidated");
    }
    private void replacement() {
        List<Runnable> gui = new ArrayList<>(); Fixture f = new Fixture(gui::add);
        f.start(); f.clock.advance(300_000); f.retry.close();
        var second = new SecondFactorRetry(f.clock, gui::add, () -> f.clock.now, f.target, 300);
        second.start(); gui.forEach(Runnable::run); gui.clear();
        Assertions.equals(0, f.target.cancels, "retired deadline cannot act");
        f.clock.advance(299_999);
        Assertions.isTrue(gui.isEmpty(), "replacement gets its own interval");
        second.close();
    }
    private void manual() {
        Fixture f = new Fixture(); f.target.credentials = false; f.start(); f.clock.advance(600_000);
        Assertions.equals(0, f.target.cancels, "manual approval request is left intact");
        Assertions.equals(0, f.target.retries, "no blank password submitted");
        Assertions.equals("SECOND_FACTOR_RETRY_BLOCKED", f.target.events.get(1), "visible block");
    }
    private void unknownCancel() {
        Fixture f = new Fixture(); f.target.cancelSupported = false; f.start(); f.clock.advance(600_000);
        Assertions.equals(1, f.target.cancels, "one checked cancel attempt");
        Assertions.equals(0, f.target.retries, "no guessing");
        Assertions.equals(1L, f.target.events.stream().filter(s -> s.endsWith("BLOCKED")).count(), "one error");
    }
    private void cancelStuck() {
        Fixture f = new Fixture(); f.target.cancelCloses = false; f.start(); f.clock.advance(311_000);
        Assertions.equals(1, f.target.cancels, "no repeated click");
        Assertions.equals(0, f.target.retries, "no login through active dialog");
        Assertions.equals("SECOND_FACTOR_RETRY_BLOCKED", f.target.events.get(2), "readiness bounded");
        Assertions.equals(0L, f.clock.jobs.stream().filter(j -> !j.cancelled).count(), "no ongoing poll");
    }
    private void successAfterCancel() {
        Fixture f = new Fixture(); f.start(); f.clock.advance(300_000); f.target.pending = false;
        f.clock.advance(1_000);
        Assertions.equals(0, f.target.retries, "approval won race with form readiness");
    }
    private void disabled() {
        Fixture f = new Fixture(); f.start(); f.target.pending = false; f.clock.advance(600_000);
        Assertions.equals(List.of("SECOND_FACTOR_RETRY_ARMED"), f.target.events, "policy changed: quiet cancellation");
    }
    private void repeated() {
        Fixture f = new Fixture(); f.start(); f.clock.advance(300_100);
        f.target.showing = true;
        var second = new SecondFactorRetry(f.clock, Runnable::run, () -> f.clock.now, f.target, 300);
        second.start(); f.clock.advance(299_999);
        Assertions.equals(1, f.target.retries, "new request not prematurely cancelled");
        f.clock.advance(101);
        Assertions.equals(2, f.target.retries, "second request independently retried");
    }
    private void earlyScheduler() {
        Fixture f = new Fixture(); f.start(); f.clock.jobs.get(0).task.run();
        Assertions.equals(0, f.target.cancels, "early callback respects nanoTime");
        f.clock.advance(300_100);
        Assertions.equals(1, f.target.retries, "rescheduled remaining delay");
    }
    private void clockWrap() {
        Fixture f = new Fixture(); f.clock.now = Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(150);
        f.start(); f.clock.advance(300_100);
        Assertions.equals(1, f.target.retries, "elapsed subtraction survives wrap");
    }
    private void uiFailure() {
        Fixture f = new Fixture(); f.target.throwOnCancel = true; f.start(); f.clock.advance(600_000);
        Assertions.equals(0, f.target.retries, "exception cannot lead to submission");
        Assertions.equals(1L, f.target.events.stream().filter(s -> s.endsWith("BLOCKED")).count(), "one safe failure");
        Assertions.isTrue(f.target.events.stream().noneMatch(s -> s.contains("sensitive")), "no exception text emitted");
    }
    private void stopDuringWait() {
        Fixture f = new Fixture(); f.target.ready = false; f.start(); f.clock.advance(300_050);
        f.target.pending = false; f.clock.advance(30_000);
        Assertions.equals(0, f.target.retries, "explicit stop/failed authentication cancels retries");
    }
    private void invalid() {
        Fixture f = new Fixture();
        for (int timeout : new int[] {-1, 0, 3601, Integer.MAX_VALUE}) {
            Assertions.throwsType(IllegalArgumentException.class,
                    () -> new SecondFactorRetry(f.clock, Runnable::run, () -> 0L, f.target, timeout), "invalid bound");
        }
        Assertions.isTrue(f.clock.jobs.isEmpty(), "validation has no timers");
    }

    private static final class Fixture {
        final Clock clock = new Clock(); final Target target = new Target(); final SecondFactorRetry retry;
        Fixture() { this(Runnable::run); }
        Fixture(Executor gui) { retry = new SecondFactorRetry(clock, gui, () -> clock.now, target, 300); }
        void start() { retry.start(); }
    }
    private static final class Target implements SecondFactorRetry.Target {
        boolean pending = true, credentials = true, showing = true, ready = true;
        boolean cancelSupported = true, cancelCloses = true, throwOnCancel;
        int cancels, retries;
        final List<String> events = new ArrayList<>();
        @Override public boolean isPending() { return pending; }
        @Override public boolean hasCredentials() { return credentials; }
        @Override public boolean isChallengeShowing() { return showing; }
        @Override public boolean cancelChallenge() {
            cancels++;
            if (throwOnCancel) throw new IllegalStateException("sensitive exception text");
            if (cancelSupported && cancelCloses) showing = false;
            return cancelSupported;
        }
        @Override public boolean isLoginReady() { return ready; }
        @Override public boolean retryLogin() { retries++; return true; }
        @Override public void event(String value) { events.add(value); }
    }
    private static final class Job {
        final long at; final Runnable task; boolean cancelled;
        Job(long at, Runnable task) { this.at = at; this.task = task; }
    }
    private static final class Clock implements SecondFactorRetry.Scheduler {
        long now; final List<Job> jobs = new ArrayList<>();
        @Override public SecondFactorRetry.Cancellation schedule(Runnable task, long delay) {
            Job job = new Job(now + delay, task); jobs.add(job); return () -> job.cancelled = true;
        }
        void advance(long milliseconds) {
            long end = now + TimeUnit.MILLISECONDS.toNanos(milliseconds);
            int guard = 0;
            while (true) {
                Job next = jobs.stream().filter(j -> !j.cancelled && j.at - now <= end - now)
                        .min(Comparator.comparingLong(j -> j.at - now)).orElse(null);
                if (next == null) break;
                if (++guard > 10000) throw new AssertionError("unbounded timer loop");
                jobs.remove(next); now = next.at; next.task.run();
            }
            now = end;
        }
    }
}
