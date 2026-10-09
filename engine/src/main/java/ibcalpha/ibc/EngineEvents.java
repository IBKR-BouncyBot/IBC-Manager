// IBC Manager integration; GPL-3.0-or-later, like the upstream engine.
package ibcalpha.ibc;

import java.util.Set;
import java.util.UUID;

/** Versioned, bounded lifecycle messages on the inherited stdout pipe. No credentials or free text. */
public final class EngineEvents {
    public static final String PREFIX = "IBC_MANAGER_EVENT|1|";
    private static final String GENERATION = UUID.randomUUID().toString();
    private static final Set<String> ALLOWED = Set.of(
            "ENGINE_STARTED", "GATEWAY_STARTING", "MAIN_WINDOW_READY",
            "LOGIN_LOGGED_OUT", "LOGIN_LOGGING_IN", "LOGIN_AWAITING_CREDENTIALS",
            "LOGIN_TWO_FA_IN_PROGRESS", "LOGIN_LOGGED_IN", "LOGIN_LOGIN_FAILED",
            "COMMAND_SERVER_READY", "COMMAND_SERVER_CLOSED",
            "SECOND_FACTOR_RETRY_ARMED", "SECOND_FACTOR_RETRY_DUE",
            "SECOND_FACTOR_RETRY_STARTED", "SECOND_FACTOR_RETRY_BLOCKED");
    private static long sequence;

    private EngineEvents() { }

    public static synchronized void emit(String event) {
        if (!ALLOWED.contains(event)) throw new IllegalArgumentException("Unknown engine event");
        Utils.getOutStream().println(PREFIX + GENERATION + "|" + (++sequence) + "|" + event);
    }
}
