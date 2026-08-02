package io.github.ibcmanager.security;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

public interface CommandExecutor {
    CommandResult execute(List<String> command, String stdin, Duration timeout) throws IOException, InterruptedException;
}
