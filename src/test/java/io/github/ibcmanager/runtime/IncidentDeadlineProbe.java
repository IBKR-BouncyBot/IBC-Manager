package io.github.ibcmanager.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Same API probe can run against either the original 2.0.3 JAR or the repaired Manager. */
public final class IncidentDeadlineProbe {
    private IncidentDeadlineProbe() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("expect-stall|expect-no-stall fixture.tsv required");
        boolean expectStall = switch (args[0]) {
            case "expect-stall" -> true;
            case "expect-no-stall" -> false;
            default -> throw new IllegalArgumentException("Unknown expectation");
        };
        MutableClock clock = new MutableClock();
        IbcLogStateParser parser = new IbcLogStateParser();
        StartupStallDetector detector = new StartupStallDetector(clock);
        long firstStall = -1; int launches = 0; int failures = 0;
        for (String row : Files.readAllLines(Path.of(args[1]))) {
            if (row.isBlank() || row.startsWith("#")) continue;
            String[] fields = row.split("\t", 2); long at = Long.parseLong(fields[0]);
            while (at - clock.millis > 1000) {
                clock.millis += 1000;
                if (detector.isStalled(parser) && firstStall < 0) firstStall = detector.elapsed().toMillis();
            }
            clock.millis = at;
            parser.accept(fields[1]); detector.observe(parser);
            if (fields[1].contains("IBC: Starting Gateway")) launches++;
            if (fields[1].contains("Exiting after error with exit code=1112")) failures++;
            if (detector.isStalled(parser) && firstStall < 0) firstStall = detector.elapsed().toMillis();
        }
        System.out.println("Child launches=" + launches + "; 1112 failures=" + failures);
        System.out.println("First cumulative stall age (milliseconds)=" + firstStall);
        System.out.println("Final state after recognized login=" + parser.latest().orElseThrow().state());
        if (launches != 10 || failures != 9 || (firstStall >= 0) != expectStall) throw new AssertionError("Incident expectation failed");
        System.out.println("INCIDENT COMPARISON PASSED (expected stall=" + expectStall + ")");
    }
    private static final class MutableClock extends Clock {
        private long millis;
        @Override public Instant instant() { return Instant.parse("2026-10-10T18:00:00Z").plusMillis(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
