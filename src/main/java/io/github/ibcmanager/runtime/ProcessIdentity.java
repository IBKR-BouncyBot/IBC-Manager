package io.github.ibcmanager.runtime;

import java.time.Instant;

public record ProcessIdentity(long pid, Instant startedAt) {
}
