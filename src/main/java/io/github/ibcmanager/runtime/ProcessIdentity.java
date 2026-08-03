package io.github.ibcmanager.runtime;

import java.time.Instant;
import java.util.Objects;

public record ProcessIdentity(long pid, Instant startedAt, String fingerprint) {
    public ProcessIdentity {
        if (pid < 1) throw new IllegalArgumentException("pid must be positive");
        Objects.requireNonNull(startedAt, "startedAt");
        fingerprint = Objects.requireNonNullElse(fingerprint, "");
    }

    public ProcessIdentity(long pid, Instant startedAt) {
        this(pid, startedAt, "");
    }
}
