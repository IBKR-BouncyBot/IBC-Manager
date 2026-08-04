package io.github.ibcmanager.runtime;

import io.github.ibcmanager.model.Profile;

import java.io.IOException;
import java.time.Duration;

/** Serializes the short, mutation-prone startup phase for one offline installation. */
public interface StartCoordinator {
    Lease acquire(Profile profile, Duration timeout) throws IOException, InterruptedException;

    interface Lease extends AutoCloseable {
        boolean coordinated();
        @Override void close() throws IOException;
    }

    static StartCoordinator noop() {
        return (profile, timeout) -> new Lease() {
            @Override public boolean coordinated() { return false; }
            @Override public void close() { }
        };
    }
}
