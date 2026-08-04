package io.github.ibcmanager.runtime;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.config.RuntimeConfigLease;
import io.github.ibcmanager.config.RuntimeConfigLocation;
import io.github.ibcmanager.config.RuntimeConfigProvider;
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
import java.util.function.Consumer;

public final class ProfileRuntimeController {
    private static final Duration PORT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration UNCONFIRMED_STOP_COMMAND_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration FORCE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration START_COORDINATION_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration START_MILESTONE_TIMEOUT = Duration.ofSeconds(60);

    private enum ExpectedTermination {
        NONE,
        STOP,
        PAUSE
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
    private final RuntimeLogBuffer logBuffer = new RuntimeLogBuffer(5000);
    private final IbcLogStateParser stateParser = new IbcLogStateParser();
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
        this.startCoordinator = Objects.requireNonNull(startCoordinator, "startCoordinator");
        this.status = ProfileStatus.stopped(profile.id());
        this.logTailer = new LogTailer(paths.profileLog(profile.id()));
        reattachIfPossible();
    }

    public Profile profile() {
        return profile;
    }

    public synchronized void updateProfile(Profile updated) {
        Objects.requireNonNull(updated, "updated");
        if (!updated.id().equals(profile.id())) throw new IllegalArgumentException("Cannot replace a controller with a different profile ID");
        if (process != null && process.isAlive()) throw new IllegalStateException("Stop the profile before changing its configuration");
        profile = updated;
    }

    public ProfileStatus status() {
        return status;
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

    public synchronized void start() throws RuntimeControllerException {
        if (process != null && process.isAlive()) return;
        setStatus(RuntimeState.VALIDATING, false, false, PortListenerState.UNKNOWN, -1, null, null, "Validating profile");
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
            logTailer = new LogTailer(logFile);
            Instant startedAt = launchedProcess.startInstant().orElseGet(clock::instant);
            setStatus(RuntimeState.STARTING, true, false, PortListenerState.NOT_LISTENING, launchedProcess.pid(), startedAt, null, "IBC process started");
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
            message = hint.get().message();
        } else if (hint.isPresent() && hint.get().state() == RuntimeState.WAITING_FOR_LOGIN) {
            nextState = RuntimeState.WAITING_FOR_LOGIN;
            message = hint.get().message();
        } else if (hint.isPresent() && hint.get().state() == RuntimeState.PAUSED) {
            // PAUSE is an explicit shutdown request. During that transition, a socket can remain
            // observable briefly, so the pause hint must outrank the probe result.
            nextState = RuntimeState.PAUSED;
            message = hint.get().message();
        } else if (hint.isPresent() && hint.get().state() == RuntimeState.STOPPED) {
            // StartIBC.bat has confirmed a normal/scheduled exit and is finishing its own cleanup.
            // The wrapper handle and old API listener can remain alive very briefly, so show a
            // yellow transition until handleProcessExit() records the final STOPPED state.
            nextState = RuntimeState.STOPPING;
            message = hint.get().message() + "; waiting for the wrapper process to exit";
        } else if (hint.isPresent() && hint.get().state() == RuntimeState.UNKNOWN) {
            // Preserve a deliberate child-exit/error observation while the wrapper decides whether
            // to restart. A listener from the exiting child and older command-server readiness must
            // not turn this transition back into misleading green or STARTING status.
            nextState = RuntimeState.UNKNOWN;
            message = hint.get().message();
        } else if (apiListening) {
            nextState = RuntimeState.API_LISTENER_DETECTED;
            message = "The operating system reports an API TCP listener owned by the managed process tree"
                    + listenerDetails(observedApi) + "; IB API handshake is not verified";
        } else if (observedApi.state() == PortListenerState.LISTENING) {
            nextState = RuntimeState.UNKNOWN;
            message = observedApi.ownershipAvailable()
                    ? "An API listener exists" + listenerDetails(observedApi)
                            + " but its PID does not belong to the managed IBC/Gateway process tree"
                    : "An API listener exists" + listenerDetails(observedApi)
                            + " but this operating system did not expose enough ownership data to verify it";
        } else if (hint.isPresent() && hint.get().state() == RuntimeState.RUNNING) {
            nextState = RuntimeState.RUNNING;
            message = apiListener == PortListenerState.UNKNOWN
                    ? "IBC login completed; API TCP listener state is temporarily unavailable"
                    : "IBC login completed; API TCP listener is not currently present";
        } else if (commandOpen) {
            nextState = RuntimeState.STARTING;
            message = "IBC command server is available; waiting for login completion";
        } else if (apiListener == PortListenerState.UNKNOWN) {
            nextState = RuntimeState.UNKNOWN;
            message = "Could not inspect the local API TCP listener state";
        } else {
            nextState = RuntimeState.STARTING;
            message = hint.map(IbcLogStateParser.StateHint::message).orElse("IBC process is starting");
        }
        setStatus(nextState, true, commandOpen, apiListener, process.pid(),
                process.startInstant().orElse(status.startedAt()), null, message);
    }

    public synchronized boolean canExecute(IbcCommand command) {
        Objects.requireNonNull(command, "command");
        if (process == null || !process.isAlive()) return false;
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
        if (process == null || !process.isAlive()) {
            try {
                cleanupStoppedState("Already stopped", null);
                return;
            } catch (IOException ex) {
                throw new RuntimeControllerException("Could not clean stale runtime state", ex);
            }
        }
        expectedTermination = ExpectedTermination.STOP;
        ManagedProcess stoppingProcess = process;
        setStatus(RuntimeState.STOPPING, true, status.commandPortOpen(), status.apiListenerState(), stoppingProcess.pid(), status.startedAt(), null, "Stopping through the IBC command server");
        try {
            try {
                Duration timeout = stateParser.commandServerState() == IbcLogStateParser.CommandServerState.OPEN
                        ? COMMAND_TIMEOUT : UNCONFIRMED_STOP_COMMAND_TIMEOUT;
                IbcCommandResult result = commandClient.send(probeHost(profile.bindAddress()),
                        profile.commandServerPort(), IbcCommand.STOP, timeout);
                markCommandServerAvailable(true);
                if (!result.success()) {
                    logBuffer.append("IBC Manager: graceful STOP command was rejected: " + result.response());
                }
            } catch (IOException ex) {
                markCommandServerAvailable(false);
                logBuffer.append("IBC Manager: graceful STOP command failed: " + safeMessage(ex));
            }
            boolean stopped = stoppingProcess.waitFor(Duration.ofSeconds(profile.gracefulStopTimeoutSeconds()));
            if (!stopped) {
                String message = "Graceful stop timed out after " + profile.gracefulStopTimeoutSeconds()
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

    public synchronized void forceStop() throws RuntimeControllerException {
        if (process == null || !process.isAlive()) {
            try {
                cleanupStoppedState("Already stopped", null);
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
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeControllerException("Interrupted while force stopping IBC", ex);
        } catch (IOException ex) {
            throw new RuntimeControllerException("Could not clean runtime state", ex);
        }
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
        stateParser.reset();
        apiListenerObservation = ListenerObservation.unknown(profile.apiPort());
        setStatus(finalState, false, false, PortListenerState.UNKNOWN, -1,
                oldProcess == null ? null : oldProcess.startInstant().orElse(status.startedAt()), code, message);
    }

    private void reattachIfPossible() {
        Optional<ManagedProcess> attached = identityStore.reattach(profile.id());
        if (attached.isEmpty()) return;
        process = attached.get();
        expectedTermination = ExpectedTermination.NONE;
        stateParser.reset();
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
            for (String line : newLines) stateParser.accept(line);
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
