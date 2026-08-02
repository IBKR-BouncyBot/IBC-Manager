package io.github.ibcmanager.runtime;

import io.github.ibcmanager.model.RuntimeState;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

public final class IbcLogStateParser {
    private StateHint latest;

    public synchronized Optional<StateHint> accept(String line) {
        if (line == null) return Optional.empty();
        String lower = line.toLowerCase(Locale.ROOT);
        StateHint hint = null;
        if (containsAny(lower, "second factor authentication initiated", "detected dialog entitled: second factor authentication")) {
            hint = hint(RuntimeState.WAITING_FOR_SECOND_FACTOR, "Waiting for second-factor authentication");
        } else if (containsAny(lower, "login has completed")) {
            hint = hint(RuntimeState.RUNNING, "IBC login completed");
        } else if (containsAny(lower, "login dialog window_opened", "setting user name", "login attempt:")) {
            hint = hint(RuntimeState.WAITING_FOR_LOGIN, "IBC is processing the login window");
        } else if (containsAny(lower, "starting ibc version", "ibc: starting gateway", "ibc: starting tws", "starting gateway with this command", "starting tws with this command")) {
            hint = hint(RuntimeState.STARTING, "Starting IBC and IBKR application");
        } else if (containsAny(lower, "ibc is paused")) {
            hint = hint(RuntimeState.PAUSED, "IBC session paused");
        } else if (containsAny(lower, "program has exited", "normal exit", "gateway finished at", "tws finished at")) {
            hint = hint(RuntimeState.STOPPED, "IBC process exited");
        } else if (containsAny(lower,
                "an error has occurred",
                "exiting with exit code=",
                "login failed",
                "too many failed login attempts",
                "could not login:",
                "can't find suitable java installation",
                "offline tws/gateway version") ) {
            hint = hint(RuntimeState.ERROR, line.trim());
        }
        if (hint != null) latest = hint;
        return Optional.ofNullable(hint);
    }

    public synchronized Optional<StateHint> latest() {
        return Optional.ofNullable(latest);
    }

    public synchronized void reset() {
        latest = null;
    }

    private static boolean containsAny(String value, String... patterns) {
        for (String pattern : patterns) if (value.contains(pattern)) return true;
        return false;
    }

    private static StateHint hint(RuntimeState state, String message) {
        return new StateHint(state, message, Instant.now());
    }

    public record StateHint(RuntimeState state, String message, Instant observedAt) {
    }
}
