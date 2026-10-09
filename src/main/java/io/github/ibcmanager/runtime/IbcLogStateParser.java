package io.github.ibcmanager.runtime;

import io.github.ibcmanager.model.RuntimeState;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Set;

/** Interprets the lifecycle messages emitted by supported IBC releases and StartIBC.bat. */
public final class IbcLogStateParser {
    public enum CommandServerState {
        UNKNOWN,
        STARTING,
        OPEN,
        CLOSED
    }

    private StateHint latest;
    private CommandServerState commandServerState = CommandServerState.UNKNOWN;
    private boolean loginCompleted;
    private boolean pauseConfirmed;
    private boolean launchCommandObserved;
    private boolean applicationLaunchObserved;
    private boolean childExitObserved;
    private boolean restartPending;
    private boolean normalExitConfirmed;
    private boolean errorExitConfirmed;
    private long sessionGeneration;
    private String engineGeneration;
    private long engineSequence;
    private boolean mainWindowObserved;
    private final Set<String> retiredEngineGenerations = new LinkedHashSet<>();
    private static final String EVENT_PREFIX = "IBC_MANAGER_EVENT|1|";
    private static final Set<String> EVENTS = Set.of("ENGINE_STARTED", "GATEWAY_STARTING", "MAIN_WINDOW_READY",
            "LOGIN_LOGGED_OUT", "LOGIN_LOGGING_IN", "LOGIN_AWAITING_CREDENTIALS", "LOGIN_TWO_FA_IN_PROGRESS",
            "LOGIN_LOGGED_IN", "LOGIN_LOGIN_FAILED", "COMMAND_SERVER_READY", "COMMAND_SERVER_CLOSED",
            "SECOND_FACTOR_RETRY_ARMED", "SECOND_FACTOR_RETRY_DUE",
            "SECOND_FACTOR_RETRY_STARTED", "SECOND_FACTOR_RETRY_BLOCKED");

    public synchronized Optional<StateHint> accept(String line) {
        if (line == null) return Optional.empty();
        if (line.startsWith("IBC_MANAGER_EVENT|")) return acceptEngineEvent(line);
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("===== ibc manager session ")) {
            reset();
            return Optional.empty();
        }
        if (lower.contains("starting ibc with this command:")) {
            // StartIBC.bat emits this line before every IBC JVM it launches, including
            // automatic restart and timeout recovery. Never carry login, terminal-state,
            // or command-server readiness from the previous JVM into the replacement process.
            clearChildSessionForLaunch();
            launchCommandObserved = true;
            sessionGeneration++;
            return Optional.empty();
        }

