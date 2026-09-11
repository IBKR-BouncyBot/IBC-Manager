package io.github.ibcmanager.runtime;

import io.github.ibcmanager.model.RuntimeState;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

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
    private boolean childExitObserved;
    private boolean restartPending;
    private boolean normalExitConfirmed;
    private boolean errorExitConfirmed;
    private long sessionGeneration;

    public synchronized Optional<StateHint> accept(String line) {
        if (line == null) return Optional.empty();
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
                "starting ibc version", "ibc: starting gateway", "ibc: starting tws",
                "starting gateway with this command", "starting tws with this command")) {
            hint = hint(RuntimeState.STARTING, "Starting IBC and IBKR application");
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
    public synchronized boolean mainWindowReady() { return loginCompleted; }

    public synchronized boolean pauseConfirmed() { return pauseConfirmed; }
    public synchronized boolean launchCommandObserved() { return launchCommandObserved; }
    public synchronized boolean childExitObserved() { return childExitObserved; }
    public synchronized boolean restartPending() { return restartPending; }
    public synchronized boolean normalExitConfirmed() { return normalExitConfirmed; }
    public synchronized boolean errorExitConfirmed() { return errorExitConfirmed; }
    public synchronized long sessionGeneration() { return sessionGeneration; }

    public synchronized void resetSessionState() {
        latest = null;
        loginCompleted = false;
        pauseConfirmed = false;
        launchCommandObserved = false;
        childExitObserved = false;
        restartPending = false;
        normalExitConfirmed = false;
        errorExitConfirmed = false;
    }

    public synchronized void reset() {
        latest = null;
        commandServerState = CommandServerState.UNKNOWN;
        loginCompleted = false;
        pauseConfirmed = false;
        launchCommandObserved = false;
        childExitObserved = false;
        restartPending = false;
        normalExitConfirmed = false;
        errorExitConfirmed = false;
        sessionGeneration = 0;
    }

    private void clearChildSessionForLaunch() {
        latest = null;
        commandServerState = CommandServerState.UNKNOWN;
        loginCompleted = false;
        pauseConfirmed = false;
        childExitObserved = false;
        restartPending = false;
        normalExitConfirmed = false;
        errorExitConfirmed = false;
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
