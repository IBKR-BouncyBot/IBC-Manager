package io.github.ibcmanager.runtime;

import java.time.Duration;

public interface PortProbe {
    boolean isOpen(String host, int port, Duration timeout);
}
