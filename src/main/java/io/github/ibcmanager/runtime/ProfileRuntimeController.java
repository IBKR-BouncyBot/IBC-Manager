package io.github.ibcmanager.runtime;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.config.RuntimeConfigLease;
import io.github.ibcmanager.config.RuntimeConfigProvider;
import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.ProfileStatus;
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
    private static final Duration PORT_TIMEOUT = Duration.ofMillis(350);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration UNCONFIRMED_STOP_COMMAND_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration FORCE_TIMEOUT = Duration.ofSeconds(5);

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

    public void addStatusListener(Consumer<ProfileStatus> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeStatusListener(Consumer<ProfileStatus> listener) {
        listeners.remove(listener);
    }

    public synchronized void start() throws RuntimeControllerException {
        if (process != null && process.isAlive()) return;
        setStatus(RuntimeState.VALIDATING, false, false, false, -1, null, null, "Validating profile");
        ValidationResult validation = validator.validate(profile, true);
        List<String> errors = validation.issues().stream()
                .filter(issue -> issue.severity() == Severity.ERROR)
                .map(issue -> issue.field() + ": " + issue.message())
                .toList();
        if (!errors.isEmpty()) {
            setStatus(RuntimeState.ERROR, false, false, false, -1, null, null, errors.get(0));
            throw new RuntimeControllerException(String.join(System.lineSeparator(), errors));
        }

        String commandHost = probeHost(profile.bindAddress());
        if (portProbe.isOpen(commandHost, profile.commandServerPort(), PORT_TIMEOUT)) {
            String message = "IBC command-server port " + profile.commandServerPort()
                    + " is already in use on " + commandHost;
            setStatus(RuntimeState.ERROR, false, true, false, -1, null, null, message);
            throw new RuntimeControllerException(message);
        }
        if (portProbe.isOpen("127.0.0.1", profile.apiPort(), PORT_TIMEOUT)) {
            String message = "TWS/Gateway API port " + profile.apiPort() + " is already in use on 127.0.0.1";
            setStatus(RuntimeState.ERROR, false, false, true, -1, null, null, message);
            throw new RuntimeControllerException(message);
        }

        RuntimeConfigLease newLease = null;
        ManagedProcess launchedProcess = null;
        SecureChars secret = null;
        try {
            runtimeConfigProvider.cleanStale(profile);
            if (profile.credentialMode() == CredentialMode.ENCRYPTED) secret = credentialStore.load(profile.id());
            newLease = runtimeConfigProvider.create(profile, secret);
            LaunchSpec launchSpec = launchSpecFactory.create(profile, newLease.path());
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
            setStatus(RuntimeState.STARTING, true, false, false, launchedProcess.pid(), startedAt, null, "IBC process started");
            launchedProcess.onExit().thenRun(this::handleProcessExit);
        } catch (CredentialStoreException | IOException | RuntimeException ex) {
            RuntimeException cleanupFailure = cleanupFailedStart(launchedProcess, newLease);
            if (cleanupFailure != null) ex.addSuppressed(cleanupFailure);
            setStatus(RuntimeState.ERROR, false, false, false, -1, null, null, safeMessage(ex));
            throw new RuntimeControllerException("Could not start profile '" + profile.name() + "'", ex);
        } finally {
            if (secret != null) secret.close();
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
        boolean apiOpen = portProbe.isOpen("127.0.0.1", profile.apiPort(), PORT_TIMEOUT);
        Optional<IbcLogStateParser.StateHint> hint = stateParser.latest();
        if (apiOpen || hint.map(value -> value.state() == RuntimeState.WAITING_FOR_SECOND_FACTOR
                || value.state() == RuntimeState.RUNNING || value.state() == RuntimeState.PAUSED).orElse(false)) {
            scrubRuntimeConfigAfterAuthentication();
        }
        RuntimeState nextState;
        String message;

        if (hint.isPresent() && hint.get().state() == RuntimeState.ERROR) {
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
        } else if (apiOpen) {
            nextState = RuntimeState.API_SOCKET_OPEN;
            message = "API socket is accepting TCP connections; IB API handshake is not verified";
        } else if (hint.isPresent() && hint.get().state() == RuntimeState.RUNNING) {
            nextState = RuntimeState.RUNNING;
            message = "IBC login completed; API socket is not currently open";
        } else if (hint.isPresent() && hint.get().state() == RuntimeState.STOPPED) {
            // IBC reported that the IBKR application exited while our process handle is still
            // alive. Neither STARTING nor STOPPED is truthful here, so report it as unknown.
            nextState = RuntimeState.UNKNOWN;
            message = hint.get().message();
        } else if (commandOpen) {
            nextState = RuntimeState.STARTING;
            message = "IBC command server is available; waiting for login completion";
        } else {
            nextState = RuntimeState.STARTING;
            message = hint.map(IbcLogStateParser.StateHint::message).orElse("IBC process is starting");
        }
        setStatus(nextState, true, commandOpen, apiOpen, process.pid(),
                process.startInstant().orElse(status.startedAt()), null, message);
    }

    public synchronized IbcCommandResult restartSession() throws RuntimeControllerException {
        IbcCommandResult result = sendCommand(IbcCommand.RESTART);
        if (result.success()) {
            // A restart invalidates every log hint from the previous session, including a
            // PAUSED hint that would otherwise be re-applied by the next refresh.
            stateParser.resetSessionState();
            expectedTermination = ExpectedTermination.NONE;
            setStatus(RuntimeState.STARTING, true, true, false, process.pid(), status.startedAt(), null, "IBC restart requested");
        }
        return result;
    }

    public synchronized IbcCommandResult pause() throws RuntimeControllerException {
        IbcCommandResult result = sendCommand(IbcCommand.PAUSE);
        if (result.success()) {
            expectedTermination = ExpectedTermination.PAUSE;
            setStatus(RuntimeState.PAUSED, true, true, false, process.pid(), status.startedAt(), null,
                    "IBC pause requested; waiting for Gateway/TWS to exit");
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
        setStatus(RuntimeState.STOPPING, true, status.commandPortOpen(), status.apiPortOpen(), stoppingProcess.pid(), status.startedAt(), null, "Stopping through the IBC command server");
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
                logBuffer.append("IBC Manager: graceful stop timed out; terminating only this profile's process tree");
                stopped = terminator.terminate(stoppingProcess, Duration.ofSeconds(3), FORCE_TIMEOUT);
            }
            if (!stopped) throw new RuntimeControllerException("The IBC process did not terminate after a forced stop");
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
                cleanupTerminatedState(RuntimeState.PAUSED,
                        "IBC paused; start the profile to continue the preserved session", exitCode);
            } else {
                cleanupStoppedState(termination == ExpectedTermination.STOP
                        ? "Stopped" : "IBC exited unexpectedly", exitCode);
                if (termination == ExpectedTermination.NONE) {
                    setStatus(RuntimeState.ERROR, false, false, false, -1, status.startedAt(), code,
                            "IBC exited unexpectedly" + (code == null ? "" : " with code " + code));
                }
            }
        } catch (IOException ex) {
            setStatus(RuntimeState.ERROR, false, false, false, -1, status.startedAt(), code, "IBC exited and cleanup failed: " + ex.getMessage());
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
        setStatus(finalState, false, false, false, -1,
                oldProcess == null ? null : oldProcess.startInstant().orElse(status.startedAt()), code, message);
    }

    private void reattachIfPossible() {
        Optional<ManagedProcess> attached = identityStore.reattach(profile.id());
        if (attached.isEmpty()) return;
        process = attached.get();
        expectedTermination = ExpectedTermination.NONE;
        stateParser.reset();
        commandProbeFallbackPending = true;
        Path runtimeConfig = paths.runtimeDirectory(profile.id()).resolve("config.ini");
        if (SecureFileOperations.isRegularFile(runtimeConfig)) configLease = new RuntimeConfigLease(runtimeConfig);
        setStatus(RuntimeState.UNKNOWN, true, false, false, process.pid(),
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
        boolean open = portProbe.isOpen(probeHost(profile.bindAddress()),
                profile.commandServerPort(), PORT_TIMEOUT);
        if (open) stateParser.markCommandServerOpen();
        else stateParser.markCommandServerClosed();
        return open;
    }

    private void markCommandServerAvailable(boolean available) {
        if (available) stateParser.markCommandServerOpen();
        else stateParser.markCommandServerClosed();
        ProfileStatus current = status;
        setStatus(current.state(), current.processAlive(), available, current.apiPortOpen(), current.pid(),
                current.startedAt(), current.exitCode(), current.message());
    }


    public synchronized boolean prepareForManagerExit() {
        if (configLease == null) return true;
        if (process != null && process.isAlive() && profile.credentialMode() == CredentialMode.MANUAL
                && status.state() != RuntimeState.WAITING_FOR_SECOND_FACTOR
                && status.state() != RuntimeState.RUNNING
                && status.state() != RuntimeState.API_SOCKET_OPEN
                && status.state() != RuntimeState.PAUSED) {
            // The manual-mode runtime file contains no password. Leave it in place so a just-started
            // IBC process can still finish reading it after the manager exits; the next start cleans it.
            return true;
        }
        if (process == null || !process.isAlive()
                || status.state() == RuntimeState.WAITING_FOR_SECOND_FACTOR
                || status.state() == RuntimeState.RUNNING
                || status.state() == RuntimeState.API_SOCKET_OPEN
                || status.state() == RuntimeState.PAUSED) {
            try {
                configLease.close();
                configLease = null;
                return true;
            } catch (IOException ex) {
                logBuffer.append("IBC Manager: could not securely remove the temporary runtime configuration: "
                        + safeMessage(ex));
                return false;
            }
        }
        return false;
    }

    public synchronized boolean runtimeConfigurationPresent() {
        return configLease != null && SecureFileOperations.exists(configLease.path());
    }

    private void scrubRuntimeConfigAfterAuthentication() {
        if (configLease == null) return;
        Path path = configLease.path();
        try {
            configLease.close();
            configLease = null;
            logBuffer.append("IBC Manager: removed temporary runtime configuration after authentication progressed: " + path.getFileName());
        } catch (IOException ex) {
            logBuffer.append("IBC Manager: could not remove the temporary runtime configuration: " + safeMessage(ex));
        }
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

    private void setStatus(RuntimeState state, boolean processAlive, boolean commandOpen, boolean apiOpen,
            long pid, Instant startedAt, Integer exitCode, String message) {
        status = new ProfileStatus(profile.id(), state, processAlive, commandOpen, apiOpen, pid,
                startedAt, exitCode, message, clock.instant());
        for (Consumer<ProfileStatus> listener : listeners) {
            try {
                listener.accept(status);
            } catch (RuntimeException ex) {
                logBuffer.append("IBC Manager: status listener failed: " + ex.getClass().getSimpleName());
            }
        }
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
