package io.github.ibcmanager.runtime;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RecoveryHistoryStoreTests implements TestSuite {
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    @Override public String name() { return "Automatic recovery persistence and rate limiting"; }

    @Override
    public List<NamedTest> tests() {
        return List.of(
                new NamedTest("records a pending attempt and reloads it", this::pendingRoundTrip),
                new NamedTest("blocks a second attempt until health is confirmed", this::pendingBlocks),
                new NamedTest("keeps successful attempts for the rolling rate limit", this::healthyRateLimit),
                new NamedTest("manual reset clears only the pending marker", this::manualReset),
                new NamedTest("explicit intervention can clear all recovery bookkeeping", this::explicitClear),
                new NamedTest("prunes attempts older than one hour", this::prunesOldAttempts),
                new NamedTest("rejects corrupt and future recovery state", this::rejectsInvalidState),
                new NamedTest("refuses a symbolic recovery-history file", this::rejectsSymbolicState));
    }

    private void pendingRoundTrip() throws Exception {
        try (Fixture fixture = new Fixture()) {
            RecoveryHistoryStore.BeginResult result = fixture.store.beginAttempt(fixture.profileId, NOW, 2);
            Assertions.equals(RecoveryHistoryStore.BeginDisposition.STARTED, result.disposition(),
                    "the first recovery attempt must start");
            Assertions.equals(1, result.attemptsInWindow(), "the first attempt must be counted");
            RecoveryHistoryStore.State state = new RecoveryHistoryStore(fixture.paths)
                    .snapshot(fixture.profileId, NOW);
            Assertions.isTrue(state.awaitingHealthy(), "the attempt must remain pending across store instances");
            Assertions.equals(List.of(NOW), state.attempts(), "the exact attempt time must be retained");
            Assertions.fileExists(fixture.paths.recoveryHistory(fixture.profileId),
                    "the recovery state must be persisted under the profile runtime directory");
        }
    }

    private void pendingBlocks() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.store.beginAttempt(fixture.profileId, NOW, 2);
            RecoveryHistoryStore.BeginResult blocked = fixture.store.beginAttempt(
                    fixture.profileId, NOW.plusSeconds(30), 2);
            Assertions.equals(RecoveryHistoryStore.BeginDisposition.PREVIOUS_ATTEMPT_PENDING,
                    blocked.disposition(), "one unresolved attempt must block another destructive recovery");
            Assertions.equals(1, blocked.attemptsInWindow(),
                    "the blocked request must not add an attempt");
        }
    }

    private void healthyRateLimit() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.store.beginAttempt(fixture.profileId, NOW, 2);
            fixture.store.markHealthy(fixture.profileId, NOW.plusSeconds(10));
            RecoveryHistoryStore.BeginResult second = fixture.store.beginAttempt(
                    fixture.profileId, NOW.plus(Duration.ofMinutes(10)), 2);
            Assertions.equals(RecoveryHistoryStore.BeginDisposition.STARTED, second.disposition(),
                    "health confirmation must permit the second attempt");
            fixture.store.markHealthy(fixture.profileId, NOW.plus(Duration.ofMinutes(10)).plusSeconds(10));
            RecoveryHistoryStore.BeginResult third = fixture.store.beginAttempt(
                    fixture.profileId, NOW.plus(Duration.ofMinutes(20)), 2);
            Assertions.equals(RecoveryHistoryStore.BeginDisposition.RATE_LIMITED, third.disposition(),
                    "the third attempt inside one hour must be blocked");
            Assertions.equals(2, third.attemptsInWindow(),
                    "the rate limit must report the two retained attempts");
        }
    }

    private void manualReset() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.store.beginAttempt(fixture.profileId, NOW, 2);
            fixture.store.resetPendingAttempt(fixture.profileId, NOW.plusSeconds(1));
            RecoveryHistoryStore.State state = fixture.store.snapshot(fixture.profileId, NOW.plusSeconds(1));
            Assertions.isFalse(state.awaitingHealthy(), "manual intervention must clear the pending marker");
            Assertions.equals(1, state.attempts().size(),
                    "manual intervention must not erase the rolling attempt history");
        }
    }

    private void explicitClear() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.store.beginAttempt(fixture.profileId, NOW, 2);
            Path file = fixture.paths.recoveryHistory(fixture.profileId);
            Assertions.fileExists(file, "a pending recovery must have persistent bookkeeping");

            fixture.store.clear(fixture.profileId);

            Assertions.isFalse(Files.exists(file),
                    "explicit intervention must remove the persistent recovery file");
            RecoveryHistoryStore.State state = fixture.store.snapshot(fixture.profileId, NOW.plusSeconds(1));
            Assertions.isFalse(state.awaitingHealthy(),
                    "clearing recovery bookkeeping must remove the pending marker");
            Assertions.equals(List.of(), state.attempts(),
                    "clearing recovery bookkeeping must reset the rolling attempt history");
        }
    }

    private void prunesOldAttempts() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.store.beginAttempt(fixture.profileId, NOW.minus(Duration.ofHours(2)), 2);
            fixture.store.markHealthy(fixture.profileId, NOW.minus(Duration.ofHours(2)).plusSeconds(1));
            RecoveryHistoryStore.BeginResult current = fixture.store.beginAttempt(fixture.profileId, NOW, 2);
            Assertions.equals(RecoveryHistoryStore.BeginDisposition.STARTED, current.disposition(),
                    "an attempt older than the rolling hour must not consume the limit");
            Assertions.equals(1, current.attemptsInWindow(),
                    "the persisted history must be pruned to the current rolling window");
        }
    }

    private void rejectsInvalidState() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Path file = fixture.paths.recoveryHistory(fixture.profileId);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "formatVersion=1\nawaitingHealthy=true\nattemptEpochMillis=not-a-number\n");
            Assertions.throwsType(IOException.class,
                    () -> fixture.store.snapshot(fixture.profileId, NOW),
                    "corrupt recovery state must fail closed");
            Files.writeString(file, "formatVersion=1\nawaitingHealthy=false\nattemptEpochMillis="
                    + NOW.plus(Duration.ofHours(1)).toEpochMilli() + "\n");
            Assertions.throwsType(IOException.class,
                    () -> fixture.store.snapshot(fixture.profileId, NOW),
                    "timestamps too far in the future must fail closed");
        }
    }

    private void rejectsSymbolicState() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Path file = fixture.paths.recoveryHistory(fixture.profileId);
            Files.createDirectories(file.getParent());
            Path target = fixture.root.resolve("outside-state.txt");
            Files.writeString(target, "formatVersion=1\nawaitingHealthy=false\n");
            try {
                Files.createSymbolicLink(file, target);
            } catch (UnsupportedOperationException | IOException | SecurityException ex) {
                Assertions.isTrue(Files.isRegularFile(target),
                        "platform without symbolic-link support must leave the target intact");
                return;
            }
            Assertions.throwsType(IOException.class,
                    () -> fixture.store.snapshot(fixture.profileId, NOW),
                    "recovery history must never follow a symbolic link");
            Assertions.equals("formatVersion=1\nawaitingHealthy=false\n", Files.readString(target),
                    "rejecting a symbolic state file must not modify its target");
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Path root;
        private final AppPaths paths;
        private final UUID profileId = UUID.randomUUID();
        private final RecoveryHistoryStore store;

        private Fixture() throws IOException {
            root = TestSupport.tempDirectory("recovery-history");
            paths = new AppPaths(root.resolve("data"));
            store = new RecoveryHistoryStore(paths);
        }

        @Override
        public void close() throws IOException {
            TestSupport.deleteTree(root);
        }
    }
}
