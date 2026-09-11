package io.github.ibcmanager.runtime;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.config.RuntimeConfigLease;
import io.github.ibcmanager.config.RuntimeConfigProvider;
import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.PortListenerState;
import io.github.ibcmanager.model.RuntimeState;
import io.github.ibcmanager.security.CredentialStore;
import io.github.ibcmanager.security.CredentialStoreException;
import io.github.ibcmanager.security.SecureChars;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;
import io.github.ibcmanager.validation.ProfileValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public final class ControllerTests implements TestSuite {
    @Override public String name() { return "Profile runtime controller and registry"; }

    @Override
    public List<NamedTest> tests() {
        return List.of(
                new NamedTest("controller starts in stopped state", this::initialState),
                new NamedTest("validation failure prevents all launch work", this::validationFailure),
                new NamedTest("occupied command port prevents launch", this::occupiedCommandPort),
                new NamedTest("occupied API port prevents launch", this::occupiedApiPort),
                new NamedTest("unavailable listener inspection prevents launch", this::unavailablePortInspection),
                new NamedTest("profile and status snapshots remain readable during a blocking start",
                        this::nonBlockingSnapshots),
                new NamedTest("manual profile starts once and records identity", this::startManual),
                new NamedTest("encrypted start loads and clears stored credential", this::startEncrypted),
                new NamedTest("existing-config start does not query credential store", this::startExisting),
                new NamedTest("credential load failure stops before runtime config creation", this::credentialFailure),
                new NamedTest("runtime config creation failure prevents process launch", this::runtimeConfigFailure),
                new NamedTest("process launch failure removes temporary runtime config", this::processLaunchFailure),
                new NamedTest("launch is rejected unless the detached relay owns exact runtime-config cleanup",
                        this::relayCleanupOwnership),
                new NamedTest("identity persistence failure terminates the partially started process", this::identityFailureCleanup),
                new NamedTest("failing status listeners cannot break controller operation", this::listenerIsolation),
                new NamedTest("refresh recognizes login and second-factor states", this::loginStates),
                new NamedTest("refresh retains paused and reports an exited application", this::pausedAndExitedStates),
                new NamedTest("automatic and timeout restarts stay yellow until replacement launch",
                        this::automaticRestartStates),
                new NamedTest("runtime configuration remains available across authentication and wrapper restarts", this::secondFactorScrub),
                new NamedTest("API listener detection is reported with an explicit caveat", this::apiState),
                new NamedTest("API listener must belong to the managed process tree before status becomes green",
                        this::apiOwnership),
                new NamedTest("unavailable API listener inspection is reported as uncertain",
                        this::unknownApiListenerState),
                new NamedTest("login-completed and error log states are reported", this::runningAndErrorStates),
                new NamedTest("command-server readiness is reported while login is pending", this::commandReadyState),
                new NamedTest("periodic refresh uses passive listener inspection without command connections",
                        this::commandServerProbeNoise),
                new NamedTest("controller commands use loopback and update restart and pause states", this::commands),
                new NamedTest("a successful PAUSE exit remains paused instead of becoming an error",
                        this::pauseExit),
                new NamedTest("PAUSE exit without wrapper confirmation becomes an error",
                        this::pauseExitWithoutConfirmation),
                new NamedTest("controller rejects commands while command server is closed", this::commandClosed),
                new NamedTest("replacement IBC launch marker revokes stale command capabilities",
                        this::replacementLaunchRevokesCommands),
                new NamedTest("controller propagates IBC command rejection", this::commandRejected),
                new NamedTest("graceful stop survives an onExit callback race", this::gracefulStopRace),
                new NamedTest("failed STOP command never escalates to an implicit force stop", this::stopCommandFailure),
                new NamedTest("force stop terminates only the managed process", this::forceStop),
                new NamedTest("unexpected process exit cleans state and reports an error", this::unexpectedExit),
                new NamedTest("normal and scheduled wrapper exits finish stopped rather than error",
                        this::normalScheduledExit),
                new NamedTest("exact IBC 3.24.2 error-exit marker survives to final failure state",
                        this::reportedErrorExit),
                new NamedTest("IBC error marker outranks StartIBC's generic normal-exit footer",
                        this::reportedErrorWithNormalFooter),
                new NamedTest("profile updates are blocked while running and accepted when stopped", this::updateProfile),
                new NamedTest("manager exit leaves active wrapper configuration to the detached relay", this::prepareExit),
                new NamedTest("failed final runtime-config cleanup remains visible and blocks exit", this::scrubFailure),
                new NamedTest("controller reattaches to an exact live process identity", this::reattach),
                new NamedTest("runtime registry sorts updates and removes stopped controllers", this::registryProfiles),
                new NamedTest("runtime registry retains omitted running controllers", this::registryRetainsRunning),
                new NamedTest("runtime registry auto-starts only enabled auto-start profiles", this::registryAutoStart),
                new NamedTest("runtime registry permits exit while detached relays own active cleanup", this::registryExitBlock));
    }

    private void initialState() throws Exception {
        try (Fixture fixture = new Fixture(CredentialMode.MANUAL)) {
            Assertions.equals(RuntimeState.STOPPED, fixture.controller.status().state(), "new controller must be stopped");
            Assertions.isFalse(fixture.controller.status().processAlive(), "new controller must have no process");
            Assertions.equals(fixture.profile, fixture.controller.profile(), "profile must be retained");
            Assertions.equals(List.of(), fixture.controller.logs().snapshot(), "runtime log buffer must start empty");
        }
    }

    private void validationFailure() throws Exception {
        try (Fixture fixture = new Fixture(CredentialMode.MANUAL)) {
            fixture.profile = fixture.profile.toBuilder().name("").twsMajorVersion("bad").build();
            fixture.rebuildController();
            Assertions.throwsType(RuntimeControllerException.class, fixture.controller::start,
                    "invalid profile must not start");
            Assertions.equals(RuntimeState.ERROR, fixture.controller.status().state(), "validation error must become status error");
            Assertions.equals(0, fixture.runtime.createCalls, "runtime config must not be created");
            Assertions.equals(0, fixture.launcher.calls, "process must not launch");
        }
    }

    private void occupiedCommandPort() throws Exception {
        try (Fixture fixture = new Fixture(CredentialMode.MANUAL)) {
            fixture.ports.commandOpen = true;
            RuntimeControllerException error = Assertions.throwsType(RuntimeControllerException.class,
                    fixture.controller::start, "occupied command port must stop before launch");
            Assertions.contains(error.getMessage(), Integer.toString(fixture.profile.commandServerPort()),
                    "conflicting command port must be named");
            Assertions.equals(0, fixture.runtime.cleanCalls, "preflight conflict must precede runtime file work");
            Assertions.equals(0, fixture.launcher.calls, "preflight conflict must precede process launch");
            Assertions.equals(RuntimeState.ERROR, fixture.controller.status().state(),
                    "port conflict must be visible as an error state");
            Assertions.isTrue(fixture.controller.status().commandPortOpen(),
                    "status must identify the occupied command port");
        }
    }

    private void occupiedApiPort() throws Exception {
        try (Fixture fixture = new Fixture(CredentialMode.MANUAL)) {
            fixture.ports.apiOpen = true;
            RuntimeControllerException error = Assertions.throwsType(RuntimeControllerException.class,
                    fixture.controller::start, "occupied API port must stop before launch");
            Assertions.contains(error.getMessage(), Integer.toString(fixture.profile.apiPort()),
                    "conflicting API port must be named");
            Assertions.equals(0, fixture.runtime.cleanCalls, "preflight conflict must precede runtime file work");
            Assertions.equals(0, fixture.launcher.calls, "preflight conflict must precede process launch");
            Assertions.equals(RuntimeState.ERROR, fixture.controller.status().state(),
                    "port conflict must be visible as an error state");
            Assertions.isTrue(fixture.controller.status().apiListenerDetected(),
                    "status must identify the occupied API port");
        }
    }

    private void unavailablePortInspection() throws Exception {
        try (Fixture fixture = new Fixture(CredentialMode.MANUAL)) {
            fixture.ports.unknown = true;
            RuntimeControllerException error = Assertions.throwsType(RuntimeControllerException.class,
                    fixture.controller::start, "unknown listener state must stop before launch");
            Assertions.contains(error.getMessage(), "inspect local TCP listeners",
                    "the preflight error must explain that listener inspection failed");
            Assertions.equals(0, fixture.runtime.cleanCalls,
                    "unknown listener state must precede runtime configuration work");
            Assertions.equals(0, fixture.launcher.calls,
                    "unknown listener state must precede process launch");
            Assertions.equals(RuntimeState.ERROR, fixture.controller.status().state(),
                    "inspection failure must be visible as an error state");
        }
    }

    private void nonBlockingSnapshots() throws Exception {
        try (Fixture fixture = new Fixture(CredentialMode.MANUAL)) {
            fixture.ports.block = true;
            ExecutorService executor = Executors.newFixedThreadPool(3);
            Future<?> start = executor.submit(() -> {
                fixture.controller.start();
                return null;
            });
            try {
                Assertions.isTrue(fixture.ports.entered.await(2, TimeUnit.SECONDS),
                        "start must reach the deliberately blocked port probe");
                Future<Profile> profileRead = executor.submit(fixture.controller::profile);
                Future<io.github.ibcmanager.model.ProfileStatus> statusRead =
                        executor.submit(fixture.controller::status);
                try {
                    Assertions.equals(fixture.profile, profileRead.get(500, TimeUnit.MILLISECONDS),
                            "profile snapshot must not wait for the controller monitor");
                    Assertions.equals(RuntimeState.VALIDATING,
                            statusRead.get(500, TimeUnit.MILLISECONDS).state(),
                            "status snapshot must not wait for the controller monitor");
                } catch (TimeoutException ex) {
                    Assertions.fail("profile/status reads blocked behind start()");
                }
            } finally {
                fixture.ports.release.countDown();
                try {
                    start.get(5, TimeUnit.SECONDS);
                } finally {
                    executor.shutdownNow();
                    executor.awaitTermination(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    private void startManual() throws Exception {
        try (Fixture fixture = new Fixture(CredentialMode.MANUAL)) {
            fixture.controller.start();
            Assertions.equals(RuntimeState.STARTING, fixture.controller.status().state(), "start must enter STARTING");
            Assertions.isTrue(fixture.controller.status().processAlive(), "process must be marked alive");
            Assertions.equals(fixture.process.pid(), fixture.controller.status().pid(), "status must expose exact PID");
            Assertions.equals(1, fixture.runtime.cleanCalls, "stale runtime config must be cleaned first");
            Assertions.equals(1, fixture.runtime.createCalls, "one runtime config must be created");
            Assertions.equals(1, fixture.launcher.calls, "one process must be launched");
            Assertions.equals(1, fixture.ports.invalidations,
                    "launch preflight must force one fresh passive listener snapshot");
            Assertions.fileExists(fixture.paths.runtimeState(fixture.profile.id()), "process identity must be persisted");
            Assertions.contains(fixture.launcher.initialLogText, "IBC Manager session",
                    "session header must be supplied to the buffered process logger");
            fixture.controller.start();
            Assertions.equals(1, fixture.launcher.calls, "starting an already running profile must be idempotent");
        }
    }

    private void startEncrypted() throws Exception {
        try (Fixture fixture = new Fixture(CredentialMode.ENCRYPTED)) {
            fixture.credentials.save(fixture.profile.id(), "stored-password".toCharArray());
            fixture.controller.start();
            Assertions.equals(1, fixture.credentials.loadCalls, "encrypted start must load one credential");
            Assertions.equals("stored-password", fixture.runtime.receivedPassword,
                    "runtime provider must receive the stored credential");
            Assertions.throwsType(IllegalStateException.class, fixture.credentials.lastLoaded::copy,
                    "controller must clear loaded SecureChars after config creation");
            Assertions.isTrue(fixture.controller.runtimeConfigurationPresent(),
                    "temporary credential config must exist until authentication progresses");
        }
    }

    private void startExisting() throws Exception {
        try (Fixture fixture = new Fixture(CredentialMode.EXISTING_CONFIG)) {
            Path existing = fixture.root.resolve("existing.ini");
            Files.writeString(existing, "IbLoginId=user\nIbPassword=existing\n");
            fixture.profile = fixture.profile.toBuilder().baseConfigPath(existing).build();
            fixture.rebuildController();
            fixture.controller.start();
            Assertions.equals(0, fixture.credentials.loadCalls, "existing-config mode must not query encrypted credential store");
            Assertions.equals(null, fixture.runtime.receivedPassword, "no SecureChars object is expected");
        }
    }

    private void credentialFailure() throws Exception {
        try (Fixture fixture = new Fixture(CredentialMode.ENCRYPTED)) {
            fixture.credentials.save(fixture.profile.id(), "stored-password".toCharArray());
            fixture.credentials.failLoad = true;
            fixture.controller.start();
            Assertions.fail("credential failure should throw");
        } catch (RuntimeControllerException expected) {
            Assertions.contains(expected.getMessage(), "Could not start profile", "start failure must identify profile");
        }
    }

    private void runtimeConfigFailure() throws Exception {
        try (Fixture fixture = new Fixture(CredentialMode.MANUAL)) {
            fixture.runtime.failCreate = true;
            Assertions.throwsType(RuntimeControllerException.class, fixture.controller::start,
                    "runtime config failure must propagate");
            Assertions.equals(0, fixture.launcher.calls, "process must not launch without config");
            Assertions.equals(RuntimeState.ERROR, fixture.controller.status().state(), "failure must update status");
        }
    }

    private void processLaunchFailure() throws Exception {
        try (Fixture fixture = new Fixture(CredentialMode.MANUAL)) {
            fixture.launcher.fail = true;
            Assertions.throwsType(RuntimeControllerException.class, fixture.controller::start,
                    "launcher failure must propagate");
            Assertions.isFalse(Files.exists(fixture.runtime.lastPath), "temporary config must be deleted after launch failure");
            Assertions.isFalse(Files.exists(fixture.paths.runtimeState(fixture.profile.id())), "identity must not remain");
        }
    }

    private void relayCleanupOwnership() throws Exception {
        try (Fixture fixture = new Fixture(CredentialMode.ENCRYPTED)) {
            fixture.credentials.save(fixture.profile.id(), "secret".toCharArray());
            fixture.launchFactory.cleanupOverride = fixture.root.resolve("wrong-config.ini");
            RuntimeControllerException failure = Assertions.throwsType(RuntimeControllerException.class,
                    fixture.controller::start,
                    "a long-lived StartIBC wrapper must not start unless its detached relay owns exact cleanup");
            Assertions.contains(failure.getMessage(), "Could not start profile",
                    "cleanup-ownership failure must be reported as a start failure");
            Assertions.equals(0, fixture.launcher.calls,
                    "ownership mismatch must be rejected before the process is launched");
            Assertions.isFalse(Files.exists(fixture.runtime.lastPath),
                    "failed ownership validation must remove the credential-bearing runtime file");
        }
    }

    private void identityFailureCleanup() throws Exception {
        try (Fixture fixture = new Fixture(CredentialMode.MANUAL)) {
            Path blocking = fixture.paths.runtimeDirectory(fixture.profile.id());
            Files.createDirectories(blocking.getParent());
            Files.writeString(blocking, "blocks identity directory");
            Assertions.throwsType(RuntimeControllerException.class, fixture.controller::start,
                    "identity write failure must abort start");
            Assertions.isFalse(fixture.process.isAlive(), "partially launched process must be terminated");
            Assertions.isTrue(fixture.process.destroyCalls + fixture.process.forceCalls > 0,
                    "exact managed process must receive termination");
            Assertions.isFalse(Files.exists(fixture.runtime.lastPath), "temporary credential file must be removed");
        }
    }

    private void listenerIsolation() throws Exception {
        try (Fixture fixture = new Fixture(CredentialMode.MANUAL)) {
            fixture.controller.addStatusListener(status -> { throw new IllegalStateException("listener bug"); });
            fixture.controller.start();
            Assertions.equals(RuntimeState.STARTING, fixture.controller.status().state(),
                    "listener exception must not change controller result");
            Assertions.isTrue(fixture.controller.logs().snapshot().stream().anyMatch(line -> line.contains("status listener failed")),
                    "listener failure must be recorded without its sensitive message");
        }
    }

    private void loginStates() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.MANUAL)) {
            fixture.appendLog("Login dialog WINDOW_OPENED");
            fixture.controller.refresh();
            Assertions.equals(RuntimeState.WAITING_FOR_LOGIN, fixture.controller.status().state(),
                    "login dialog log must be recognized");
            fixture.appendLog("Second factor authentication initiated");
            fixture.controller.refresh();
            Assertions.equals(RuntimeState.WAITING_FOR_SECOND_FACTOR, fixture.controller.status().state(),
                    "2FA log must be recognized");
        }
    }

    private void pausedAndExitedStates() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.MANUAL)) {
            fixture.appendLog("Login has completed");
            fixture.controller.refresh();
            Assertions.equals(RuntimeState.RUNNING, fixture.controller.status().state(),
                    "login completion must report running");
            fixture.appendLog("IBC is paused");
            fixture.controller.refresh();
            Assertions.equals(RuntimeState.PAUSED, fixture.controller.status().state(),
                    "a pause must not be downgraded to starting on the next poll");
            fixture.ports.apiOpen = true;
            fixture.ports.apiOwnerPid = fixture.process.pid();
            fixture.controller.refresh();
            Assertions.equals(RuntimeState.PAUSED, fixture.controller.status().state(),
                    "an open API socket must not mask an explicit pause");
            fixture.appendLog("Gateway finished at 22:00");
            fixture.controller.refresh();
            Assertions.equals(RuntimeState.STOPPING, fixture.controller.status().state(),
                    "a confirmed normal exit must outrank a briefly lingering API listener");
            Assertions.contains(fixture.controller.status().message(), "scheduled shutdown",
                    "the transition must explain that the exit was normal or scheduled");
        }
    }

    private void automaticRestartStates() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.MANUAL)) {
            fixture.ports.apiOpen = true;
            fixture.ports.apiOwnerPid = fixture.process.pid();
            fixture.appendLog("Exiting after error with exit code=4");
            fixture.controller.refresh();
            Assertions.equals(RuntimeState.UNKNOWN, fixture.controller.status().state(),
                    "a child error must remain yellow and outrank a listener from the exiting child");
            fixture.ports.apiOpen = false;

            fixture.appendLog("Program has exited");
            fixture.appendLog("IBC will autorestart shortly");
            fixture.controller.refresh();
            Assertions.equals(RuntimeState.RESTARTING, fixture.controller.status().state(),
                    "automatic restart must be represented explicitly in yellow");
            Assertions.contains(fixture.controller.status().message(), "automatic restart",
                    "the reason for the restart must remain visible");

            fixture.appendLog("Starting IBC with this command: replacement");
            fixture.controller.refresh();
            Assertions.equals(RuntimeState.STARTING, fixture.controller.status().state(),
                    "the replacement launch marker must begin a fresh child session");

            fixture.appendLog("Program has exited");
            fixture.appendLog("IBC will restart shortly due to 2FA completion timeout");
            fixture.controller.refresh();
            Assertions.equals(RuntimeState.RESTARTING, fixture.controller.status().state(),
                    "2FA timeout recovery must also remain a yellow restart state");
        }
    }

    private void secondFactorScrub() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.ENCRYPTED)) {
            Path temporary = fixture.runtime.lastPath;
            Assertions.fileExists(temporary, "runtime credential file must initially exist");
            fixture.appendLog("Second factor authentication initiated");
            fixture.controller.refresh();
            Assertions.fileExists(temporary,
                    "StartIBC may launch another IBC JVM after a 2FA timeout, so the config path must remain valid");
            fixture.appendLog("Login has completed");
            fixture.controller.refresh();
            Assertions.fileExists(temporary,
                    "login completion must not invalidate the config path reused by StartIBC restarts");
            Assertions.isTrue(fixture.controller.runtimeConfigurationPresent(),
                    "the runtime lease must remain owned for the complete wrapper lifetime");
        }
    }

    private void apiState() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.ENCRYPTED)) {
            fixture.ports.apiOpen = true;
            fixture.controller.refresh();
            Assertions.equals(RuntimeState.API_LISTENER_DETECTED, fixture.controller.status().state(),
                    "open API socket must be reported");
            Assertions.isTrue(fixture.controller.status().apiListenerDetected(), "API flag must be true");
            Assertions.contains(fixture.controller.status().message(), "handshake is not verified",
                    "status must not overclaim API readiness");
            Assertions.fileExists(fixture.runtime.lastPath,
                    "API listener detection must not invalidate the config reused by the StartIBC wrapper");
        }
    }

    private void apiOwnership() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.MANUAL)) {
            fixture.ports.apiOpen = true;
            fixture.ports.apiOwnerPid = fixture.process.pid() + 999;
            fixture.controller.refresh();
            Assertions.equals(RuntimeState.UNKNOWN, fixture.controller.status().state(),
                    "a listener owned by an unrelated process must never produce green status");
            Assertions.isFalse(fixture.controller.status().apiListenerDetected(),
                    "wrong-PID listener must not be reported as the managed API listener");
            Assertions.contains(fixture.controller.status().message(), "does not belong",
                    "uncertain status must explain the ownership mismatch");

            fixture.ports.apiOwnerPid = -1;
            fixture.controller.refresh();
            Assertions.equals(RuntimeState.UNKNOWN, fixture.controller.status().state(),
                    "listener without ownership metadata must remain yellow/uncertain");
            Assertions.contains(fixture.controller.status().message(), "did not expose enough ownership data",
                    "missing ownership must be explicit rather than silently accepted");
        }
    }

    private void unknownApiListenerState() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.MANUAL)) {
            fixture.ports.unknown = true;
            fixture.controller.refresh();
            Assertions.equals(RuntimeState.UNKNOWN, fixture.controller.status().state(),
                    "unavailable listener inspection without a login hint must remain uncertain");
            Assertions.isFalse(fixture.controller.status().apiListenerDetected(),
                    "an unavailable listener inspection must never produce a green API flag");
            Assertions.contains(fixture.controller.status().message(), "Could not inspect",
                    "uncertain status must explain the listener inspection failure");

            fixture.appendLog("Login has completed");
            fixture.controller.refresh();
            Assertions.equals(RuntimeState.RUNNING, fixture.controller.status().state(),
                    "a confirmed login hint must remain visible when listener inspection is unavailable");
            Assertions.contains(fixture.controller.status().message(), "temporarily unavailable",
                    "logged-in status must state that listener inspection is unavailable");
        }
    }

    private void runningAndErrorStates() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.MANUAL)) {
            fixture.appendLog("Login has completed");
            fixture.controller.refresh();
            Assertions.equals(RuntimeState.RUNNING, fixture.controller.status().state(), "login completion must report running");
            fixture.appendLog("Login failed: bad credentials");
            fixture.controller.refresh();
            Assertions.equals(RuntimeState.UNKNOWN, fixture.controller.status().state(),
                    "a child login error must remain nonterminal until StartIBC decides whether to restart");
            Assertions.contains(fixture.controller.status().message(), "wrapper decision",
                    "pending child failure must explain why it is not yet terminal");
        }
    }

    private void commandReadyState() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.MANUAL)) {
            int commandProbeCalls = fixture.ports.commandCalls;
            fixture.appendLog("CommandServer started and is ready to accept commands");
            fixture.controller.refresh();
            Assertions.equals(RuntimeState.STARTING, fixture.controller.status().state(),
                    "command server alone must not be mistaken for authenticated state");
            Assertions.isTrue(fixture.controller.status().commandPortOpen(), "command port flag must be true");
            Assertions.contains(fixture.controller.status().message(), "waiting for login", "message must explain state");
            Assertions.equals(commandProbeCalls, fixture.ports.commandCalls,
                    "IBC readiness output must not require an active TCP probe");
        }
    }

    private void commandServerProbeNoise() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.MANUAL)) {
            int commandCallsAfterPreflight = fixture.ports.commandCalls;
            int apiCallsAfterPreflight = fixture.ports.apiCalls;
            fixture.appendLog("CommandServer started and is ready to accept commands");
            for (int attempt = 0; attempt < 12; attempt++) fixture.controller.refresh();
            Assertions.isTrue(fixture.controller.status().commandPortOpen(),
                    "IBC readiness output must keep command actions available");
            Assertions.equals(commandCallsAfterPreflight, fixture.ports.commandCalls,
                    "periodic status refresh must never connect to the IBC command server");
            Assertions.equals(apiCallsAfterPreflight + 12, fixture.ports.apiCalls,
                    "API listener monitoring must continue at the normal refresh cadence");
        }
    }

    private void commands() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.MANUAL)) {
            fixture.profile = fixture.profile.toBuilder().bindAddress("0.0.0.0").build();
            fixture.controller.forceStop();
            fixture.process = new FakeProcess();
            fixture.launcher.process = fixture.process;
            fixture.rebuildController();
            fixture.controller.start();
            authenticate(fixture);

            fixture.controller.reconnectData();
            fixture.controller.reconnectAccount();
            Assertions.isFalse(fixture.controller.canExecute(IbcCommand.ENABLEAPI),
                    "ENABLEAPI must remain unavailable for IB Gateway profiles");

            FakeProcess original = fixture.process;
            original.exitOnWait = true;
            FakeProcess replacement = new FakeProcess();
            fixture.launcher.nextProcess = replacement;
            int commandProbeCalls = fixture.ports.commandCalls;
            IbcCommandResult restart = fixture.controller.restartSession();
            fixture.process = replacement;
            fixture.ports.apiOwnerPid = replacement.pid();
            Assertions.isTrue(restart.accepted(), "controlled restart must report an initiated replacement session");
            Assertions.equals(RuntimeState.STARTING, fixture.controller.status().state(),
                    "controlled restart must launch a fresh wrapper");
            Assertions.isFalse(fixture.commands.commands.contains(IbcCommand.RESTART),
                    "IBC's native RESTART command must not be used because its fallback mutates restart scheduling");

            authenticate(fixture);
            IbcCommandResult pause = fixture.controller.pause();
            Assertions.isTrue(pause.success(), "PAUSE acknowledgement must be accepted");
            Assertions.equals(RuntimeState.PAUSING, fixture.controller.status().state(),
                    "preliminary PAUSE acknowledgement must remain pending until wrapper confirmation");
            Assertions.equals(List.of(IbcCommand.RECONNECTDATA, IbcCommand.RECONNECTACCOUNT,
                            IbcCommand.STOP, IbcCommand.PAUSE), fixture.commands.commands,
                    "only safe, requested commands must be sent in order");
            Assertions.isTrue(fixture.commands.hosts.stream().allMatch("127.0.0.1"::equals),
                    "wildcard bind address must be controlled through loopback");
            Assertions.equals(commandProbeCalls + 1, fixture.ports.commandCalls,
                    "the controlled restart may perform exactly one fresh-start occupancy check, but commands must not add probes");
        }
    }

    private void pauseExit() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.ENCRYPTED)) {
            Path config = fixture.runtime.lastPath;
            authenticate(fixture);
            fixture.controller.pause();
            Assertions.equals(RuntimeState.PAUSING, fixture.controller.status().state(),
                    "accepted PAUSE must remain pending until the wrapper confirms completion");
            Assertions.isTrue(fixture.controller.status().processAlive(),
                    "the process can remain alive briefly after PAUSE is accepted");

            fixture.appendLog("IBC is paused");
            fixture.controller.refresh();
            fixture.process.completeExit(0);

            Assertions.equals(RuntimeState.PAUSED, fixture.controller.status().state(),
                    "the expected process exit must preserve PAUSED state");
            Assertions.isFalse(fixture.controller.status().processAlive(),
                    "paused Gateway/TWS must no longer be reported alive after exit");
            Assertions.equals(Integer.valueOf(0), fixture.controller.status().exitCode(),
                    "pause exit code must be retained");
            Assertions.contains(fixture.controller.status().message(), "start the profile",
                    "paused state must explain how to continue the preserved session");
            Assertions.isFalse(Files.exists(config), "pause exit must remove the temporary runtime config");
            Assertions.isFalse(Files.exists(fixture.paths.runtimeState(fixture.profile.id())),
                    "pause exit must remove the stale process identity");
        }
    }

    private void pauseExitWithoutConfirmation() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.MANUAL)) {
            authenticate(fixture);
            fixture.commands.result = new IbcCommandResult(CommandDisposition.ACCEPTED,
                    "OK PAUSE in progress");
            fixture.controller.pause();
            fixture.process.completeExit(1);
            Assertions.equals(RuntimeState.ERROR, fixture.controller.status().state(),
                    "preliminary PAUSE acceptance is not proof of a resumable paused session");
            Assertions.contains(fixture.controller.status().message(), "never confirmed",
                    "the error must identify missing StartIBC pause confirmation");
        }
    }

    private void commandClosed() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.MANUAL)) {
            Assertions.throwsType(RuntimeControllerException.class, fixture.controller::pause,
                    "commands must be blocked until login and command-server readiness are confirmed");
            Assertions.equals(List.of(), fixture.commands.commands,
                    "capability gating must prevent an unsafe early command from reaching IBC");
            Assertions.isFalse(fixture.controller.status().commandPortOpen(),
                    "the command server must still be reported closed");
        }
    }

    private void replacementLaunchRevokesCommands() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.MANUAL)) {
            authenticate(fixture);
            Assertions.isTrue(fixture.controller.canExecute(IbcCommand.RECONNECTDATA),
                    "fixture must begin in a command-capable state");
            Assertions.isFalse(fixture.controller.canExecute(IbcCommand.RESTART),
                    "native IBC RESTART must never be exposed by the controller");

            fixture.appendLog("Starting IBC with this command: java -cp IBC.jar ibcalpha.ibc.IbcGateway");
            fixture.controller.refresh();
            Assertions.equals(RuntimeState.STARTING, fixture.controller.status().state(),
                    "replacement IBC JVM must return the profile to a startup state");
            Assertions.isFalse(fixture.controller.canExecute(IbcCommand.RECONNECTDATA),
                    "stale main-window readiness must not survive an internal IBC restart");
            Assertions.throwsType(RuntimeControllerException.class, fixture.controller::reconnectData,
                    "controller enforcement must block unsafe pre-main-window commands even if UI is bypassed");
        }
    }

    private void commandRejected() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.MANUAL)) {
            authenticate(fixture);
            fixture.commands.result = new IbcCommandResult(false, "ERROR not permitted");
            RuntimeControllerException error = Assertions.throwsType(RuntimeControllerException.class,
                    fixture.controller::reconnectData, "rejected command must propagate");
            Assertions.contains(error.getMessage(), "not permitted", "IBC response must be included");
        }
    }

    private void gracefulStopRace() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.MANUAL)) {
            int commandProbeCalls = fixture.ports.commandCalls;
            fixture.process.exitOnWait = true;
            fixture.controller.stop();
            Assertions.equals(RuntimeState.STOPPED, fixture.controller.status().state(), "expected exit must end stopped");
            Assertions.isFalse(fixture.controller.status().processAlive(), "stopped process must not be alive");
            Assertions.equals(List.of(IbcCommand.STOP), fixture.commands.commands, "graceful STOP must be requested");
            Assertions.equals(commandProbeCalls, fixture.ports.commandCalls,
                    "graceful STOP must not open a preliminary health-check connection");
            Assertions.isFalse(Files.exists(fixture.paths.runtimeState(fixture.profile.id())), "identity must be removed");
        }
    }

    private void stopCommandFailure() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.MANUAL)) {
            fixture.commands.fail = true;
            fixture.process.exitOnWait = false;
            RuntimeControllerException error = Assertions.throwsType(RuntimeControllerException.class,
                    fixture.controller::stop,
                    "a failed graceful stop must require an explicit Force Stop instead of killing the process");
            Assertions.contains(error.getMessage(), "not killed",
                    "timeout must explain that no implicit force termination occurred");
            Assertions.equals(RuntimeState.STOPPING, fixture.controller.status().state(),
                    "the still-running process must remain visibly in a stop-pending state");
            Assertions.isTrue(fixture.process.isAlive(), "the managed process must remain alive");
            Assertions.equals(0, fixture.process.destroyCalls + fixture.process.forceCalls,
                    "normal Stop must never escalate to forced termination");
            Assertions.isTrue(fixture.controller.logs().snapshot().stream().anyMatch(line -> line.contains("STOP command failed")),
                    "STOP communication failure must be logged");
        }
    }

    private void forceStop() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.MANUAL)) {
            long pid = fixture.process.pid();
            fixture.controller.forceStop();
            Assertions.equals(RuntimeState.STOPPED, fixture.controller.status().state(), "force stop must end stopped");
            Assertions.isFalse(fixture.process.isAlive(), "managed process must be dead");
            Assertions.equals(pid, fixture.process.pid(), "only the selected process identity is targeted");
        }
    }

    private void unexpectedExit() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.ENCRYPTED)) {
            Path config = fixture.runtime.lastPath;
            fixture.process.completeExit(9);
            Assertions.equals(RuntimeState.ERROR, fixture.controller.status().state(), "unexpected exit must report error");
            Assertions.equals(Integer.valueOf(9), fixture.controller.status().exitCode(), "exit code must be retained");
            Assertions.isFalse(Files.exists(config), "runtime config must be removed on exit");
            Assertions.isFalse(Files.exists(fixture.paths.runtimeState(fixture.profile.id())), "identity must be removed on exit");
        }
    }

    private void normalScheduledExit() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.ENCRYPTED)) {
            Path config = fixture.runtime.lastPath;
            fixture.appendLog("Program has exited");
            fixture.appendLog("Normal exit");
            fixture.appendLog("Gateway finished at 23:45");
            fixture.controller.refresh();
            Assertions.equals(RuntimeState.STOPPING, fixture.controller.status().state(),
                    "normal wrapper shutdown must remain transitional until process exit");

            fixture.process.completeExit(0);
            Assertions.equals(RuntimeState.STOPPED, fixture.controller.status().state(),
                    "ClosedownAt and other normal wrapper exits must not become ERROR");
            Assertions.contains(fixture.controller.status().message(), "normal or scheduled shutdown",
                    "final stopped state must describe the expected lifecycle outcome");
            Assertions.equals(Integer.valueOf(0), fixture.controller.status().exitCode(),
                    "normal wrapper exit code must be retained");
            Assertions.isFalse(Files.exists(config), "runtime config must still be cleaned after scheduled shutdown");
        }
    }

    private void reportedErrorExit() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.ENCRYPTED)) {
            fixture.appendLog("Exiting after error with exit code=4");
            fixture.controller.refresh();
            Assertions.equals(RuntimeState.UNKNOWN, fixture.controller.status().state(),
                    "the controller must wait for the wrapper decision while it remains alive");
            fixture.process.completeExit(4);
            Assertions.equals(RuntimeState.ERROR, fixture.controller.status().state(),
                    "wrapper exit without a restart after IBC's exact error marker must be fatal");
            Assertions.contains(fixture.controller.status().message(), "after reporting an error",
                    "the final message must distinguish an IBC-reported failure from an unexplained exit");
            Assertions.equals(Integer.valueOf(4), fixture.controller.status().exitCode(),
                    "the reported error exit code must be retained");
        }
    }

    private void reportedErrorWithNormalFooter() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.ENCRYPTED)) {
            fixture.appendLog("Exiting after error with exit code=4");
            fixture.appendLog("Program has exited");
            fixture.appendLog("Normal exit");
            fixture.appendLog("Gateway finished at 23:45");
            fixture.controller.refresh();
            Assertions.equals(RuntimeState.UNKNOWN, fixture.controller.status().state(),
                    "StartIBC's generic normal-exit footer must not hide an earlier IBC error");
            Assertions.contains(fixture.controller.status().message(), "IBC-reported error",
                    "the transitional status must retain the actual failure reason");

            fixture.process.completeExit(0);
            Assertions.equals(RuntimeState.ERROR, fixture.controller.status().state(),
                    "an IBC-reported error must outrank StartIBC's generic footer even when the wrapper exits zero");
            Assertions.contains(fixture.controller.status().message(), "after reporting an error",
                    "the final state must preserve the IBC error classification");
        }
    }

    private void updateProfile() throws Exception {
        try (Fixture fixture = startedFixture(CredentialMode.MANUAL)) {
            Profile renamed = fixture.profile.toBuilder().name("Renamed").build();
            Assertions.throwsType(IllegalStateException.class, () -> fixture.controller.updateProfile(renamed),
                    "running profile must not change under its process");
            fixture.controller.forceStop();
            fixture.controller.updateProfile(renamed);
            Assertions.equals("Renamed", fixture.controller.profile().name(), "stopped profile must update");
            Profile otherId = renamed.toBuilder().id(UUID.randomUUID()).build();
            Assertions.throwsType(IllegalArgumentException.class, () -> fixture.controller.updateProfile(otherId),
                    "controller identity must remain stable");
        }
    }

    private void prepareExit() throws Exception {
        try (Fixture encrypted = startedFixture(CredentialMode.ENCRYPTED)) {
            Assertions.isTrue(encrypted.controller.prepareForManagerExit(),
                    "the detached process relay owns cleanup while the StartIBC wrapper remains alive");
            Assertions.fileExists(encrypted.runtime.lastPath,
                    "the config path must remain available for automatic wrapper restarts after Manager exits");
        }
        try (Fixture manual = startedFixture(CredentialMode.MANUAL)) {
            Path config = manual.runtime.lastPath;
            Assertions.isTrue(manual.controller.prepareForManagerExit(),
                    "manual mode contains no password and must not block manager exit");
            Assertions.fileExists(config, "manual startup config must remain so IBC can finish reading it");
        }
    }

    private void scrubFailure() throws Exception {
        try (Fixture fixture = new Fixture(CredentialMode.ENCRYPTED)) {
            fixture.credentials.save(fixture.profile.id(), "secret".toCharArray());
            fixture.runtime.directoryLease = true;
            fixture.controller.start();
            fixture.process.completeExit(9);
            Assertions.equals(RuntimeState.ERROR, fixture.controller.status().state(),
                    "a final cleanup failure must be visible as an error");
            Assertions.isTrue(fixture.controller.runtimeConfigurationPresent(),
                    "failed final cleanup must retain the lease for a later retry");
            Assertions.isFalse(fixture.controller.prepareForManagerExit(),
                    "manager exit must remain blocked once the wrapper is gone and secure cleanup still fails");
        }
    }

    private void reattach() throws Exception {
        Path root = TestSupport.tempDirectory("controller-reattach");
        Process raw = new ProcessBuilder(TestSupport.javaCommand("sleep", "30000")).start();
        try {
            AppPaths paths = new AppPaths(root.resolve("data"));
            Profile profile = TestSupport.validProfile(root.resolve("install"));
            ProcessIdentityStore identities = new ProcessIdentityStore(paths);
            identities.save(profile.id(), new JavaManagedProcess(raw));
            Path runtime = paths.runtimeDirectory(profile.id()).resolve("config.ini");
            Files.createDirectories(runtime.getParent());
            Files.writeString(runtime, "IbPassword=temporary\n");
            MemoryCredentialStore credentials = new MemoryCredentialStore();
            FakePortProbe ports = new FakePortProbe();
            ports.commandOpen = true;
            ProfileRuntimeController controller = new ProfileRuntimeController(profile, paths,
                    new ProfileValidator(credentials), new FakeRuntimeConfigProvider(root.resolve("provider")), credentials,
                    new FakeLaunchFactory(root), new FakeProcessLauncher(new FakeProcess()), ports,
                    new FakeCommandClient(), identities, new ProcessTreeTerminator(), fixedClock());
            Assertions.equals(RuntimeState.UNKNOWN, controller.status().state(), "constructor must reattach exact live identity");
            Assertions.equals(raw.pid(), controller.status().pid(), "reattached PID must match process");
            Assertions.equals(0, ports.commandCalls,
                    "controller construction must not open a command-channel connection");
            controller.refresh();
            Assertions.isTrue(controller.status().commandPortOpen(),
                    "one fallback probe must restore command controls when historical readiness is unavailable");
            Assertions.equals(1, ports.commandCalls, "reattachment fallback must probe the command port exactly once");
            for (int attempt = 0; attempt < 8; attempt++) controller.refresh();
            Assertions.equals(1, ports.commandCalls,
                    "periodic refresh after reattachment must not repeat the command-port probe");
            controller.forceStop();
            Assertions.isFalse(raw.isAlive(), "force stop must terminate reattached process");
            Assertions.isFalse(Files.exists(runtime), "reattached runtime config must be scrubbed on stop");
        } finally {
            raw.destroyForcibly();
            raw.waitFor(2, TimeUnit.SECONDS);
            TestSupport.deleteTree(root);
        }
    }

    private void registryProfiles() throws Exception {
        Path root = TestSupport.tempDirectory("registry-profiles");
        List<Fixture> fixtures = new ArrayList<>();
        try (RuntimeRegistry registry = registry(root, fixtures)) {
            Profile zeta = TestSupport.validProfile(root.resolve("z")).toBuilder().name("Zeta").build();
            Profile alpha = TestSupport.validProfile(root.resolve("a")).toBuilder().name("alpha").build();
            registry.setProfiles(List.of(zeta, alpha));
            Assertions.equals(List.of("alpha", "Zeta"), registry.controllers().stream()
                    .map(controller -> controller.profile().name()).toList(), "registry must sort case-insensitively");
            Profile renamed = alpha.toBuilder().name("Beta").build();
            registry.upsert(renamed);
            Assertions.equals("Beta", registry.controller(alpha.id()).orElseThrow().profile().name(),
                    "stopped controller must accept profile update");
            registry.setProfiles(List.of(zeta));
            Assertions.isTrue(registry.controller(alpha.id()).isEmpty(), "omitted stopped controller must be removed");
        } finally {
            for (Fixture fixture : fixtures) fixture.close();
            TestSupport.deleteTree(root);
        }
    }

    private void registryRetainsRunning() throws Exception {
        Path root = TestSupport.tempDirectory("registry-running");
        List<Fixture> fixtures = new ArrayList<>();
        try (RuntimeRegistry registry = registry(root, fixtures)) {
            Profile profile = TestSupport.validProfile(root.resolve("p"));
            registry.setProfiles(List.of(profile));
            ProfileRuntimeController controller = registry.controller(profile.id()).orElseThrow();
            controller.start();
            registry.setProfiles(List.of());
            Assertions.isTrue(registry.controller(profile.id()).isPresent(), "running controller must not be orphaned");
            controller.forceStop();
            registry.setProfiles(List.of());
            Assertions.isTrue(registry.controller(profile.id()).isEmpty(), "stopped omitted controller may then be removed");
        } finally {
            for (Fixture fixture : fixtures) fixture.close();
            TestSupport.deleteTree(root);
        }
    }

    private void registryAutoStart() throws Exception {
        Path root = TestSupport.tempDirectory("registry-autostart");
        List<Fixture> fixtures = new ArrayList<>();
        try (RuntimeRegistry registry = registry(root, fixtures)) {
            Profile auto = TestSupport.validProfile(root.resolve("auto")).toBuilder().name("Auto").autoStart(true).build();
            Profile disabled = TestSupport.validProfile(root.resolve("disabled")).toBuilder()
                    .name("Disabled").enabled(false).autoStart(true).build();
            Profile manual = TestSupport.validProfile(root.resolve("manual")).toBuilder().name("Manual").autoStart(false).build();
            registry.setProfiles(List.of(auto, disabled, manual));
            registry.startAutoStartProfiles();
            Assertions.eventually(Duration.ofSeconds(3),
                    () -> registry.controller(auto.id()).orElseThrow().status().processAlive(),
                    "enabled auto-start profile must start asynchronously");
            Assertions.isFalse(registry.controller(disabled.id()).orElseThrow().status().processAlive(),
                    "disabled profile must not auto-start");
            Assertions.isFalse(registry.controller(manual.id()).orElseThrow().status().processAlive(),
                    "profile without auto-start must remain stopped");
        } finally {
            for (Fixture fixture : fixtures) fixture.close();
            TestSupport.deleteTree(root);
        }
    }

    private void registryExitBlock() throws Exception {
        Path root = TestSupport.tempDirectory("registry-exit");
        List<Fixture> fixtures = new ArrayList<>();
        try (RuntimeRegistry registry = registry(root, fixtures)) {
            Profile encrypted = TestSupport.validProfile(root.resolve("encrypted")).toBuilder()
                    .credentialMode(CredentialMode.ENCRYPTED).build();
            registry.setProfiles(List.of(encrypted));
            Fixture fixture = fixtures.get(0);
            fixture.credentials.save(encrypted.id(), "secret".toCharArray());
            registry.controller(encrypted.id()).orElseThrow().start();
            Assertions.equals(0, registry.controllersBlockingManagerExit().size(),
                    "active wrappers must delegate runtime-config cleanup to their detached relays");
        } finally {
            for (Fixture fixture : fixtures) fixture.close();
            TestSupport.deleteTree(root);
        }
    }

    private static RuntimeRegistry registry(Path root, List<Fixture> fixtures) {
        return new RuntimeRegistry(profile -> {
            try {
                Fixture fixture = new Fixture(root.resolve(profile.id().toString()), profile);
                fixtures.add(fixture);
                return fixture.controller;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
    }


    private static void authenticate(Fixture fixture) throws IOException {
        fixture.appendLog("Starting IBC with this command: java ...");
        fixture.appendLog("CommandServer started and is ready to accept commands");
        fixture.appendLog("Login has completed");
        fixture.controller.refresh();
    }

    private static Fixture startedFixture(CredentialMode mode) throws Exception {
        Fixture fixture = new Fixture(mode);
        if (mode == CredentialMode.ENCRYPTED) fixture.credentials.save(fixture.profile.id(), "secret".toCharArray());
        fixture.controller.start();
        return fixture;
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-08-01T08:00:00Z"), ZoneOffset.UTC);
    }

    private static final class Fixture implements AutoCloseable {
        private final Path root;
        private final AppPaths paths;
        private Profile profile;
        private final MemoryCredentialStore credentials = new MemoryCredentialStore();
        private final FakeRuntimeConfigProvider runtime;
        private final FakeLaunchFactory launchFactory;
        private final FakePortProbe ports = new FakePortProbe();
        private final FakeCommandClient commands = new FakeCommandClient();
        private FakeProcess process = new FakeProcess();
        private final FakeProcessLauncher launcher = new FakeProcessLauncher(process);
        private ProfileRuntimeController controller;

        private Fixture(CredentialMode mode) throws Exception {
            this(TestSupport.tempDirectory("controller"), null, mode);
        }

        private Fixture(Path root, Profile supplied) throws Exception {
            this(root, supplied, supplied == null ? CredentialMode.MANUAL : supplied.credentialMode());
        }

        private Fixture(Path root, Profile supplied, CredentialMode mode) throws Exception {
            this.root = root;
            this.paths = new AppPaths(root.resolve("data"));
            this.profile = supplied == null
                    ? TestSupport.validProfile(root.resolve("install")).toBuilder().credentialMode(mode).build()
                    : supplied;
            this.runtime = new FakeRuntimeConfigProvider(root.resolve("provider"));
            this.launchFactory = new FakeLaunchFactory(root);
            rebuildController();
        }

        private void rebuildController() {
            ports.apiOwnerPid = process.pid();
            controller = new ProfileRuntimeController(profile, paths, new ProfileValidator(credentials), runtime,
                    credentials, launchFactory, launcher, ports, commands, new ProcessIdentityStore(paths),
                    new ProcessTreeTerminator(), fixedClock());
        }

        private void appendLog(String line) throws IOException {
            Files.createDirectories(paths.profileLog(profile.id()).getParent());
            Files.writeString(paths.profileLog(profile.id()), line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        @Override
        public void close() throws IOException {
            if (controller != null && controller.status().processAlive()) {
                try { controller.forceStop(); }
                catch (RuntimeControllerException ignored) { }
            }
            TestSupport.deleteTree(root);
        }
    }

    private static final class MemoryCredentialStore implements CredentialStore {
        private final Map<UUID, char[]> values = new HashMap<>();
        private boolean failLoad;
        private int loadCalls;
        private SecureChars lastLoaded;
        @Override public boolean isAvailable() { return true; }
        @Override public void save(UUID profileId, char[] password) { values.put(profileId, Arrays.copyOf(password, password.length)); }
        @Override public SecureChars load(UUID profileId) throws CredentialStoreException {
            loadCalls++;
            if (failLoad) throw new CredentialStoreException("credential unavailable");
            char[] value = values.get(profileId);
            if (value == null) throw new CredentialStoreException("missing credential");
            lastLoaded = new SecureChars(value);
            return lastLoaded;
        }
        @Override public boolean exists(UUID profileId) { return values.containsKey(profileId); }
        @Override public void delete(UUID profileId) { values.remove(profileId); }
    }

    private static final class FakeRuntimeConfigProvider implements RuntimeConfigProvider {
        private final Path root;
        private int cleanCalls;
        private int createCalls;
        private boolean failCreate;
        private boolean directoryLease;
        private String receivedPassword;
        private Path lastPath;

        private FakeRuntimeConfigProvider(Path root) { this.root = root; }

        @Override public RuntimeConfigLease create(Profile profile, SecureChars password) throws IOException {
            createCalls++;
            if (failCreate) throw new IOException("runtime config failed");
            receivedPassword = password == null ? null : password.revealAsString();
            Files.createDirectories(root);
            lastPath = root.resolve("config-" + createCalls + ".ini");
            if (directoryLease) {
                Files.createDirectories(lastPath);
                Files.writeString(lastPath.resolve("prevents-delete"), "test");
            } else {
                Files.writeString(lastPath, "IbPassword=" + (receivedPassword == null ? "" : receivedPassword) + "\n");
            }
            return new RuntimeConfigLease(lastPath);
        }

        @Override public void cleanStale(Profile profile) { cleanCalls++; }
    }

    private static final class FakeLaunchFactory implements LaunchSpecFactory {
        private final Path root;
        private Path cleanupOverride;
        private FakeLaunchFactory(Path root) { this.root = root; }
        @Override public LaunchSpec create(Profile profile, Path runtimeConfig) {
            return new LaunchSpec(List.of("fake"), root, Map.of(), root.resolve("launch.cmd"),
                    "fake launch", cleanupOverride == null ? runtimeConfig : cleanupOverride);
        }
    }

    private static final class FakeProcessLauncher implements ProcessLauncher {
        private FakeProcess process;
        private int calls;
        private boolean fail;
        private FakeProcess nextProcess;
        private String initialLogText = "";
        private FakeProcessLauncher(FakeProcess process) { this.process = process; }
        @Override public ManagedProcess launch(LaunchSpec spec, Path logFile, String initialLogText) throws IOException {
            calls++;
            this.initialLogText = initialLogText;
            if (fail) throw new IOException("launch failed");
            if (calls > 1 && nextProcess != null) {
                process = nextProcess;
                nextProcess = null;
            }
            return process;
        }
    }

    private static final class FakePortProbe implements PortProbe {
        private boolean commandOpen;
        private boolean apiOpen;
        private boolean block;
        private boolean unknown;
        private int commandCalls;
        private int apiCalls;
        private int invalidations;
        private long apiOwnerPid = -1;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        @Override public void invalidate() { invalidations++; }
        @Override public PortListenerState inspect(String host, int port, Duration timeout) {
            return observe(host, port, timeout).state();
        }
        @Override public ListenerObservation observe(String host, int port, Duration timeout) {
            if (port == 4002) apiCalls++;
            else commandCalls++;
            if (block) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted test probe", ex);
                }
            }
            if (unknown) return ListenerObservation.unknown(port);
            boolean open = port == 4002 ? apiOpen : commandOpen;
            if (!open) return ListenerObservation.notListening(host, port);
            long owner = port == 4002 ? apiOwnerPid : -1;
            return ListenerObservation.listening(host, port, owner);
        }
    }

    private static final class FakeCommandClient implements CommandClient {
        private final List<IbcCommand> commands = new ArrayList<>();
        private final List<String> hosts = new ArrayList<>();
        private IbcCommandResult result = new IbcCommandResult(true, "OK command");
        private boolean fail;
        @Override public IbcCommandResult send(String host, int port, IbcCommand command, Duration timeout) throws IOException {
            hosts.add(host);
            commands.add(command);
            if (fail) throw new IOException("command socket failed");
            return result;
        }
    }

    private static final class FakeProcess implements ManagedProcess {
        private static final AtomicInteger IDS = new AtomicInteger();
        private final long pid = 8_000_000_000L + IDS.incrementAndGet();
        private final Instant started = Instant.parse("2026-08-01T08:00:00Z");
        private final CompletableFuture<ProcessHandle> exit = new CompletableFuture<>();
        private boolean alive = true;
        private boolean exitOnWait;
        private boolean ignoreDestroy;
        private int exitCode;
        private int destroyCalls;
        private int forceCalls;

        @Override public long pid() { return pid; }
        @Override public synchronized boolean isAlive() { return alive; }
        @Override public Optional<Instant> startInstant() { return Optional.of(started); }
        @Override public List<ProcessHandle> descendants() { return List.of(); }
        @Override public CompletableFuture<ProcessHandle> onExit() { return exit; }
        @Override public synchronized boolean waitFor(Duration timeout) {
            if (!alive) return true;
            if (exitOnWait) {
                completeExit(0);
                return true;
            }
            return false;
        }
        @Override public synchronized void destroy() {
            destroyCalls++;
            if (!ignoreDestroy) completeExit(143);
        }
        @Override public synchronized void destroyForcibly() {
            forceCalls++;
            completeExit(137);
        }
        @Override public synchronized OptionalInt exitCode() {
            return alive ? OptionalInt.empty() : OptionalInt.of(exitCode);
        }
        private synchronized void completeExit(int code) {
            if (!alive) return;
            alive = false;
            exitCode = code;
            exit.complete(ProcessHandle.current());
        }
    }
}
