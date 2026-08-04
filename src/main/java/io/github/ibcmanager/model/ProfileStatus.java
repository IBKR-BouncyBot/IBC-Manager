package io.github.ibcmanager.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ProfileStatus(
        UUID profileId,
        RuntimeState state,
        boolean processAlive,
        boolean commandPortOpen,
        PortListenerState apiListenerState,
        long pid,
        Instant startedAt,
        Integer exitCode,
        String message,
        Instant updatedAt) {

    public ProfileStatus {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(state, "state");
        apiListenerState = Objects.requireNonNullElse(apiListenerState, PortListenerState.UNKNOWN);
        message = Objects.requireNonNullElse(message, "");
        updatedAt = Objects.requireNonNullElseGet(updatedAt, Instant::now);
    }

    public boolean apiListenerDetected() {
        return apiListenerState == PortListenerState.LISTENING;
    }

    public boolean apiListenerStateKnown() {
        return apiListenerState != PortListenerState.UNKNOWN;
    }

    public static ProfileStatus stopped(UUID profileId) {
        return new ProfileStatus(
                profileId,
                RuntimeState.STOPPED,
                false,
                false,
                PortListenerState.UNKNOWN,
                -1,
                null,
                null,
                "Stopped",
                Instant.now());
    }
}
