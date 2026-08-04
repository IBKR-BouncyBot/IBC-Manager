package io.github.ibcmanager.runtime;

import io.github.ibcmanager.model.PortListenerState;

import java.util.Objects;

/** Detailed passive observation of one requested local TCP listener. */
public record ListenerObservation(
        PortListenerState state,
        String localAddress,
        int port,
        long owningPid,
        boolean ownershipAvailable) {

    public ListenerObservation {
        state = Objects.requireNonNullElse(state, PortListenerState.UNKNOWN);
        localAddress = Objects.requireNonNullElse(localAddress, "");
        if (port < -1 || port > 65_535) throw new IllegalArgumentException("port is out of range");
        if (owningPid < -1) throw new IllegalArgumentException("owningPid is out of range");
        if (state != PortListenerState.LISTENING) {
            owningPid = -1;
            ownershipAvailable = false;
        }
        if (ownershipAvailable && owningPid <= 0) {
            throw new IllegalArgumentException("ownershipAvailable requires a positive PID");
        }
    }

    public static ListenerObservation unknown(int port) {
        return new ListenerObservation(PortListenerState.UNKNOWN, "", port, -1, false);
    }

    public static ListenerObservation notListening(String address, int port) {
        return new ListenerObservation(PortListenerState.NOT_LISTENING, address, port, -1, false);
    }

    public static ListenerObservation listening(String address, int port, long pid) {
        return new ListenerObservation(PortListenerState.LISTENING, address, port, pid, pid > 0);
    }
}
