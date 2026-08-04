package io.github.ibcmanager.runtime;

import io.github.ibcmanager.model.PortListenerState;

import java.time.Duration;

/** Passively inspects whether the local operating system reports a TCP listener. */
public interface PortProbe {
    PortListenerState inspect(String host, int port, Duration timeout);

    /**
     * Returns address/PID metadata when the platform exposes it. Implementations that only
     * support the legacy state result remain valid but cannot establish listener ownership.
     */
    default ListenerObservation observe(String host, int port, Duration timeout) {
        PortListenerState state = inspect(host, port, timeout);
        return switch (state) {
            case LISTENING -> ListenerObservation.listening(host, port, -1);
            case NOT_LISTENING -> ListenerObservation.notListening(host, port);
            case UNKNOWN -> ListenerObservation.unknown(port);
        };
    }

    /** Discards a cached listener snapshot before a launch preflight. */
    default void invalidate() {
    }
}
