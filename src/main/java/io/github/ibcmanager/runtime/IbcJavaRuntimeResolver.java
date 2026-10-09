package io.github.ibcmanager.runtime;

import io.github.ibcmanager.app.OperatingSystem;
import io.github.ibcmanager.engine.EmbeddedEngine;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.security.CommandExecutor;
import io.github.ibcmanager.security.CommandResult;
import io.github.ibcmanager.security.DefaultCommandExecutor;
import io.github.ibcmanager.security.SecureFileOperations;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates an explicit Java override while leaving automatic selection to StartIBC.bat. */
public final class IbcJavaRuntimeResolver {
    private static final Duration VERSION_TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern QUOTED_VERSION = Pattern.compile("(?i)version\\s+\\\"([^\\\"]+)\\\"");
    private static final Pattern LEADING_VERSION = Pattern.compile("(?im)^(?:openjdk|java)\\s+([0-9][^\\s]*)");
    private static final int JAVA_25_GATEWAY_TWS_VERSION = 1048;

    private final OperatingSystem operatingSystem;
    private final CommandExecutor executor;
    private final OfflineApplicationLayoutResolver layoutResolver;

    public IbcJavaRuntimeResolver() {
        this(OperatingSystem.current(), new DefaultCommandExecutor(),
                new OfflineApplicationLayoutResolver());
    }

    public IbcJavaRuntimeResolver(OperatingSystem operatingSystem, CommandExecutor executor,
            OfflineApplicationLayoutResolver layoutResolver) {
        this.operatingSystem = Objects.requireNonNull(operatingSystem, "operatingSystem");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.layoutResolver = Objects.requireNonNull(layoutResolver, "layoutResolver");
    }

    public boolean isSupportedPlatform() {
        return operatingSystem == OperatingSystem.WINDOWS;
    }

    /**
     * Resolves and validates only a user-supplied Java override.
     *
     * <p>When the profile field is blank, the official StartIBC.bat script remains
     * authoritative for bundled-runtime discovery. This is required for newer
     * TWS/Gateway installers whose runtime layout differs from the legacy install4j runtime-marker arrangement.</p>
     */
    public Optional<ResolvedJava> resolve(Profile profile) throws IOException {
        Objects.requireNonNull(profile, "profile");
        if (!isSupportedPlatform()) {
            throw new IOException("IBC Java runtime validation is supported only on Windows");
        }
        EmbeddedEngine.verifyBundledPayload();
        int ibcRequiredJavaMajor = 17; // source-built engine bytecode target
        int applicationRequiredJavaMajor = requiredApplicationJavaMajor(profile.twsMajorVersion());
        if (profile.ibcJavaPath() == null || profile.ibcJavaPath().toString().isBlank()) {
            if (applicationRequiredJavaMajor < ibcRequiredJavaMajor) {
                throw new IOException("The selected IBC release requires Java " + ibcRequiredJavaMajor
                        + " or newer, but " + profile.targetType().displayName() + ' '
                        + profile.twsMajorVersion() + " uses the Java " + applicationRequiredJavaMajor
                        + " bundled-runtime generation. Select an explicit Java " + ibcRequiredJavaMajor
                        + "+ override.");
            }
            return Optional.empty();
        }

        int requiredJavaMajor = Math.max(ibcRequiredJavaMajor, applicationRequiredJavaMajor);
        OfflineApplicationLayoutResolver.Layout layout = layoutResolver.resolve(profile);
        Path javaDirectory = profile.ibcJavaPath().toAbsolutePath().normalize();
        Path executable = javaDirectory.resolve("java.exe").normalize();
        if (!SecureFileOperations.isRegularFile(executable)) {
            throw new IOException("The explicit IBC Java directory does not contain java.exe: " + javaDirectory);
        }

        CommandResult result;
        try {
            result = executor.execute(List.of(executable.toString(), "-version"), "", VERSION_TIMEOUT);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while checking the explicit IBC Java override", ex);
        }
        if (result.timedOut()) {
            throw new IOException("Timed out while checking the explicit IBC Java override: " + executable);
        }
        if (result.exitCode() != 0) {
            throw new IOException("The explicit IBC Java override failed its version check: " + executable);
        }
        String combined = result.stderr() + System.lineSeparator() + result.stdout();
        String version = extractVersion(combined);
        int major = majorVersion(version);
        if (major < requiredJavaMajor) {
            String requirement = applicationRequiredJavaMajor >= ibcRequiredJavaMajor
                    ? profile.targetType().displayName() + ' ' + profile.twsMajorVersion()
                    : "the selected IBC release";
            throw new IOException(requirement + " requires Java " + requiredJavaMajor
                    + " or newer, but the explicit IBC Java override is Java " + version
                    + " from " + javaDirectory
                    + ". Clear the override to let StartIBC.bat use the bundled runtime.");
        }
        return Optional.of(new ResolvedJava(javaDirectory, executable, version, major,
                "profile override", layout));
    }

    static int requiredApplicationJavaMajor(String version) throws IOException {
        Objects.requireNonNull(version, "version");
        try {
            int numeric = Integer.parseInt(version.trim());
            return numeric >= JAVA_25_GATEWAY_TWS_VERSION ? 25 : 17;
        } catch (NumberFormatException ex) {
            throw new IOException("Unrecognized TWS/Gateway version while validating Java: " + version, ex);
        }
    }

    private static String extractVersion(String output) throws IOException {
        Matcher quoted = QUOTED_VERSION.matcher(output);
        if (quoted.find()) return quoted.group(1);
        Matcher leading = LEADING_VERSION.matcher(output);
        if (leading.find()) return leading.group(1);
        throw new IOException("Could not determine the version of the explicit IBC Java override");
    }

    static int majorVersion(String version) throws IOException {
        String normalized = Objects.requireNonNull(version, "version").trim().toLowerCase(Locale.ROOT);
        try {
            if (normalized.startsWith("1.")) {
                int end = normalized.indexOf('.', 2);
                String part = end < 0 ? normalized.substring(2) : normalized.substring(2, end);
                return Integer.parseInt(part.replaceAll("[^0-9].*$", ""));
            }
            Matcher matcher = Pattern.compile("^(\\d+)").matcher(normalized);
            if (!matcher.find()) throw new NumberFormatException();
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ex) {
            throw new IOException("Unrecognized Java version: " + version, ex);
        }
    }

    public record ResolvedJava(Path directory, Path executable, String version, int major,
            String source, OfflineApplicationLayoutResolver.Layout layout) {
        public ResolvedJava {
            directory = directory.toAbsolutePath().normalize();
            executable = executable.toAbsolutePath().normalize();
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(layout, "layout");
        }
    }
}
