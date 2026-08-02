package io.github.ibcmanager.task;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public interface TaskSchedulerService {
    boolean isAvailable();
    boolean isInstalled(String taskName) throws IOException, InterruptedException;
    void installAtLogon(String taskName, Path executable, List<String> arguments,
            Duration startupDelay) throws IOException, InterruptedException;
    void remove(String taskName) throws IOException, InterruptedException;
}
