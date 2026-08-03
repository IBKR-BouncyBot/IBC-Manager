package io.github.ibcmanager.runtime;

import io.github.ibcmanager.security.FilePermissionHardener;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class DefaultProcessLauncher implements ProcessLauncher {
    public static final Duration DISK_FLUSH_INTERVAL = Duration.ofSeconds(60);
    private final Duration flushInterval;

    public DefaultProcessLauncher() {
        this(DISK_FLUSH_INTERVAL);
    }

    public DefaultProcessLauncher(Duration flushInterval) {
        if (flushInterval == null || flushInterval.isZero() || flushInterval.isNegative()
                || flushInterval.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException(
                    "Process log flush interval must be between 1 millisecond and 1 hour");
        }
        this.flushInterval = flushInterval;
    }

    @Override
    public ManagedProcess launch(LaunchSpec spec, Path logFile, String initialLogText) throws IOException {
        Path normalizedLog = logFile.toAbsolutePath().normalize();
        Path logParent = normalizedLog.getParent();
        if (logParent == null) throw new IOException("Process log has no parent directory: " + logFile);
        FilePermissionHardener.hardenDirectory(logParent);
        Path descriptor = ProcessRelayDescriptor.write(spec, normalizedLog, initialLogText, flushInterval);
        List<String> relayCommand = List.of(
                javaExecutable().toString(),
                "-Dfile.encoding=UTF-8",
                "-classpath", relayClasspath(),
                BufferedProcessRelay.class.getName(),
                descriptor.toString());
        ProcessBuilder builder = new ProcessBuilder(relayCommand);
        builder.directory(spec.workingDirectory().toFile());
        builder.redirectErrorStream(true);
        try {
            Process relay = builder.start();
            return new JavaManagedProcess(relay, new LiveProcessOutput(relay.getInputStream()));
        } catch (IOException | RuntimeException ex) {
            try { ProcessRelayDescriptor.deleteExpectedRegularDescriptor(descriptor); }
            catch (IOException cleanup) { ex.addSuppressed(cleanup); }
            throw ex;
        }
    }

    private static Path javaExecutable() throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String rawHome = System.getProperty("java.home", "").trim();
        if (rawHome.isEmpty()) throw new IOException("The active Java runtime does not report java.home.");
        try {
            return locateJavaExecutable(Path.of(rawHome), windows);
        } catch (java.nio.file.InvalidPathException ex) {
            throw new IOException("The active Java runtime reports an invalid java.home path.", ex);
        }
    }

    static Path locateJavaExecutable(Path javaHome, boolean windows) throws IOException {
        Path normalizedHome = Objects.requireNonNull(javaHome, "javaHome").toAbsolutePath().normalize();
        List<String> candidates = windows ? List.of("java.exe", "javaw.exe") : List.of("java");
        for (String candidate : candidates) {
            Path executable = normalizedHome.resolve("bin").resolve(candidate).normalize();
            if (Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS) && Files.size(executable) > 0) {
                return executable;
            }
        }
        throw new IOException("Could not locate a Java process launcher under "
                + normalizedHome.resolve("bin") + ". The packaged runtime is incomplete.");
    }

    private static String relayClasspath() throws IOException {
        try {
            Path location = Path.of(BufferedProcessRelay.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            if (!Files.exists(location, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Process-relay classpath does not exist: " + location);
            return location.toString();
        } catch (URISyntaxException | SecurityException ex) {
            throw new IOException("Could not determine process-relay classpath", ex);
        }
    }
}
