package io.github.ibcmanager.runtime;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.config.RuntimeConfigLease;
import io.github.ibcmanager.config.RuntimeConfigLocation;
import io.github.ibcmanager.config.RuntimeConfigProvider;
import io.github.ibcmanager.config.SecondFactorPolicy;
import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.ProfileStatus;
import io.github.ibcmanager.model.PortListenerState;
import io.github.ibcmanager.model.RuntimeState;
import io.github.ibcmanager.model.Severity;
import io.github.ibcmanager.security.CredentialStore;
import io.github.ibcmanager.security.CredentialStoreException;
import io.github.ibcmanager.security.SecureChars;
import io.github.ibcmanager.security.SecureFileOperations;
import io.github.ibcmanager.validation.ProfileValidator;
import io.github.ibcmanager.validation.ValidationResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

public final class ProfileRuntimeController {
    private static final Duration PORT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration UNCONFIRMED_STOP_COMMAND_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration FORCE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration START_COORDINATION_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration START_MILESTONE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration STALLED_GRACEFUL_STOP_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration RECOVERY_PORT_RELEASE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration RECOVERY_PORT_POLL_INTERVAL = Duration.ofMillis(250);
    private static final Duration RECOVERY_COOLDOWN = Duration.ofSeconds(10);
    private static final int MAX_AUTOMATIC_RECOVERIES_PER_HOUR = 2;

    private enum ExpectedTermination {
        NONE,
        STOP,
        PAUSE,
        AUTO_RECOVERY
    }

    private enum StartReason {
        NORMAL,
        AUTOMATIC_RECOVERY
    }

    private final AppPaths paths;
    private final ProfileValidator validator;
    private final RuntimeConfigProvider runtimeConfigProvider;
    private final CredentialStore credentialStore;
    private final LaunchSpecFactory launchSpecFactory;
    private final ProcessLauncher processLauncher;
    private final PortProbe portProbe;
    private final CommandClient commandClient;
    private final ProcessIdentityStore identityStore;
    private final ProcessTreeTerminator terminator;
    private final Clock clock;
    private final StartCoordinator startCoordinator;
    private final Executor automaticRecoveryExecutor;
    private final RecoveryHistoryStore recoveryHistoryStore;
    private final RecoveryDiagnosticCapture recoveryDiagnosticCapture;
    private final RecoverySleeper recoverySleeper;
    private final RuntimeLogBuffer logBuffer = new RuntimeLogBuffer(5000);
    private final IbcLogStateParser stateParser = new IbcLogStateParser();
    private final StartupStallDetector startupStallDetector;
    private final CopyOnWriteArrayList<Consumer<ProfileStatus>> listeners = new CopyOnWriteArrayList<>();

    // profile and status are read by the Swing event dispatch thread on every UI tick and on
    // every profile-list cell repaint. They are volatile, and only ever replaced with fully
    // built immutable values, so those reads never contend for the controller monitor, which
    // start() and stop() legitimately hold for tens of seconds.
    private volatile Profile profile;
    private ManagedProcess process;
    private RuntimeConfigLease configLease;
    private LogTailer logTailer;
    private volatile ProfileStatus status;
    private ExpectedTermination expectedTermination = ExpectedTermination.NONE;
    private boolean commandProbeFallbackPending;
    private boolean automaticRecoveryTaskActive;
    private boolean automaticRecoveryCancelled;
    private boolean freshRecoveryStartActive;
    private boolean recoveryPendingHealth;
    private boolean recoveryHistoryUnreadable;
    private String recoveryHistoryError = "";
    private volatile Thread automaticRecoveryThread;
    private long automaticRecoveryGeneration = Long.MIN_VALUE;
    private volatile ListenerObservation apiListenerObservation = ListenerObservation.unknown(-1);

    public ProfileRuntimeController(
            Profile profile,
            AppPaths paths,
            ProfileValidator validator,
            RuntimeConfigProvider runtimeConfigProvider,
            CredentialStore credentialStore,
            LaunchSpecFactory launchSpecFactory,
            ProcessLauncher processLauncher,
            PortProbe portProbe,
            CommandClient commandClient,
            ProcessIdentityStore identityStore,
            ProcessTreeTerminator terminator,
            Clock clock) {
        this(profile, paths, validator, runtimeConfigProvider, credentialStore, launchSpecFactory,
                processLauncher, portProbe, commandClient, identityStore, terminator, clock,
                StartCoordinator.noop());
    }

    public ProfileRuntimeController(
            Profile profile,
            AppPaths paths,
            ProfileValidator validator,
            RuntimeConfigProvider runtimeConfigProvider,
            CredentialStore credentialStore,
            LaunchSpecFactory launchSpecFactory,
            ProcessLauncher processLauncher,
            PortProbe portProbe,
            CommandClient commandClient,
            ProcessIdentityStore identityStore,
            ProcessTreeTerminator terminator,
            Clock clock,
            StartCoordinator startCoordinator) {
        this(profile, paths, validator, runtimeConfigProvider, credentialStore, launchSpecFactory,
                processLauncher, portProbe, commandClient, identityStore, terminator, clock, startCoordinator,
                defaultRecoveryExecutor(), new RecoveryHistoryStore(paths), RecoveryDiagnosticCapture.noop(),
                RecoverySleeper.system());
    }

    public ProfileRuntimeController(
            Profile profile,
            AppPaths paths,
            ProfileValidator validator,
            RuntimeConfigProvider runtimeConfigProvider,
            CredentialStore credentialStore,
            LaunchSpecFactory launchSpecFactory,
            ProcessLauncher processLauncher,
            PortProbe portProbe,
            CommandClient commandClient,
            ProcessIdentityStore identityStore,
            ProcessTreeTerminator terminator,
            Clock clock,
            StartCoordinator startCoordinator,
            Executor automaticRecoveryExecutor,
            RecoveryHistoryStore recoveryHistoryStore,
            RecoveryDiagnosticCapture recoveryDiagnosticCapture,
            RecoverySleeper recoverySleeper) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.paths = Objects.requireNonNull(paths, "paths");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.runtimeConfigProvider = Objects.requireNonNull(runtimeConfigProvider, "runtimeConfigProvider");
        this.credentialStore = Objects.requireNonNull(credentialStore, "credentialStore");
        this.launchSpecFactory = Objects.requireNonNull(launchSpecFactory, "launchSpecFactory");
        this.processLauncher = Objects.requireNonNull(processLauncher, "processLauncher");
        this.portProbe = Objects.requireNonNull(portProbe, "portProbe");
        this.commandClient = Objects.requireNonNull(commandClient, "commandClient");
        this.identityStore = Objects.requireNonNull(identityStore, "identityStore");
        this.terminator = Objects.requireNonNull(terminator, "terminator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.startupStallDetector = new StartupStallDetector(clock);
        this.startCoordinator = Objects.requireNonNull(startCoordinator, "startCoordinator");
        this.automaticRecoveryExecutor = Objects.requireNonNull(automaticRecoveryExecutor,
                "automaticRecoveryExecutor");
        this.recoveryHistoryStore = Objects.requireNonNull(recoveryHistoryStore, "recoveryHistoryStore");
        this.recoveryDiagnosticCapture = Objects.requireNonNull(recoveryDiagnosticCapture,
                "recoveryDiagnosticCapture");
        this.recoverySleeper = Objects.requireNonNull(recoverySleeper, "recoverySleeper");
        this.status = ProfileStatus.stopped(profile.id());
        this.logTailer = new LogTailer(paths.profileLog(profile.id()));
        loadInitialRecoveryState();
        reattachIfPossible();
        if (process == null && recoveryRequiresManualIntervention()) {
            String message = recoveryHistoryUnreadable
                    ? "Automatic-recovery state could not be read: " + recoveryHistoryError
                            + ". Use an explicit Start to clear the invalid state and retry."
                    : "A previous automatic recovery did not reach completed login or a verified API listener. "
                            + "Automatic startup is blocked; use an explicit Start to acknowledge and retry.";
            setStatus(RuntimeState.RECOVERY_FAILED, false, false, PortListenerState.UNKNOWN,
                    -1, null, null, message);
        }
    }

