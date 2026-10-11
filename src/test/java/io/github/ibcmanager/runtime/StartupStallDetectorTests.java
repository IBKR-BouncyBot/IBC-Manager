package io.github.ibcmanager.runtime;

import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

public final class StartupStallDetectorTests implements TestSuite {
    @Override public String name() { return "Restart-wide no-progress deadline and incident replay"; }
    @Override public List<NamedTest> tests() {
        return List.of(
                new NamedTest("single startup stalls at exactly five minutes", this::boundary),
                new NamedTest("successive one-minute failures do not reset the overall deadline", this::replacements),
                new NamedTest("duplicate launch lines do not create attempts or extend the deadline", this::duplicates),
                new NamedTest("wrapper bootstrap without a Gateway milestone does not arm", this::beforeLaunch),
                new NamedTest("login window ends the no-progress episode", () -> progress("Login dialog WINDOW_OPENED")),
                new NamedTest("real second factor protects indefinite approval wait", () -> progress("Second Factor Authentication initiated")),
                new NamedTest("completed login ends the no-progress episode", () -> progress("Login has completed")),
                new NamedTest("owned API progress ends the episode even without login text", this::apiProgress),
                new NamedTest("normal exit never initiates stalled recovery", this::normalExit),
                new NamedTest("child-exit and restart gaps preserve age without authorizing a kill", this::restartGap),
                new NamedTest("explicit stop or new wrapper resets the episode", this::explicitReset),
                new NamedTest("profile detectors have independent episodes", this::isolation),
                new NamedTest("invalid detector dependencies and timeouts are rejected", this::invalid),
                new NamedTest("backwards wall-clock age is not negative", this::clockRollback),
                new NamedTest("initial logged-out bootstrap state cannot erase repeated failure age", this::bootstrapLoggedOut),
                new NamedTest("same-child incidental starting text cannot erase prior login progress", this::lateStarting),
                new NamedTest("October 10 nine-failure incident crosses the cumulative deadline", this::october10),
                new NamedTest("October 11 successful warm restart does not classify as stalled", this::october11));
    }

