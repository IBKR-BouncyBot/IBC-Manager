package io.github.ibcmanager.runtime;

import io.github.ibcmanager.security.FilePermissionHardener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DefaultProcessLauncher implements ProcessLauncher {
    @Override
    public ManagedProcess launch(LaunchSpec spec, Path logFile) throws IOException {
        Path normalizedLog = logFile.toAbsolutePath().normalize();
        FilePermissionHardener.hardenDirectory(normalizedLog.getParent());
        if (!Files.exists(normalizedLog)) Files.createFile(normalizedLog);
        FilePermissionHardener.hardenFile(normalizedLog);
        ProcessBuilder builder = new ProcessBuilder(spec.command());
        builder.directory(spec.workingDirectory().toFile());
        builder.environment().putAll(spec.environment());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(normalizedLog.toFile()));
        return new JavaManagedProcess(builder.start());
    }
}
