package io.github.ibcmanager.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record LaunchSpec(
        List<String> command,
        Path workingDirectory,
        Map<String, String> environment,
        Path launchScript,
        String displayCommand) {

    public LaunchSpec {
        command = List.copyOf(command);
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        environment = Map.copyOf(environment);
        Objects.requireNonNull(launchScript, "launchScript");
        displayCommand = Objects.requireNonNullElse(displayCommand, "");
    }
}
