package io.github.ibcmanager.task;

import io.github.ibcmanager.app.OperatingSystem;
import io.github.ibcmanager.security.CommandExecutor;
import io.github.ibcmanager.security.CommandResult;
import io.github.ibcmanager.security.DefaultCommandExecutor;
import io.github.ibcmanager.security.SecretRedactor;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public final class WindowsTaskSchedulerService implements TaskSchedulerService {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private final OperatingSystem operatingSystem;
    private final CommandExecutor executor;

    public WindowsTaskSchedulerService() {
        this(OperatingSystem.current(), new DefaultCommandExecutor());
    }

    public WindowsTaskSchedulerService(OperatingSystem operatingSystem, CommandExecutor executor) {
        this.operatingSystem = Objects.requireNonNull(operatingSystem, "operatingSystem");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public boolean isAvailable() {
        return operatingSystem == OperatingSystem.WINDOWS;
    }

    @Override
    public boolean isInstalled(String taskName) throws IOException, InterruptedException {
        ensureAvailable();
        CommandResult result = executor.execute(TaskSchedulerCommandBuilder.query(taskName), "", COMMAND_TIMEOUT);
        if (result.timedOut()) throw new IOException("Task Scheduler query timed out");
        return result.exitCode() == 0;
    }

    @Override
    public void installAtLogon(String taskName, Path executable, List<String> arguments,
            Duration startupDelay) throws IOException, InterruptedException {
        ensureAvailable();
        Objects.requireNonNull(startupDelay, "startupDelay");
        // schtasks' ONLOGON trigger is used deliberately. Runtime recovery and profile auto-start
        // are handled by IBC Manager itself, so a second restart loop is not introduced here.
        CommandResult result = executor.execute(
                TaskSchedulerCommandBuilder.createAtLogon(taskName, executable, arguments, startupDelay), "", COMMAND_TIMEOUT);
        verify(result, "create");
    }

    @Override
    public void remove(String taskName) throws IOException, InterruptedException {
        ensureAvailable();
        CommandResult result = executor.execute(TaskSchedulerCommandBuilder.delete(taskName), "", COMMAND_TIMEOUT);
        if (result.timedOut()) throw new IOException("Task Scheduler removal timed out");
        if (result.exitCode() != 0 && !result.stderr().toLowerCase(java.util.Locale.ROOT).contains("cannot find")) {
            throw new IOException("Task Scheduler could not remove the task: "
                    + SecretRedactor.redact(result.stderr()).trim());
        }
    }

    private void ensureAvailable() throws IOException {
        if (!isAvailable()) throw new IOException("Windows Task Scheduler integration is only available on Windows");
    }

    private static void verify(CommandResult result, String action) throws IOException {
        if (result.timedOut()) throw new IOException("Task Scheduler " + action + " operation timed out");
        if (result.exitCode() != 0) {
            String detail = result.stderr().isBlank() ? result.stdout() : result.stderr();
            throw new IOException("Task Scheduler " + action + " operation failed: "
                    + SecretRedactor.redact(detail).trim());
        }
    }
}
