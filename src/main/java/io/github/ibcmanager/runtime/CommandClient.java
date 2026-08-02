package io.github.ibcmanager.runtime;

import java.io.IOException;
import java.time.Duration;

public interface CommandClient {
    IbcCommandResult send(String host, int port, IbcCommand command, Duration timeout) throws IOException;
}