    private void boundary() {
        Fixture f = new Fixture(); f.child(); f.advance(299);
        Assertions.isFalse(f.stalled(), "not early"); f.advance(1);
        Assertions.isTrue(f.stalled(), "inclusive five-minute boundary");
        Assertions.equals(1, f.detector.launchAttempts(), "one attempt");
    }
    private void replacements() {
        Fixture f = new Fixture();
        for (int i = 0; i < 5; i++) { f.child(); f.advance(60); f.fail(); f.advance(2); }
        f.child();
        Assertions.isTrue(f.stalled(), "310 seconds unsuccessful even though current child is new");
        Assertions.equals(6, f.detector.launchAttempts(), "all failed children belong to the episode");
        Assertions.equals(Duration.ofSeconds(310), f.detector.elapsed(), "age is never restarted by a new JVM");
    }
    private void duplicates() {
        Fixture f = new Fixture(); f.child(); f.advance(280);
        for (int i = 0; i < 100; i++) f.line("IBC: Starting Gateway");
        f.advance(20);
        Assertions.isTrue(f.stalled(), "duplicate milestone cannot postpone the deadline");
        Assertions.equals(1, f.detector.launchAttempts(), "one counted launch");
    }
    private void beforeLaunch() {
        Fixture f = new Fixture(); f.line("Starting IBC with this command:"); f.advance(3600);
        Assertions.isFalse(f.stalled(), "no invented Gateway launch");
        f.line("IBC: Starting Gateway");
        Assertions.equals(Duration.ZERO, f.detector.elapsed(), "count from the actual milestone");
    }
    private void progress(String milestone) {
        Fixture f = new Fixture(); f.child(); f.advance(299); f.line(milestone); f.advance(36000);
        Assertions.isFalse(f.stalled(), "never kill manual login or approval wait");
        Assertions.equals(Duration.ZERO, f.detector.elapsed(), "progress ends old episode");
        f.child(); f.advance(299); Assertions.isFalse(f.stalled(), "future scheduled restart has a new budget");
        f.advance(1); Assertions.isTrue(f.stalled(), "but future silent restarts are still supervised");
    }
    private void apiProgress() {
        Fixture f = new Fixture(); f.child(); f.advance(400); f.detector.recordProgress();
        f.advance(600); Assertions.isFalse(f.stalled(), "loss of port visibility later cannot revive old startup time");
        f.child(); Assertions.equals(1, f.detector.launchAttempts(), "later incident starts from attempt one");
        Assertions.isFalse(f.stalled(), "new budget");
    }
    private void normalExit() {
        Fixture f = new Fixture(); f.child(); f.advance(360); f.line("Normal exit");
        Assertions.isFalse(f.stalled(), "legitimate exit is not stalled startup");
    }
    private void restartGap() {
        Fixture f = new Fixture(); f.child(); f.advance(300); f.fail();
        Assertions.isFalse(f.stalled(), "must not kill between child processes");
        f.advance(20); f.child();
        Assertions.isTrue(f.stalled(), "new active attempt retains failed-episode age");
    }
    private void explicitReset() {
        Fixture f = new Fixture(); f.child(); f.advance(300); f.detector.reset(); f.parser.reset(); f.child();
        Assertions.isFalse(f.stalled(), "operator starts a new episode");
        Assertions.equals(1, f.detector.launchAttempts(), "counts reset with wrapper");
    }
    private void isolation() {
        Fixture a = new Fixture(); Fixture b = new Fixture(); a.child(); a.advance(360); b.child();
        Assertions.isTrue(a.stalled(), "A stale"); Assertions.isFalse(b.stalled(), "B independent");
    }
    private void invalid() {
        Clock c = Clock.systemUTC();
        Assertions.throwsType(NullPointerException.class, () -> new StartupStallDetector(null), "clock required");
        Assertions.throwsType(IllegalArgumentException.class, () -> new StartupStallDetector(c, Duration.ZERO), "positive timeout");
        Assertions.throwsType(IllegalArgumentException.class, () -> new StartupStallDetector(c, Duration.ofSeconds(-1)), "positive timeout");
        Assertions.throwsType(NullPointerException.class, () -> new StartupStallDetector(c).observe(null), "parser required");
    }
    private void clockRollback() {
        Fixture f = new Fixture(); f.child(); f.advance(-10);
        Assertions.equals(Duration.ZERO, f.detector.elapsed(), "negative age is clamped");
        Assertions.isFalse(f.stalled(), "no false early expiration");
    }
    private void bootstrapLoggedOut() {
        Fixture f = new Fixture();
        for (int i = 1; i <= 6; i++) {
            f.line("Starting IBC with this command:");
            String id = new UUID(0, i).toString();
            f.line("IBC_MANAGER_EVENT|1|" + id + "|1|ENGINE_STARTED");
            f.line("IBC_MANAGER_EVENT|1|" + id + "|2|LOGIN_LOGGED_OUT");
            f.line("IBC_MANAGER_EVENT|1|" + id + "|3|GATEWAY_STARTING");
            if (i < 6) { f.advance(60); f.fail(); f.advance(2); }
        }
        Assertions.isTrue(f.stalled(), "bootstrap logged-out event is not a displayed login form");
    }
    private void lateStarting() {
        Fixture f = new Fixture(); f.child(); f.line("Login dialog WINDOW_OPENED");
        f.line("IBC: Starting Gateway"); f.advance(400);
        Assertions.isFalse(f.stalled(), "same-child progress is sticky");
    }
    private void october10() throws Exception {
        Replay r = replay("oct10-failed-starts.tsv");
        Assertions.equals(10, r.launches, "actual incident child count");
        Assertions.equals(9, r.timeouts, "actual 1112 count");
        Assertions.isTrue(r.firstStallAge >= 300000 && r.firstStallAge <= 301000,
                "actual retry loop crosses the deadline rather than resetting it");
        Assertions.isFalse(r.finalStalled, "eventual real login cancels no-progress classification");
    }
    private void october11() throws Exception {
        Replay r = replay("oct11-warm-success.tsv");
        Assertions.equals(1, r.launches, "actual one-child successful warm restart");
        Assertions.equals(0, r.timeouts, "no failed retries");
        Assertions.equals(-1L, r.firstStallAge, "successful warm restart is unaffected");
        Assertions.isFalse(r.finalStalled, "success stays successful");
    }
    private static Replay replay(String filename) throws Exception {
        Fixture f = new Fixture(); int launches = 0; int timeouts = 0; long first = -1;
        long last = 0;
        for (String raw : Files.readAllLines(Path.of("src/test/resources/restarts", filename))) {
            if (raw.startsWith("#") || raw.isBlank()) continue;
            String[] event = raw.split("\t", 2); long at = Long.parseLong(event[0]);
            while (at - last > 1000) {
                last += 1000; f.clock.at = Fixture.BASE.plusMillis(last);
                if (f.stalled() && first < 0) first = f.detector.elapsed().toMillis();
            }
            last = at; f.clock.at = Fixture.BASE.plusMillis(at); f.line(event[1]);
            if (event[1].contains("IBC: Starting Gateway")) launches++;
            if (event[1].contains("Exiting after error with exit code=1112")) timeouts++;
            if (f.stalled() && first < 0) first = f.detector.elapsed().toMillis();
        }
        return new Replay(launches, timeouts, first, f.stalled());
    }
    private record Replay(int launches, int timeouts, long firstStallAge, boolean finalStalled) { }
    private static final class Fixture {
        static final Instant BASE = Instant.parse("2026-10-10T18:00:00Z");
        final MutableClock clock = new MutableClock();
        final IbcLogStateParser parser = new IbcLogStateParser();
        final StartupStallDetector detector = new StartupStallDetector(clock);
        void line(String text) { parser.accept(text); detector.observe(parser); }
        void child() { line("Starting IBC with this command:"); line("IBC: Starting Gateway"); }
        void fail() {
            line("IBC: Exiting after error with exit code=1112"); line("Program has exited");
            line("IBC will restart shortly due to login dialog display timeout");
        }
        void advance(long seconds) { clock.at = clock.at.plusSeconds(seconds); }
        boolean stalled() { return detector.isStalled(parser); }
    }
    private static final class MutableClock extends Clock {
        private Instant at = Fixture.BASE;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return at; }
    }
}