    private static Executor defaultRecoveryExecutor() {
        return command -> {
            Thread thread = new Thread(command, "ibc-manager-auto-recovery");
            thread.setDaemon(true);
            thread.start();
        };
    }

    public Profile profile() {
        return profile;
    }

    public synchronized void updateProfile(Profile updated) {
        Objects.requireNonNull(updated, "updated");
        if (!updated.id().equals(profile.id())) throw new IllegalArgumentException("Cannot replace a controller with a different profile ID");
        if ((process != null && process.isAlive()) || automaticRecoveryTaskActive) {
            throw new IllegalStateException("Stop the profile and wait for automatic recovery to finish before changing its configuration");
        }
        profile = updated;
    }

    @FunctionalInterface
    public interface ConfigurationChange {
        void apply() throws IOException, io.github.ibcmanager.security.CredentialStoreException;
    }

    /** Serialize the complete save against manual start and asynchronous recovery. */
    public synchronized void editConfiguration(Profile expected, ConfigurationChange change)
            throws IOException, io.github.ibcmanager.security.CredentialStoreException {
        if ((process != null && process.isAlive()) || automaticRecoveryTaskActive) {
            throw new IOException("Stop the profile and wait for recovery before saving configuration");
        }
        if (!profile.equals(expected)) throw new IOException("The profile changed while the editor was open; reopen it");
        change.apply();
    }

    public ProfileStatus status() {
        return status;
    }

    public synchronized boolean automaticRecoveryInProgress() {
        return automaticRecoveryTaskActive;
    }

    public synchronized boolean recoveryRequiresManualIntervention() {
        return recoveryPendingHealth || recoveryHistoryUnreadable;
    }

    public RuntimeLogBuffer logs() {
        return logBuffer;
    }

    public ListenerObservation apiListenerObservation() {
        return apiListenerObservation;
    }

