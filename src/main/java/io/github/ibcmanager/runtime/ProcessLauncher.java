package io.github.ibcmanager.runtime;

import java.io.IOException;
import java.nio.file.Path;

public interface ProcessLauncher {
    ManagedProcess launch(LaunchSpec spec, Path logFile, String initialLogText) throws IOException;
}