        updateCommandServerState(lower);
        StateHint hint = null;
        if (isRestartDecision(lower)) {
            restartPending = true;
            childExitObserved = true;
            normalExitConfirmed = false;
            errorExitConfirmed = false;
            hint = hint(RuntimeState.RESTARTING, restartMessage(lower));
        } else if (containsAny(lower,
                "second factor authentication initiated",
                "detected dialog entitled: second factor authentication")) {
            hint = hint(RuntimeState.WAITING_FOR_SECOND_FACTOR,
                    "Waiting for second-factor authentication");
        } else if (lower.contains("login has completed")) {
            loginCompleted = true;
            hint = hint(RuntimeState.RUNNING, "IBC login completed");
        } else if (containsAny(lower,
                "login dialog window_opened", "setting user name", "login attempt:")) {
            hint = hint(RuntimeState.WAITING_FOR_LOGIN, "IBC is processing the login window");
        } else if (containsAny(lower,
                "ibc: starting gateway", "ibc: starting tws",
                "starting gateway with this command", "starting tws with this command")) {
            applicationLaunchObserved = true;
            hint = hint(RuntimeState.STARTING, "Starting IBC and IBKR application");
        } else if (lower.contains("starting ibc version")) {
            hint = hint(RuntimeState.STARTING, "Starting IBC");
        } else if (lower.contains("ibc is paused")) {
            pauseConfirmed = true;
            hint = hint(RuntimeState.PAUSED, "IBC session paused");
        } else if (isNormalExitMarker(lower)) {
            normalExitConfirmed = true;
            restartPending = false;
            hint = errorExitConfirmed
                    ? hint(RuntimeState.UNKNOWN,
                            "StartIBC is exiting after an IBC-reported error")
                    : hint(RuntimeState.STOPPED,
                            "IBC wrapper reported a normal or scheduled shutdown");
        } else if (isApplicationFinishedMarker(lower)) {
            normalExitConfirmed = true;
            restartPending = false;
            hint = errorExitConfirmed
                    ? hint(RuntimeState.UNKNOWN,
                            "StartIBC completed after an IBC-reported error")
                    : hint(RuntimeState.STOPPED,
                            "IBC wrapper completed a normal or scheduled shutdown");
        } else if (lower.contains("program has exited")) {
            childExitObserved = true;
            hint = restartPending
                    ? hint(RuntimeState.RESTARTING, "IBC is restarting")
                    : hint(RuntimeState.UNKNOWN,
                            "IBC child process exited; waiting for the StartIBC wrapper decision");
        } else if (containsAny(lower,
                "exiting after error with exit code=",
                "exiting with exit code=")) {
            // IBC has reported why the child JVM is exiting, but StartIBC.bat may still decide
            // to launch a replacement JVM. Keep the UI non-red until the wrapper either announces
            // a restart or exits itself. handleProcessExit() promotes this to ERROR if no restart
            // occurs.
            errorExitConfirmed = true;
            childExitObserved = true;
            hint = hint(RuntimeState.UNKNOWN,
                    "IBC child process reported an error; waiting for the StartIBC wrapper decision");
        } else if (containsAny(lower,
                "an error has occurred",
                "login failed",
                "too many failed login attempts",
                "could not login:")) {
            // These messages come from the child IBC JVM. StartIBC.bat can still recover by
            // launching another JVM, so do not flash a terminal red state before the wrapper has
            // made its restart-or-exit decision.
            errorExitConfirmed = true;
            hint = hint(RuntimeState.UNKNOWN,
                    "IBC reported an error; waiting for the StartIBC wrapper decision");
        } else if (containsAny(lower,
                "can't find suitable java installation",
                "offline tws/gateway version")) {
            // These are wrapper-level launch failures rather than recoverable child-JVM exits.
            errorExitConfirmed = true;
            hint = hint(RuntimeState.ERROR, line.trim());
        }
        if (hint != null) latest = hint;
        return Optional.ofNullable(hint);
    }

    public synchronized Optional<StateHint> latest() {
        return Optional.ofNullable(latest);
    }

    public synchronized CommandServerState commandServerState() {
        return commandServerState;
    }

    public synchronized void markCommandServerOpen() {
        commandServerState = CommandServerState.OPEN;
    }

    public synchronized void markCommandServerClosed() {
        commandServerState = CommandServerState.CLOSED;
    }

    public synchronized boolean loginCompleted() { return loginCompleted; }

    /**
     * IBC emits "Login has completed" only after its LoginManager reaches LOGGED_IN. For the
     * supported IBC integration surface this is the command-safety boundary used for operations that
     * dereference the TWS/Gateway main window, such as RECONNECTDATA.
     */
    public synchronized boolean mainWindowReady() {
        return loginCompleted && (engineGeneration == null || mainWindowObserved);
    }

    public synchronized boolean pauseConfirmed() { return pauseConfirmed; }
    public synchronized boolean launchCommandObserved() { return launchCommandObserved; }
    public synchronized boolean applicationLaunchObserved() { return applicationLaunchObserved; }
    public synchronized boolean childExitObserved() { return childExitObserved; }
    public synchronized boolean restartPending() { return restartPending; }
    public synchronized boolean normalExitConfirmed() { return normalExitConfirmed; }
    public synchronized boolean errorExitConfirmed() { return errorExitConfirmed; }
    public synchronized long sessionGeneration() { return sessionGeneration; }

    public synchronized void resetSessionState() {
        retireEngineGeneration();
        latest = null;
        loginCompleted = false;
        pauseConfirmed = false;
        launchCommandObserved = false;
        applicationLaunchObserved = false;
        childExitObserved = false;
        restartPending = false;
        normalExitConfirmed = false;
        errorExitConfirmed = false;
    }

    public synchronized void reset() {
        engineGeneration = null;
        engineSequence = 0;
        mainWindowObserved = false;
        retiredEngineGenerations.clear();
        latest = null;
        commandServerState = CommandServerState.UNKNOWN;
        loginCompleted = false;
        pauseConfirmed = false;
        launchCommandObserved = false;
        applicationLaunchObserved = false;
        childExitObserved = false;
        restartPending = false;
        normalExitConfirmed = false;
        errorExitConfirmed = false;
        sessionGeneration = 0;
    }

    private void clearChildSessionForLaunch() {
        retireEngineGeneration();
        latest = null;
        commandServerState = CommandServerState.UNKNOWN;
        loginCompleted = false;
        pauseConfirmed = false;
        applicationLaunchObserved = false;
        childExitObserved = false;
        restartPending = false;
        normalExitConfirmed = false;
        errorExitConfirmed = false;
    }

    private void retireEngineGeneration() {
        if (engineGeneration != null) retiredEngineGenerations.add(engineGeneration);
        if (retiredEngineGenerations.size() > 128) {
            retiredEngineGenerations.remove(retiredEngineGenerations.iterator().next());
        }
        engineGeneration = null;
        engineSequence = 0;
        mainWindowObserved = false;
    }

    private Optional<StateHint> acceptEngineEvent(String line) {
        if (!line.startsWith(EVENT_PREFIX) || line.length() > 192) return Optional.empty();
        String[] fields = line.substring(EVENT_PREFIX.length()).split("\\|", -1);
        if (fields.length != 3 || !EVENTS.contains(fields[2])) return Optional.empty();
        try {
            if (!UUID.fromString(fields[0]).toString().equals(fields[0])) return Optional.empty();
            long sequence = Long.parseLong(fields[1]);
            if (sequence <= 0 || retiredEngineGenerations.contains(fields[0])) return Optional.empty();
            if (fields[2].equals("ENGINE_STARTED")) {
                if (engineGeneration != null || sequence != 1) return Optional.empty();
                engineGeneration = fields[0];
                if (!launchCommandObserved) sessionGeneration++;
                launchCommandObserved = true;
            } else if (!fields[0].equals(engineGeneration)) {
                return Optional.empty();
            }
            if (sequence <= engineSequence) return Optional.empty();
            engineSequence = sequence;
        } catch (IllegalArgumentException ignored) { return Optional.empty(); }
        StateHint state = switch (fields[2]) {
            case "ENGINE_STARTED" -> hint(RuntimeState.STARTING, "Integrated IBC engine started");
            case "GATEWAY_STARTING" -> {
                applicationLaunchObserved = true;
                yield hint(RuntimeState.STARTING, "Starting IB Gateway");
            }
            case "MAIN_WINDOW_READY" -> {
                mainWindowObserved = true;
                yield null;
            }
            case "LOGIN_LOGGING_IN", "LOGIN_AWAITING_CREDENTIALS", "LOGIN_LOGGED_OUT" -> {
                loginCompleted = false;
                yield hint(RuntimeState.WAITING_FOR_LOGIN, "Gateway login in progress");
            }
            case "LOGIN_TWO_FA_IN_PROGRESS" -> hint(RuntimeState.WAITING_FOR_SECOND_FACTOR,
                    "Waiting for second-factor authentication");
            case "SECOND_FACTOR_RETRY_ARMED" -> loginCompleted ? null
                    : hint(RuntimeState.WAITING_FOR_SECOND_FACTOR, "Waiting for 2FA; independent retry timer is armed");
            case "SECOND_FACTOR_RETRY_DUE" -> loginCompleted ? null
                    : hint(RuntimeState.WAITING_FOR_SECOND_FACTOR, "2FA deadline reached; retrying login inside Gateway");
            case "SECOND_FACTOR_RETRY_BLOCKED" -> loginCompleted ? null
                    : hint(RuntimeState.WAITING_FOR_SECOND_FACTOR,
                            "Automatic 2FA retry blocked: check stored credentials and the Gateway login/Cancel controls");
            // The actual login-state transitions remain authoritative after submission.
            case "SECOND_FACTOR_RETRY_STARTED" -> null;
            case "LOGIN_LOGGED_IN" -> {
                loginCompleted = true;
                yield hint(RuntimeState.RUNNING, "Integrated engine reports login completed");
            }
            case "LOGIN_LOGIN_FAILED" -> {
                loginCompleted = false;
                errorExitConfirmed = true;
                yield hint(RuntimeState.UNKNOWN, "Gateway login failed; waiting for wrapper decision");
            }
            case "COMMAND_SERVER_READY" -> {
                commandServerState = CommandServerState.OPEN;
                yield null;
            }
            case "COMMAND_SERVER_CLOSED" -> {
                commandServerState = CommandServerState.CLOSED;
                yield null;
            }
            default -> null;
        };
        if (state != null) latest = state;
        return Optional.ofNullable(state);
    }

    private void updateCommandServerState(String lower) {
        if (containsAny(lower,
                "commandserver started and is ready to accept commands",
                "commandserver started and is ready to accept connections",
                "commandserver listening on address:",
                "commandserver listening on addresses:",
                "commandserver accepted connection from:")) {
            commandServerState = CommandServerState.OPEN;
        } else if (lower.contains("commandserver is starting with port")) {
            commandServerState = CommandServerState.STARTING;
        } else if (containsAny(lower,
                "commandserver is not started because the port is not configured",
                "commandserver failed to create socket",
                "commandserver cannot process commands",
                "commandserver closing",
                "commandserver is shutdown")) {
            commandServerState = CommandServerState.CLOSED;
        }
    }

    private static boolean isNormalExitMarker(String lower) {
        return lower.trim().equals("normal exit");
    }

    private static boolean isApplicationFinishedMarker(String lower) {
        String trimmed = lower.trim();
        return trimmed.startsWith("gateway finished at ") || trimmed.startsWith("tws finished at ");
    }

    private static boolean isRestartDecision(String lower) {
        return containsAny(lower,
                "ibc will autorestart shortly",
                "ibc will cold-restart shortly",
                "ibc will restart shortly due to 2fa completion timeout",
                "ibc will restart shortly due to login dialog display timeout");
    }

    private static String restartMessage(String lower) {
        if (lower.contains("cold-restart")) return "IBC is performing a cold restart";
        if (lower.contains("2fa completion timeout")) {
            return "IBC is restarting after a second-factor authentication timeout";
        }
        if (lower.contains("login dialog display timeout")) {
            return "IBC is restarting after a login-dialog timeout";
        }
        return "IBC is performing an automatic restart";
    }

    private static boolean containsAny(String value, String... patterns) {
        for (String pattern : patterns) if (value.contains(pattern)) return true;
        return false;
    }

    private static StateHint hint(RuntimeState state, String message) {
        return new StateHint(state, message, Instant.now());
    }

    public record StateHint(RuntimeState state, String message, Instant observedAt) { }
}