    public void addStatusListener(Consumer<ProfileStatus> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeStatusListener(Consumer<ProfileStatus> listener) {
        listeners.remove(listener);
    }

    public void start() throws RuntimeControllerException {
        startInternal(StartReason.NORMAL);
    }

    private synchronized void startInternal(StartReason reason) throws RuntimeControllerException {
        Objects.requireNonNull(reason, "reason");
        if (automaticRecoveryTaskActive && reason != StartReason.AUTOMATIC_RECOVERY) {
            throw new RuntimeControllerException("Automatic recovery is in progress");
        }
        if (process != null && process.isAlive()) return;
        if (reason == StartReason.NORMAL) {
            resetPendingRecoveryForManualStart();
            automaticRecoveryGeneration = Long.MIN_VALUE;
            automaticRecoveryCancelled = false;
            freshRecoveryStartActive = false;
        }
        RuntimeState validationState = reason == StartReason.AUTOMATIC_RECOVERY
                ? RuntimeState.STARTING_FRESH : RuntimeState.VALIDATING;
        String validationMessage = reason == StartReason.AUTOMATIC_RECOVERY
                ? "Validating the profile before a fresh automatic-recovery start" : "Validating profile";
        setStatus(validationState, false, false, PortListenerState.UNKNOWN, -1, null, null, validationMessage);
        ValidationResult validation = validator.validate(profile, true);
        List<String> errors = validation.issues().stream()
                .filter(issue -> issue.severity() == Severity.ERROR)
                .map(issue -> issue.field() + ": " + issue.message())
                .toList();
        if (!errors.isEmpty()) {
            setStatus(RuntimeState.ERROR, false, false, PortListenerState.UNKNOWN, -1, null, null, errors.get(0));
            throw new RuntimeControllerException(String.join(System.lineSeparator(), errors));
        }

        String commandHost = probeHost(profile.bindAddress());
        // Force one fresh operating-system listener snapshot for launch preflight. The command
        // and API checks below then share that snapshot and do not connect to either service.
        portProbe.invalidate();
        ListenerObservation commandObservation = portProbe.observe(
                commandHost, profile.commandServerPort(), PORT_TIMEOUT);
        ListenerObservation apiObservation = portProbe.observe(
                "127.0.0.1", profile.apiPort(), PORT_TIMEOUT);
        PortListenerState commandListener = commandObservation.state();
        PortListenerState apiListener = apiObservation.state();
        apiListenerObservation = apiObservation;
        if (commandListener == PortListenerState.UNKNOWN || apiListener == PortListenerState.UNKNOWN) {
            String message = "Could not inspect local TCP listeners before startup; "
                    + "verify the operating-system socket table is available and retry";
            setStatus(RuntimeState.ERROR, false, false, PortListenerState.UNKNOWN, -1, null, null, message);
            throw new RuntimeControllerException(message);
        }
        if (commandListener == PortListenerState.LISTENING) {
            String message = "IBC command-server port " + profile.commandServerPort()
                    + " is already in use on " + commandHost;
            setStatus(RuntimeState.ERROR, false, true, apiListener, -1, null, null, message);
            throw new RuntimeControllerException(message);
        }
        if (apiListener == PortListenerState.LISTENING) {
            String message = "TWS/Gateway API port " + profile.apiPort() + " is already in use on 127.0.0.1";
            setStatus(RuntimeState.ERROR, false, false, PortListenerState.LISTENING, -1, null, null, message);
            throw new RuntimeControllerException(message);
        }

        RuntimeConfigLease newLease = null;
        ManagedProcess launchedProcess = null;
        SecureChars secret = null;
        StartCoordinator.Lease startLease = null;
        try {
            startLease = startCoordinator.acquire(profile, START_COORDINATION_TIMEOUT);
            runtimeConfigProvider.cleanStale(profile);
            if (profile.credentialMode() == CredentialMode.ENCRYPTED) secret = credentialStore.load(profile.id());
            newLease = runtimeConfigProvider.create(profile, secret);
            LaunchSpec launchSpec = launchSpecFactory.create(profile, newLease.path());
            requireRelayCleanupOwnership(launchSpec, newLease);
            Path logFile = paths.profileLog(profile.id());
            String sessionHeader = sessionHeader(launchSpec.displayCommand());
            launchedProcess = processLauncher.launch(launchSpec, logFile, sessionHeader);
            identityStore.save(profile.id(), launchedProcess);
            configLease = newLease;
            process = launchedProcess;
            expectedTermination = ExpectedTermination.NONE;
            commandProbeFallbackPending = false;
            stateParser.reset();
            startupStallDetector.reset();
            logTailer = new LogTailer(logFile);
            freshRecoveryStartActive = reason == StartReason.AUTOMATIC_RECOVERY;
            Instant startedAt = launchedProcess.startInstant().orElseGet(clock::instant);
            RuntimeState initialState = freshRecoveryStartActive
                    ? RuntimeState.STARTING_FRESH : RuntimeState.STARTING;
            String initialMessage = freshRecoveryStartActive
                    ? "Fresh IBC/Gateway start launched after automatic recovery; authentication may be required"
                    : "IBC process started";
            setStatus(initialState, true, false, PortListenerState.NOT_LISTENING, launchedProcess.pid(),
                    startedAt, null, initialMessage);
            launchedProcess.onExit().thenRun(this::handleProcessExit);
            if (startLease.coordinated()) awaitStartMilestone(launchedProcess);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            RuntimeException cleanupFailure = cleanupFailedStart(launchedProcess, newLease);
            if (cleanupFailure != null) ex.addSuppressed(cleanupFailure);
            setStatus(RuntimeState.ERROR, false, false, PortListenerState.UNKNOWN, -1, null, null,
                    "Interrupted while waiting to start from the selected offline installation");
            throw new RuntimeControllerException("Interrupted while starting profile '" + profile.name() + "'", ex);
        } catch (CredentialStoreException | IOException | RuntimeException ex) {
            RuntimeException cleanupFailure = cleanupFailedStart(launchedProcess, newLease);
            if (cleanupFailure != null) ex.addSuppressed(cleanupFailure);
            setStatus(RuntimeState.ERROR, false, false, PortListenerState.UNKNOWN, -1, null, null, safeMessage(ex));
            throw new RuntimeControllerException("Could not start profile '" + profile.name() + "'", ex);
        } finally {
            if (secret != null) secret.close();
            if (startLease != null) {
                try {
                    startLease.close();
                } catch (IOException ex) {
                    logBuffer.append("IBC Manager: could not release the offline-installation startup lock: "
                            + safeMessage(ex));
                }
            }
        }
    }


    private void awaitStartMilestone(ManagedProcess launchedProcess)
            throws IOException, InterruptedException {
        StartMilestoneAwaiter.await(launchedProcess, stateParser, this::readNewLogLines,
                START_MILESTONE_TIMEOUT);
    }

    private static void requireRelayCleanupOwnership(LaunchSpec launchSpec,
            RuntimeConfigLease lease) throws IOException {
        Path expected = lease.path().toAbsolutePath().normalize();
        Path delegated = launchSpec.cleanupPath();
        if (delegated == null || !delegated.equals(expected)) {
            throw new IOException("The process relay was not assigned cleanup ownership for the exact runtime "
                    + "configuration path; refusing to launch a long-lived StartIBC wrapper");
        }
    }

    public synchronized void refresh() {
        if (process == null) {
            reattachIfPossible();
            if (process == null) return;
        }
        readNewLogLines();
        if (!process.isAlive()) {
            handleProcessExit();
            return;
        }

        boolean commandOpen = commandServerOpenForRefresh();
        ListenerObservation observedApi = portProbe.observe(
                "127.0.0.1", profile.apiPort(), PORT_TIMEOUT);
        apiListenerObservation = observedApi;
        boolean ownedApiListener = listenerBelongsToManagedTree(observedApi);
        PortListenerState apiListener = observedApi.state() == PortListenerState.LISTENING
                ? (ownedApiListener ? PortListenerState.LISTENING : PortListenerState.UNKNOWN)
                : observedApi.state();
        boolean apiListening = apiListener == PortListenerState.LISTENING;
        Optional<IbcLogStateParser.StateHint> hint = stateParser.latest();

        if (apiListening || stateParser.loginCompleted()) markAutomaticRecoveryHealthy();

        if (automaticRecoveryTaskActive) {
            if (expectedTermination == ExpectedTermination.NONE
                    && (apiListening || hint.map(IbcLogStateParser.StateHint::state)
                            .filter(state -> state == RuntimeState.WAITING_FOR_LOGIN
                                    || state == RuntimeState.WAITING_FOR_SECOND_FACTOR
                                    || state == RuntimeState.RUNNING)
                            .isPresent())) {
                automaticRecoveryCancelled = true;
            }
            RuntimeState recoveryState = isAutomaticRecoveryState(status.state())
                    ? status.state() : RuntimeState.AUTO_RECOVERY_STOPPING;
            setStatus(recoveryState, true, commandOpen, apiListener, process.pid(),
                    process.startInstant().orElse(status.startedAt()), null, status.message());
            return;
        }

        RuntimeState nextState;
        String message;
        if (expectedTermination == ExpectedTermination.STOP) {
            nextState = RuntimeState.STOPPING;
            message = status.message().isBlank() ? "Waiting for IBC to stop" : status.message();
        } else if (expectedTermination == ExpectedTermination.PAUSE) {
            nextState = RuntimeState.PAUSING;
            message = stateParser.pauseConfirmed()
                    ? "IBC wrapper confirmed pause; waiting for the wrapper process to exit"
                    : "Waiting for IBC pause confirmation";
        } else if (hint.isPresent() && hint.get().state() == RuntimeState.RESTARTING) {
            // A replacement IBC JVM has not started yet. This lifecycle observation must outrank
            // a listener that can remain visible briefly while the previous JVM is shutting down.
            nextState = RuntimeState.RESTARTING;
            message = hint.get().message();
        } else if (hint.isPresent() && hint.get().state() == RuntimeState.ERROR) {
            nextState = RuntimeState.ERROR;
            message = hint.get().message();
        } else if (hint.isPresent() && hint.get().state() == RuntimeState.WAITING_FOR_SECOND_FACTOR) {
            nextState = RuntimeState.WAITING_FOR_SECOND_FACTOR;
            message = secondFactorWaitingMessage(freshRecoveryStartActive, hint.get().message());
        } else if (hint.isPresent() && hint.get().state() == RuntimeState.WAITING_FOR_LOGIN) {
            nextState = RuntimeState.WAITING_FOR_LOGIN;
            message = freshRecoveryStartActive
                    ? "Fresh startup reached the login window"
                    : hint.get().message();
        } else if (hint.isPresent() && hint.get().state() == RuntimeState.PAUSED) {
            nextState = RuntimeState.PAUSED;
            message = hint.get().message();
        } else if (hint.isPresent() && hint.get().state() == RuntimeState.STOPPED) {
            nextState = RuntimeState.STOPPING;
            message = hint.get().message() + "; waiting for the wrapper process to exit";
        } else if (hint.isPresent() && hint.get().state() == RuntimeState.UNKNOWN) {
            nextState = RuntimeState.UNKNOWN;
            message = hint.get().message();
        } else if (apiListening) {
            nextState = RuntimeState.API_LISTENER_DETECTED;
            message = "Gateway's API listener is available" + listenerDetails(observedApi) + ".";
        } else if (observedApi.state() == PortListenerState.LISTENING) {
            nextState = RuntimeState.UNKNOWN;
            message = observedApi.ownershipAvailable()
                    ? "An API listener exists" + listenerDetails(observedApi)
                            + " but its PID does not belong to the managed IBC/Gateway process tree"
                    : "An API listener exists" + listenerDetails(observedApi)
                            + " but this operating system did not expose enough ownership data to verify it";
        } else if (startupStallDetector.isStalled(stateParser)) {
            handleStartupStall(commandOpen, apiListener);
            return;
        } else if (hint.isPresent() && hint.get().state() == RuntimeState.RUNNING) {
            nextState = RuntimeState.RUNNING;
            message = apiListener == PortListenerState.UNKNOWN
                    ? "Gateway login completed; API listener status is temporarily unavailable"
                    : "Gateway login completed; waiting for the API listener to become available";
        } else if (commandOpen) {
            nextState = freshRecoveryStartActive ? RuntimeState.STARTING_FRESH : RuntimeState.STARTING;
            message = freshRecoveryStartActive
                    ? "A new Gateway session is starting; waiting for login or 2FA approval"
                    : "Engine control is available; waiting for login to complete";
        } else if (apiListener == PortListenerState.UNKNOWN) {
            nextState = RuntimeState.UNKNOWN;
            message = "Could not inspect the local API TCP listener state";
        } else {
            nextState = freshRecoveryStartActive ? RuntimeState.STARTING_FRESH : RuntimeState.STARTING;
            message = freshRecoveryStartActive
                    ? "Starting a new Gateway session"
                    : hint.map(IbcLogStateParser.StateHint::message).orElse("Starting the included Gateway engine");
        }
        setStatus(nextState, true, commandOpen, apiListener, process.pid(),
                process.startInstant().orElse(status.startedAt()), null, message);
    }

    private String secondFactorWaitingMessage(boolean freshStart, String fallback) {
        String base = freshStart
                ? "Fresh startup reached second-factor authentication; approve the IBKR Mobile request"
                : fallback;
        if (!profile.reloginAfterSecondFactorTimeout()) return base;
        if (fallback.startsWith("Automatic 2FA retry blocked")) return fallback;
        return base + ". Login retry is enabled after "
                + (SecondFactorPolicy.RETRY_TIMEOUT_SECONDS / 60)
                + " minutes without approval; another phone notification depends on IBKR and phone delivery.";
    }

    private void handleStartupStall(boolean commandOpen, PortListenerState apiListener) {
        long minutes = Math.max(StartupStallDetector.DEFAULT_TIMEOUT.toMinutes(),
                startupStallDetector.elapsed().toMinutes());
        String application = profile.targetType() == io.github.ibcmanager.model.TargetType.GATEWAY
                ? "Gateway" : "TWS";
        String baseMessage = "Startup has made no progress for " + minutes + " minutes after IBC reported "
                + "'Starting " + application + "'. No login window, second-factor window, or verified API "
                + "listener was observed.";
        long generation = stateParser.sessionGeneration();
        if (!profile.autoRecoverStartupStall()) {
            boolean firstObservation = status.state() != RuntimeState.STARTUP_STALLED;
            String message = baseMessage + " Automatic recovery is disabled; use Force Stop, then Start.";
            setStatus(RuntimeState.STARTUP_STALLED, true, commandOpen, apiListener, process.pid(),
                    process.startInstant().orElse(status.startedAt()), null, message);
            if (firstObservation) logBuffer.append("IBC Manager: " + message);
            return;
        }
        if (automaticRecoveryGeneration == generation) {
            if (status.state() == RuntimeState.RECOVERY_FAILED) return;
            String message = baseMessage + " An automatic recovery decision has already been made for this "
                    + "IBC child generation.";
            setStatus(RuntimeState.STARTUP_STALLED, true, commandOpen, apiListener, process.pid(),
                    process.startInstant().orElse(status.startedAt()), null, message);
            return;
        }
        automaticRecoveryGeneration = generation;
        RecoveryHistoryStore.BeginResult decision;
        try {
            decision = recoveryHistoryStore.beginAttempt(profile.id(), clock.instant(),
                    MAX_AUTOMATIC_RECOVERIES_PER_HOUR);
        } catch (IOException ex) {
            setRecoveryFailed("Automatic recovery was not started because its persistent rate-limit state "
                    + "could not be read or updated: " + safeMessage(ex));
            return;
        }
        if (decision.disposition() == RecoveryHistoryStore.BeginDisposition.PREVIOUS_ATTEMPT_PENDING) {
            setRecoveryFailed(baseMessage + " A previous automatic recovery has not yet reached a healthy "
                    + "login/API state, so another force-restart attempt is blocked.");
            return;
        }
        if (decision.disposition() == RecoveryHistoryStore.BeginDisposition.RATE_LIMITED) {
            setRecoveryFailed(baseMessage + " The safety limit of " + decision.maximumAttempts()
                    + " automatic recoveries per hour has been reached.");
            return;
        }

        recoveryPendingHealth = true;
        automaticRecoveryTaskActive = true;
        automaticRecoveryCancelled = false;
        ProfileStatus stalledSnapshot = new ProfileStatus(profile.id(), RuntimeState.STARTUP_STALLED,
                true, commandOpen, apiListener, process.pid(),
                process.startInstant().orElse(status.startedAt()), null,
                baseMessage + " Automatic recovery attempt " + decision.attemptsInWindow() + " of "
                        + decision.maximumAttempts() + " will begin now.", clock.instant());
        setStatus(stalledSnapshot.state(), stalledSnapshot.processAlive(), stalledSnapshot.commandPortOpen(),
                stalledSnapshot.apiListenerState(), stalledSnapshot.pid(), stalledSnapshot.startedAt(),
                stalledSnapshot.exitCode(), stalledSnapshot.message());
        logBuffer.append("IBC Manager: " + stalledSnapshot.message());
        try {
            automaticRecoveryExecutor.execute(() -> runAutomaticRecovery(generation, stalledSnapshot));
        } catch (RejectedExecutionException ex) {
            automaticRecoveryTaskActive = false;
            setRecoveryFailed("Automatic recovery could not be scheduled: " + safeMessage(ex));
        }
    }

    public synchronized boolean canExecute(IbcCommand command) {
        Objects.requireNonNull(command, "command");
        if (process == null || !process.isAlive() || automaticRecoveryTaskActive) return false;
        if (command == IbcCommand.STOP) return true;
        // Native IBC RESTART can fall back to changing the application's persistent
        // auto-restart schedule. The Manager exposes restartSession(), which performs
        // graceful Stop followed by a fresh Start and preserves that schedule.
        if (command == IbcCommand.RESTART) return false;
        if (expectedTermination != ExpectedTermination.NONE || !status.commandPortOpen()) return false;
        if (!stateParser.mainWindowReady()) return false;
        return command != IbcCommand.ENABLEAPI
                || profile.targetType() == io.github.ibcmanager.model.TargetType.TWS;
    }

    public synchronized boolean canRestartSession() {
        return process != null && process.isAlive()
                && !automaticRecoveryTaskActive
                && expectedTermination == ExpectedTermination.NONE
                && status.commandPortOpen()
                && stateParser.mainWindowReady();
    }

    public synchronized IbcCommandResult restartSession() throws RuntimeControllerException {
        if (!canRestartSession()) throw new RuntimeControllerException("The profile cannot be restarted in its current state");
        logBuffer.append("IBC Manager: controlled restart requested; performing graceful Stop followed by a fresh Start");
        stop();
        if (process != null && process.isAlive()) {
            throw new RuntimeControllerException("Graceful stop is still pending; use Force Stop explicitly before restarting");
        }
        start();
        return new IbcCommandResult(CommandDisposition.ACCEPTED,
                "Controlled restart initiated: the previous wrapper stopped and a fresh IBC session is starting");
    }

    public synchronized IbcCommandResult pause() throws RuntimeControllerException {
        requireCommandCapability(IbcCommand.PAUSE);
        IbcCommandResult result = sendCommandUnchecked(IbcCommand.PAUSE);
        if (result.success()) {
            expectedTermination = ExpectedTermination.PAUSE;
            setStatus(RuntimeState.PAUSING, true, true, status.apiListenerState(), process.pid(),
                    status.startedAt(), null, "IBC accepted PAUSE; waiting for wrapper confirmation and exit");
        }
        return result;
    }

    public synchronized IbcCommandResult reconnectData() throws RuntimeControllerException {
        return sendCommand(IbcCommand.RECONNECTDATA);
    }

    public synchronized IbcCommandResult reconnectAccount() throws RuntimeControllerException {
        return sendCommand(IbcCommand.RECONNECTACCOUNT);
    }

    public synchronized IbcCommandResult enableApi() throws RuntimeControllerException {
        return sendCommand(IbcCommand.ENABLEAPI);
    }

    public synchronized void stop() throws RuntimeControllerException {
        if (automaticRecoveryTaskActive) {
            throw new RuntimeControllerException("Automatic recovery is in progress; use Force Stop to cancel it");
        }
        if (process == null || !process.isAlive()) {
            try {
                cleanupStoppedState("Already stopped", null);
                return;
            } catch (IOException ex) {
                throw new RuntimeControllerException("Could not clean stale runtime state", ex);
            }
        }
        boolean stalledAtRequest = status.state() == RuntimeState.STARTUP_STALLED;
        expectedTermination = ExpectedTermination.STOP;
        ManagedProcess stoppingProcess = process;
        setStatus(RuntimeState.STOPPING, true, status.commandPortOpen(), status.apiListenerState(),
                stoppingProcess.pid(), status.startedAt(), null, "Stopping through the IBC command server");
        try {
            try {
                Duration timeout = stateParser.commandServerState() == IbcLogStateParser.CommandServerState.OPEN
                        ? COMMAND_TIMEOUT : UNCONFIRMED_STOP_COMMAND_TIMEOUT;
                IbcCommandResult result = commandClient.send(probeHost(profile.bindAddress()),
                        profile.commandServerPort(), IbcCommand.STOP, timeout);
                markCommandServerAvailable(true);
                if (!result.success()) {
                    String response = result.response();
                    logBuffer.append("IBC Manager: graceful STOP command was rejected: " + response);
                    if (stalledAtRequest) {
                        restoreStalledAfterFailedStop("IBC rejected graceful STOP: " + response);
                    }
                }
            } catch (IOException ex) {
                markCommandServerAvailable(false);
                String failure = safeMessage(ex);
                logBuffer.append("IBC Manager: graceful STOP command failed: " + failure);
                if (stalledAtRequest) {
                    restoreStalledAfterFailedStop("IBC did not respond to graceful STOP: " + failure);
                }
            }
            Duration waitTimeout = stalledAtRequest
                    ? STALLED_GRACEFUL_STOP_TIMEOUT
                    : Duration.ofSeconds(profile.gracefulStopTimeoutSeconds());
            boolean stopped = stoppingProcess.waitFor(waitTimeout);
            if (!stopped) {
                String message = "Graceful stop timed out after " + waitTimeout.toSeconds()
                        + " seconds; the process was not killed. Use Force Stop explicitly if required.";
                logBuffer.append("IBC Manager: " + message);
                setStatus(RuntimeState.STOPPING, true, status.commandPortOpen(), status.apiListenerState(),
                        stoppingProcess.pid(), status.startedAt(), null, message);
                throw new RuntimeControllerException(message);
            }
            if (process == stoppingProcess) cleanupStoppedState("Stopped", stoppingProcess.exitCode());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeControllerException("Interrupted while stopping IBC", ex);
        } catch (IOException ex) {
            throw new RuntimeControllerException("Could not clean up runtime state after stopping IBC", ex);
        }
    }

    private void restoreStalledAfterFailedStop(String reason) throws RuntimeControllerException {
        expectedTermination = ExpectedTermination.NONE;
        String message = reason + ". The stalled process was not killed. Use Force Stop, then Start.";
        setStatus(RuntimeState.STARTUP_STALLED, true, false, status.apiListenerState(), process.pid(),
                status.startedAt(), null, message);
        throw new RuntimeControllerException(message);
    }

    public synchronized void forceStop() throws RuntimeControllerException {
        boolean cancellingRecovery = automaticRecoveryTaskActive;
        automaticRecoveryCancelled = cancellingRecovery;
        Thread recoveryThread = automaticRecoveryThread;
        if (cancellingRecovery && recoveryThread != null && recoveryThread != Thread.currentThread()) {
            recoveryThread.interrupt();
        }
        if (process == null || !process.isAlive()) {
            try {
                cleanupStoppedState("Already stopped", null);
                if (!cancellingRecovery) {
                    automaticRecoveryCancelled = false;
                    resetPendingRecoveryForManualStart();
                }
                return;
            } catch (IOException ex) {
                throw new RuntimeControllerException("Could not clean stale runtime state", ex);
            }
        }
        expectedTermination = ExpectedTermination.STOP;
        ManagedProcess stoppingProcess = process;
        try {
            if (!terminator.terminate(stoppingProcess, Duration.ofMillis(500), FORCE_TIMEOUT)) {
                throw new RuntimeControllerException("The process tree did not terminate");
            }
            if (process == stoppingProcess) cleanupStoppedState("Force stopped", stoppingProcess.exitCode());
            if (!cancellingRecovery) {
                automaticRecoveryCancelled = false;
                resetPendingRecoveryForManualStart();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeControllerException("Interrupted while force stopping IBC", ex);
        } catch (IOException ex) {
            throw new RuntimeControllerException("Could not clean runtime state", ex);
        }
    }

    private void runAutomaticRecovery(long generation, ProfileStatus stalledSnapshot) {
        ManagedProcess target;
        synchronized (this) {
            automaticRecoveryThread = Thread.currentThread();
        }
        try {
            captureAutomaticRecoveryDiagnostics(stalledSnapshot);
            synchronized (this) {
                if (!automaticRecoveryTaskActive) return;
                if (automaticRecoveryCancelled) {
                    finishCancelledAutomaticRecovery();
                    return;
                }
                target = process;
                if (!recoveryTargetRemainsStalled(target, generation)) return;
                expectedTermination = ExpectedTermination.AUTO_RECOVERY;
                setRecoveryState(RuntimeState.AUTO_RECOVERY_STOPPING,
                        "Startup is stalled. Automatic recovery is requesting one graceful IBC STOP before "
                                + "force cleanup.");
            }

            requestGracefulStopForAutomaticRecovery();
            boolean stopped = target.waitFor(STALLED_GRACEFUL_STOP_TIMEOUT);
            if (automaticRecoveryCancelled()) {
                finishCancelledAutomaticRecovery();
                return;
            }
            if (!stopped && target.isAlive()) {
                // Login, 2FA, a new engine child, or API readiness may have appeared while
                // STOP was in flight. Never escalate using the pre-wait snapshot.
                if (!recoveryTargetRemainsStalled(target, generation)) return;
                setRecoveryState(RuntimeState.AUTO_RECOVERY_FORCE_CLEANUP,
                        "Graceful STOP did not complete within "
                                + STALLED_GRACEFUL_STOP_TIMEOUT.toSeconds()
                                + " seconds. Terminating only this profile's tracked process tree.");
                if (!terminator.terminate(target, Duration.ofMillis(500), FORCE_TIMEOUT)) {
                    setRecoveryFailed("Automatic recovery could not terminate the stalled managed process tree");
                    return;
                }
            }
            if (target.isAlive()) {
                setRecoveryFailed("Automatic recovery stopped because the old managed process is still alive");
                return;
            }
            cleanupExitedProcessForAutomaticRecovery(target);
            if (automaticRecoveryCancelled()) {
                finishCancelledAutomaticRecovery();
                return;
            }

            setRecoveryState(RuntimeState.AUTO_RECOVERY_FORCE_CLEANUP,
                    "The old process tree exited. Verifying that the API and IBC command ports are released.");
            if (!waitForRecoveryPortsToClose()) {
                if (automaticRecoveryCancelled()) {
                    finishCancelledAutomaticRecovery();
                } else {
                    setRecoveryFailed("Automatic recovery did not start a replacement because the old API or IBC "
                            + "command port remained occupied or could not be inspected for "
                            + RECOVERY_PORT_RELEASE_TIMEOUT.toSeconds() + " seconds");
                }
                return;
            }

            setRecoveryState(RuntimeState.AUTO_RECOVERY_COOLDOWN,
                    "Old processes and ports are clear. Waiting " + RECOVERY_COOLDOWN.toSeconds()
                            + " seconds before a fresh IBC/Gateway start.");
            recoverySleeper.sleep(RECOVERY_COOLDOWN);
            if (automaticRecoveryCancelled()) {
                finishCancelledAutomaticRecovery();
                return;
            }

            setRecoveryState(RuntimeState.STARTING_FRESH,
                    "Launching a completely fresh StartIBC wrapper. A new IBKR Mobile approval may be required.");
            startInternal(StartReason.AUTOMATIC_RECOVERY);
            synchronized (this) {
                automaticRecoveryTaskActive = false;
                automaticRecoveryCancelled = false;
                logBuffer.append("IBC Manager: automatic recovery launched a fresh IBC/Gateway session");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (automaticRecoveryCancelled()) finishCancelledAutomaticRecovery();
            else setRecoveryFailed("Automatic recovery was interrupted");
        } catch (RuntimeControllerException | IOException | RuntimeException ex) {
            setRecoveryFailed("Automatic recovery failed: " + safeMessage(ex));
        } finally {
            synchronized (this) {
                if (automaticRecoveryThread == Thread.currentThread()) automaticRecoveryThread = null;
            }
        }
    }

    /** Revalidate current evidence at each destructive recovery boundary, not just at enqueue time. */
    private synchronized boolean recoveryTargetRemainsStalled(ManagedProcess target, long generation) {
        if (automaticRecoveryCancelled) {
            finishCancelledAutomaticRecovery();
            return false;
        }
        if (target == null || process != target || !target.isAlive()) {
            abortAutomaticRecoveryBecauseProgressResumed();
            return false;
        }
        // Drain the owned process output here even when the regular refresh has not run yet.
        readNewLogLines();
        if (stateParser.sessionGeneration() != generation || !startupStallDetector.isStalled(stateParser)) {
            abandonEscalationForProgress();
            return false;
        }
        portProbe.invalidate();
        ListenerObservation observation = portProbe.observe("127.0.0.1", profile.apiPort(), PORT_TIMEOUT);
        apiListenerObservation = observation;
        if (observation.state() == PortListenerState.UNKNOWN) {
            setRecoveryFailed("Automatic recovery stopped because current API-listener absence could not "
                    + "be verified. No further process termination was performed.");
            return false;
        }
        if (observation.state() == PortListenerState.LISTENING) {
            if (listenerBelongsToManagedTree(observation)) {
                abandonEscalationForProgress();
            } else {
                setRecoveryFailed("Automatic recovery stopped because the API port has an unverified or "
                        + "unrelated listener. No further process termination was performed.");
            }
            return false;
        }
        return true;
    }

    private void abandonEscalationForProgress() {
        boolean stopWasRequested = expectedTermination == ExpectedTermination.AUTO_RECOVERY;
        abortAutomaticRecoveryBecauseProgressResumed();
        if (stopWasRequested) {
            logBuffer.append("IBC Manager: forced cleanup cancelled after new startup progress. "
                    + "The already-sent graceful STOP cannot be recalled and may still complete.");
        }
        refresh();
    }

    private void captureAutomaticRecoveryDiagnostics(ProfileStatus stalledSnapshot) {
        try {
            Path bundle = recoveryDiagnosticCapture.create(profile, stalledSnapshot, logBuffer.snapshot());
            if (bundle != null) {
                logBuffer.append("IBC Manager: automatic-recovery diagnostic bundle created: " + bundle);
            }
        } catch (IOException | RuntimeException ex) {
            logBuffer.append("IBC Manager: automatic recovery could not create a diagnostic bundle: "
                    + safeMessage(ex));
        }
    }

    private void requestGracefulStopForAutomaticRecovery() {
        try {
            Duration timeout = stateParser.commandServerState() == IbcLogStateParser.CommandServerState.OPEN
                    ? COMMAND_TIMEOUT : UNCONFIRMED_STOP_COMMAND_TIMEOUT;
            IbcCommandResult result = commandClient.send(probeHost(profile.bindAddress()),
                    profile.commandServerPort(), IbcCommand.STOP, timeout);
            synchronized (this) { markCommandServerAvailable(true); }
            if (result.success()) {
                logBuffer.append("IBC Manager: automatic recovery sent graceful STOP successfully");
            } else {
                logBuffer.append("IBC Manager: automatic-recovery graceful STOP was rejected: "
                        + result.response());
            }
        } catch (IOException ex) {
            synchronized (this) { markCommandServerAvailable(false); }
            logBuffer.append("IBC Manager: automatic-recovery graceful STOP did not respond: "
                    + safeMessage(ex));
        }
    }

    private synchronized void cleanupExitedProcessForAutomaticRecovery(ManagedProcess target)
            throws IOException {
        if (process == target) {
            cleanupTerminatedState(RuntimeState.AUTO_RECOVERY_FORCE_CLEANUP,
                    "The stalled process tree exited; verifying process and port cleanup", target.exitCode());
        } else if (process != null && process.isAlive()) {
            throw new IOException("A different managed process appeared during automatic recovery");
        }
    }

    private boolean waitForRecoveryPortsToClose() throws InterruptedException {
        int polls = Math.max(1, (int) Math.ceil((double) RECOVERY_PORT_RELEASE_TIMEOUT.toMillis()
                / RECOVERY_PORT_POLL_INTERVAL.toMillis()));
        for (int attempt = 0; attempt <= polls; attempt++) {
            if (automaticRecoveryCancelled()) return false;
            portProbe.invalidate();
            ListenerObservation command = portProbe.observe(probeHost(profile.bindAddress()),
                    profile.commandServerPort(), PORT_TIMEOUT);
            ListenerObservation api = portProbe.observe("127.0.0.1", profile.apiPort(), PORT_TIMEOUT);
            apiListenerObservation = api;
            if (command.state() == PortListenerState.NOT_LISTENING
                    && api.state() == PortListenerState.NOT_LISTENING) {
                return true;
            }
            if (attempt < polls) recoverySleeper.sleep(RECOVERY_PORT_POLL_INTERVAL);
        }
        return false;
    }

    private synchronized void setRecoveryState(RuntimeState state, String message) {
        if (!automaticRecoveryTaskActive) return;
        ManagedProcess current = process;
        boolean alive = current != null && current.isAlive();
        setStatus(state, alive, status.commandPortOpen(), status.apiListenerState(),
                alive ? current.pid() : -1,
                alive ? current.startInstant().orElse(status.startedAt()) : status.startedAt(),
                alive ? null : status.exitCode(), message);
        logBuffer.append("IBC Manager: " + message);
    }

    private synchronized void setRecoveryFailed(String message) {
        automaticRecoveryTaskActive = false;
        automaticRecoveryCancelled = false;
        freshRecoveryStartActive = false;
        expectedTermination = ExpectedTermination.NONE;
        ManagedProcess current = process;
        boolean alive = current != null && current.isAlive();
        setStatus(RuntimeState.RECOVERY_FAILED, alive, alive && status.commandPortOpen(),
                status.apiListenerState(), alive ? current.pid() : -1,
                alive ? current.startInstant().orElse(status.startedAt()) : status.startedAt(),
                alive ? null : status.exitCode(), message);
        logBuffer.append("IBC Manager: " + message);
    }

    private synchronized boolean automaticRecoveryCancelled() {
        return automaticRecoveryCancelled || !automaticRecoveryTaskActive;
    }

    private synchronized void finishCancelledAutomaticRecovery() {
        automaticRecoveryTaskActive = false;
        automaticRecoveryCancelled = false;
        expectedTermination = ExpectedTermination.NONE;
        freshRecoveryStartActive = false;
        recoveryPendingHealth = false;
        try {
            recoveryHistoryStore.resetPendingAttempt(profile.id(), clock.instant());
        } catch (IOException ex) {
            logBuffer.append("IBC Manager: could not clear cancelled automatic-recovery state: "
                    + safeMessage(ex));
        }
        logBuffer.append("IBC Manager: automatic recovery was cancelled by an explicit user action");
    }

    private synchronized void abortAutomaticRecoveryBecauseProgressResumed() {
        automaticRecoveryTaskActive = false;
        automaticRecoveryCancelled = false;
        expectedTermination = ExpectedTermination.NONE;
        recoveryPendingHealth = false;
        try {
            recoveryHistoryStore.resetPendingAttempt(profile.id(), clock.instant());
        } catch (IOException ex) {
            logBuffer.append("IBC Manager: could not clear the no-longer-needed recovery attempt: "
                    + safeMessage(ex));
        }
        logBuffer.append("IBC Manager: automatic recovery was cancelled because startup progress resumed");
    }

    private synchronized void markAutomaticRecoveryHealthy() {
        if (!recoveryPendingHealth && !freshRecoveryStartActive) return;
        recoveryPendingHealth = false;
        freshRecoveryStartActive = false;
        automaticRecoveryGeneration = Long.MIN_VALUE;
        try {
            recoveryHistoryStore.markHealthy(profile.id(), clock.instant());
        } catch (IOException ex) {
            logBuffer.append("IBC Manager: could not persist the successful automatic-recovery state: "
                    + safeMessage(ex));
        }
    }

    private synchronized void resetPendingRecoveryForManualStart() throws RuntimeControllerException {
        try {
            if (recoveryHistoryUnreadable) {
                recoveryHistoryStore.clear(profile.id());
            } else {
                recoveryHistoryStore.resetPendingAttempt(profile.id(), clock.instant());
            }
            recoveryPendingHealth = false;
            recoveryHistoryUnreadable = false;
            recoveryHistoryError = "";
        } catch (IOException ex) {
            throw new RuntimeControllerException("Could not clear the previous automatic-recovery state", ex);
        }
    }

    private static boolean isAutomaticRecoveryState(RuntimeState state) {
        return state == RuntimeState.AUTO_RECOVERY_STOPPING
                || state == RuntimeState.AUTO_RECOVERY_FORCE_CLEANUP
                || state == RuntimeState.AUTO_RECOVERY_COOLDOWN
                || state == RuntimeState.STARTING_FRESH;
    }

    private IbcCommandResult sendCommand(IbcCommand command) throws RuntimeControllerException {
        requireCommandCapability(command);
        return sendCommandUnchecked(command);
    }

    private void requireCommandCapability(IbcCommand command) throws RuntimeControllerException {
        if (command == IbcCommand.RESTART) {
            throw new RuntimeControllerException("Native IBC RESTART is intentionally disabled because its "
                    + "fallback can change the persistent auto-restart schedule; use the Manager's controlled "
                    + "Restart action instead");
        }
        if (!canExecute(command)) {
            throw new RuntimeControllerException(command + " is unavailable until IBC login has completed, "
                    + "the main window is ready, and no stop/pause operation is pending");
        }
    }

    private IbcCommandResult sendCommandUnchecked(IbcCommand command) throws RuntimeControllerException {
        if (process == null || !process.isAlive()) throw new RuntimeControllerException("The profile is not running");
        String host = probeHost(profile.bindAddress());
        try {
            IbcCommandResult result = commandClient.send(host, profile.commandServerPort(), command, COMMAND_TIMEOUT);
            markCommandServerAvailable(true);
            if (!result.success()) throw new RuntimeControllerException("IBC rejected " + command + ": " + result.response());
            return result;
        } catch (IOException ex) {
            markCommandServerAvailable(false);
            throw new RuntimeControllerException("Could not send " + command + " to IBC", ex);
        }
    }

    private synchronized void handleProcessExit() {
        if (process == null || process.isAlive()) return;
        readNewLogLines();
        OptionalInt exitCode = process.exitCode();
        Integer code = exitCode.isPresent() ? exitCode.getAsInt() : null;
        ExpectedTermination termination = expectedTermination;
        try {
            if (termination == ExpectedTermination.PAUSE) {
                if (stateParser.pauseConfirmed()) {
                    cleanupTerminatedState(RuntimeState.PAUSED,
                            "IBC pause was confirmed by the wrapper; start the profile to continue the preserved session",
                            exitCode);
                } else {
                    cleanupTerminatedState(RuntimeState.ERROR,
                            "IBC exited after PAUSE was accepted but the wrapper never confirmed 'IBC is paused'",
                            exitCode);
                }
            } else if (termination == ExpectedTermination.STOP) {
                cleanupStoppedState("Stopped", exitCode);
            } else if (termination == ExpectedTermination.AUTO_RECOVERY) {
                cleanupTerminatedState(RuntimeState.AUTO_RECOVERY_FORCE_CLEANUP,
                        "The stalled process exited during automatic recovery; verifying cleanup", exitCode);
            } else if (freshRecoveryStartActive) {
                String message = "The fresh automatic-recovery start exited before login/API health was restored";
                if (code != null) message += " with code " + code;
                cleanupTerminatedState(RuntimeState.RECOVERY_FAILED, message, exitCode);
            } else if (stateParser.normalExitConfirmed()
                    && !stateParser.errorExitConfirmed() && (code == null || code == 0)) {
                cleanupStoppedState("IBC completed a normal or scheduled shutdown", exitCode);
            } else {
                String message;
                if (stateParser.errorExitConfirmed()) {
                    message = "IBC exited after reporting an error";
                } else if (stateParser.restartPending()) {
                    message = "The StartIBC wrapper exited while an automatic restart was pending";
                } else {
                    message = "IBC exited unexpectedly";
                }
                if (code != null) message += " with code " + code;
                cleanupTerminatedState(RuntimeState.ERROR, message, exitCode);
            }
        } catch (IOException ex) {
            setStatus(RuntimeState.ERROR, false, false, PortListenerState.UNKNOWN, -1, status.startedAt(), code, "IBC exited and cleanup failed: " + ex.getMessage());
        }
    }

    private void cleanupStoppedState(String message, OptionalInt exitCode) throws IOException {
        cleanupTerminatedState(RuntimeState.STOPPED, message, exitCode);
    }

    private void cleanupTerminatedState(RuntimeState finalState, String message, OptionalInt exitCode)
            throws IOException {
        Integer code = exitCode != null && exitCode.isPresent() ? exitCode.getAsInt() : null;
        ManagedProcess oldProcess = process;
        if (configLease != null) {
            configLease.close();
            configLease = null;
        }
        identityStore.delete(profile.id());
        process = null;
        expectedTermination = ExpectedTermination.NONE;
        commandProbeFallbackPending = false;
        if (finalState != RuntimeState.AUTO_RECOVERY_FORCE_CLEANUP) {
            freshRecoveryStartActive = false;
        }
        stateParser.reset();
        startupStallDetector.reset();
        apiListenerObservation = ListenerObservation.unknown(profile.apiPort());
        setStatus(finalState, false, false, PortListenerState.UNKNOWN, -1,
                oldProcess == null ? null : oldProcess.startInstant().orElse(status.startedAt()), code, message);
    }

    private void loadInitialRecoveryState() {
        try {
            RecoveryHistoryStore.State recovery = recoveryHistoryStore.snapshot(profile.id(), clock.instant());
            recoveryPendingHealth = recovery.awaitingHealthy();
            freshRecoveryStartActive = recovery.awaitingHealthy();
        } catch (IOException ex) {
            recoveryHistoryUnreadable = true;
            recoveryHistoryError = safeMessage(ex);
            logBuffer.append("IBC Manager: automatic-recovery history is unreadable and automatic startup is "
                    + "blocked until explicit intervention: " + recoveryHistoryError);
        }
    }

    private void reattachIfPossible() {
        Optional<ManagedProcess> attached = identityStore.reattach(profile.id());
        if (attached.isEmpty()) return;
        process = attached.get();
        expectedTermination = ExpectedTermination.NONE;
        stateParser.reset();
        startupStallDetector.reset();
        commandProbeFallbackPending = true;
        for (Path runtimeConfig : RuntimeConfigLocation.cleanupCandidates(paths, profile)) {
            if (SecureFileOperations.isRegularFile(runtimeConfig)) {
                configLease = new RuntimeConfigLease(runtimeConfig);
                break;
            }
        }
        setStatus(RuntimeState.UNKNOWN, true, false, PortListenerState.UNKNOWN, process.pid(),
                process.startInstant().orElseGet(clock::instant), null, "Reattached to an existing IBC process");
        process.onExit().thenRun(this::handleProcessExit);
    }

    private boolean commandServerOpenForRefresh() {
        IbcLogStateParser.CommandServerState commandState = stateParser.commandServerState();
        if (commandState == IbcLogStateParser.CommandServerState.OPEN) {
            commandProbeFallbackPending = false;
            return true;
        }
        if (commandState == IbcLogStateParser.CommandServerState.CLOSED) {
            commandProbeFallbackPending = false;
            return false;
        }
        if (!commandProbeFallbackPending) return false;

        // A reattached process may have been running long enough that its command-server
        // startup line is outside the bounded log tail. One single fallback probe restores
        // command controls without reintroducing the two-second connection/logging loop.
        commandProbeFallbackPending = false;
        PortListenerState listener = portProbe.inspect(probeHost(profile.bindAddress()),
                profile.commandServerPort(), PORT_TIMEOUT);
        boolean open = listener == PortListenerState.LISTENING;
        if (open) stateParser.markCommandServerOpen();
        else stateParser.markCommandServerClosed();
        if (listener == PortListenerState.UNKNOWN) {
            logBuffer.append("IBC Manager: command-server listener state is unavailable during reattachment");
        }
        return open;
    }

    private void markCommandServerAvailable(boolean available) {
        if (available) stateParser.markCommandServerOpen();
        else stateParser.markCommandServerClosed();
        ProfileStatus current = status;
        setStatus(current.state(), current.processAlive(), available, current.apiListenerState(), current.pid(),
                current.startedAt(), current.exitCode(), current.message());
    }


    public synchronized boolean prepareForManagerExit() {
        if (automaticRecoveryTaskActive) {
            logBuffer.append("IBC Manager: cannot exit while automatic recovery is in progress");
            return false;
        }
        if (configLease == null) return true;
        if (process != null && process.isAlive()) {
            // StartIBC.bat is a long-lived supervisor and may start another IBC JVM after a
            // restart or timeout. Its configuration path must remain valid for the complete
            // wrapper lifetime. The detached process relay removes the ACL-restricted file
            // after the wrapper exits, even when IBC Manager itself is no longer running.
            return true;
        }
        try {
            configLease.close();
            configLease = null;
            return true;
        } catch (IOException ex) {
            logBuffer.append("IBC Manager: could not securely remove the runtime configuration: "
                    + safeMessage(ex));
            return false;
        }
    }

    public synchronized boolean runtimeConfigurationPresent() {
        return configLease != null && SecureFileOperations.exists(configLease.path());
    }

    private void readNewLogLines() {
        try {
            List<String> newLines = process != null && process.hasLiveOutput()
                    ? process.drainOutputLines()
                    : logTailer.readNewLines();
            logBuffer.appendAll(newLines);
            for (String line : newLines) {
                stateParser.accept(line);
                startupStallDetector.observe(stateParser);
            }
        } catch (IOException ex) {
            logBuffer.append("IBC Manager: could not read log: " + ex.getMessage());
        }
    }

    private String sessionHeader(String displayCommand) {
        return System.lineSeparator()
                + "===== IBC Manager session " + clock.instant() + " =====" + System.lineSeparator()
                + "Profile: " + profile.name() + System.lineSeparator()
                + "Launch: " + displayCommand + System.lineSeparator();
    }

    private void setStatus(RuntimeState state, boolean processAlive, boolean commandOpen,
            PortListenerState apiListenerState, long pid, Instant startedAt, Integer exitCode, String message) {
        status = new ProfileStatus(profile.id(), state, processAlive, commandOpen, apiListenerState, pid,
                startedAt, exitCode, message, clock.instant());
        for (Consumer<ProfileStatus> listener : listeners) {
            try {
                listener.accept(status);
            } catch (RuntimeException ex) {
                logBuffer.append("IBC Manager: status listener failed: " + ex.getClass().getSimpleName());
            }
        }
    }


    private boolean listenerBelongsToManagedTree(ListenerObservation observation) {
        if (observation.state() != PortListenerState.LISTENING
                || !observation.ownershipAvailable() || process == null || !process.isAlive()) {
            return false;
        }
        long pid = observation.owningPid();
        if (pid == process.pid()) return true;
        try {
            return process.descendants().stream().anyMatch(handle -> handle.pid() == pid && handle.isAlive());
        } catch (RuntimeException ex) {
            logBuffer.append("IBC Manager: could not verify API-listener process ownership: " + safeMessage(ex));
            return false;
        }
    }

    private static String listenerDetails(ListenerObservation observation) {
        String address = observation.localAddress().isBlank() ? "" : " on " + observation.localAddress()
                + ":" + observation.port();
        String pid = observation.ownershipAvailable() ? " (PID " + observation.owningPid() + ")" : "";
        return address + pid;
    }

    private static String probeHost(String bindAddress) {
        if (bindAddress == null || bindAddress.isBlank() || bindAddress.equals("0.0.0.0") || bindAddress.equals("::")) {
            return "127.0.0.1";
        }
        return bindAddress;
    }

    private RuntimeException cleanupFailedStart(ManagedProcess launchedProcess, RuntimeConfigLease newLease) {
        RuntimeException failure = null;
        if (launchedProcess != null && launchedProcess.isAlive()) {
            try {
                if (!terminator.terminate(launchedProcess, Duration.ofSeconds(1), FORCE_TIMEOUT)) {
                    failure = new IllegalStateException("Failed to terminate the partially started IBC process");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                failure = new IllegalStateException("Interrupted while terminating a partially started IBC process", ex);
            } catch (RuntimeException ex) {
                failure = ex;
            }
        }
        process = null;
        configLease = null;
        expectedTermination = ExpectedTermination.NONE;
        commandProbeFallbackPending = false;
        stateParser.reset();
        startupStallDetector.reset();
        apiListenerObservation = ListenerObservation.unknown(profile.apiPort());
        try {
            identityStore.delete(profile.id());
        } catch (IOException ex) {
            if (failure == null) failure = new IllegalStateException("Could not remove the stale process identity", ex);
            else failure.addSuppressed(ex);
        }
        if (newLease != null) {
            try {
                newLease.close();
            } catch (IOException ex) {
                if (failure == null) failure = new IllegalStateException("Could not remove the temporary runtime configuration", ex);
                else failure.addSuppressed(ex);
            }
        }
        return failure;
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null) return "Unknown error";
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
