package io.github.ibcmanager.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ProfileStatus(
        UUID profileId,
        RuntimeState state,
        boolean processAlive,
        boolean commandPortOpen,
        boolean apiPortOpen,
        long pid,
        Instant startedAt,
        Integer exitCode,
        String message,
        Instant updatedAt) {

    public ProfileStatus {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(state, "state");
        message = Objects.requireNonNullElse(message, "");
        updatedAt = Objects.requireNonNullElseGet(updatedAt, Instant::now);
    }

    public static ProfileStatus stopped(UUID profileId) {
        return new ProfileStatus(
                profileId,
                RuntimeState.STOPPED,
                false,
                false,
                false,
                -1,
                null,
                null,
                "Stopped",
                Instant.now());
    }
}
