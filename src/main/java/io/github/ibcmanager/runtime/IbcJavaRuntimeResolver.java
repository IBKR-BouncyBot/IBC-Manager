package io.github.ibcmanager.runtime;

import io.github.ibcmanager.app.OperatingSystem;
import io.github.ibcmanager.install.IbcInstallationException;
import io.github.ibcmanager.install.IbcInstallationValidator;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.security.BoundedFileReader;
import io.github.ibcmanager.security.CommandExecutor;
import io.github.ibcmanager.security.CommandResult;
import io.github.ibcmanager.security.DefaultCommandExecutor;
import io.github.ibcmanager.security.SecureFileOperations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves and validates the exact Java directory that will be passed to StartIBC.bat. */
public final class IbcJavaRuntimeResolver {
    private static final int MAX_JRE_CONFIG_BYTES = 64 * 1024;
    private static final Duration VERSION_TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern QUOTED_VERSION = Pattern.compile("(?i)version\\s+\\\"([^\\\"]+)\\\"");
    private static final Pattern LEADING_VERSION = Pattern.compile("(?im)^(?:openjdk|java)\\s+([0-9][^\\s]*)");

    private final OperatingSystem operatingSystem;
    private final Map<String, String> environment;
    private final CommandExecutor executor;
    private final OfflineApplicationLayoutResolver layoutResolver;
    private final IbcInstallationValidator ibcInstallationValidator;

    public IbcJavaRuntimeResolver() {
        this(OperatingSystem.current(), System.getenv(), new DefaultCommandExecutor(),
                new OfflineApplicationLayoutResolver(), new IbcInstallationValidator());
    }

    public IbcJavaRuntimeResolver(OperatingSystem operatingSystem, Map<String, String> environment,
            CommandExecutor executor, OfflineApplicationLayoutResolver layoutResolver) {
        this(operatingSystem, environment, executor, layoutResolver, new IbcInstallationValidator());
    }

    IbcJavaRuntimeResolver(OperatingSystem operatingSystem, Map<String, String> environment,
            CommandExecutor executor, OfflineApplicationLayoutResolver layoutResolver,
            IbcInstallationValidator ibcInstallationValidator) {
        this.operatingSystem = Objects.requireNonNull(operatingSystem, "operatingSystem");
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        this.executor = Objects.requireNonNull(executor, "executor");
        this.layoutResolver = Objects.requireNonNull(layoutResolver, "layoutResolver");
        this.ibcInstallationValidator = Objects.requireNonNull(
                ibcInstallationValidator, "ibcInstallationValidator");
    }

    public boolean isSupportedPlatform() {
        return operatingSystem == OperatingSystem.WINDOWS;
    }

    public ResolvedJava resolve(Profile profile) throws IOException {
        if (!isSupportedPlatform()) {
            throw new IOException("IBC Java runtime resolution is supported only on Windows");
        }
        int requiredJavaMajor;
        try {
            requiredJavaMajor = ibcInstallationValidator.validate(profile.ibcPath()).requiredJavaMajor();
        } catch (IbcInstallationException ex) {
            throw new IOException("Could not determine the Java requirement of the selected IBC installation: "
                    + ex.getMessage(), ex);
        }
        OfflineApplicationLayoutResolver.Layout layout = layoutResolver.resolve(profile);
        Path javaDirectory = null;
        String source = "";
        if (profile.ibcJavaPath() != null && !profile.ibcJavaPath().toString().isBlank()) {
            javaDirectory = profile.ibcJavaPath().toAbsolutePath().normalize();
            source = "profile override";
        }
        if (javaDirectory == null) {
            javaDirectory = configuredRuntime(layout.install4jPath().resolve("pref_jre.cfg"));
            if (javaDirectory != null) source = "TWS/Gateway pref_jre.cfg";
        }
        if (javaDirectory == null) {
            javaDirectory = configuredRuntime(layout.install4jPath().resolve("inst_jre.cfg"));
            if (javaDirectory != null) source = "TWS/Gateway inst_jre.cfg";
        }
        if (javaDirectory == null) {
            String programData = environment.getOrDefault("PROGRAMDATA", "").trim();
            if (!programData.isEmpty()) {
                Path oracle;
                try {
                    oracle = Path.of(programData).resolve("Oracle").resolve("Java").resolve("javapath");
                } catch (RuntimeException ex) {
                    throw new IOException("PROGRAMDATA contains an invalid path while resolving IBC Java", ex);
                }
                if (SecureFileOperations.isRegularFile(oracle.resolve("java.exe"))) {
                    javaDirectory = oracle.toAbsolutePath().normalize();
                    source = "ProgramData Oracle javapath fallback";
                }
            }
        }
        if (javaDirectory == null) {
            throw new IOException("StartIBC.bat cannot find a Java runtime for the selected offline installation; "
                    + "select an explicit Java " + requiredJavaMajor + "+ directory");
        }
        Path executable = javaDirectory.resolve("java.exe").normalize();
        if (!SecureFileOperations.isRegularFile(executable)) {
            throw new IOException("The selected IBC Java directory does not contain java.exe: " + javaDirectory);
        }
        CommandResult result;
        try {
            result = executor.execute(List.of(executable.toString(), "-version"), "", VERSION_TIMEOUT);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while checking the IBC Java runtime", ex);
        }
        if (result.timedOut()) throw new IOException("Timed out while checking IBC Java: " + executable);
        if (result.exitCode() != 0) {
            throw new IOException("IBC Java runtime failed its version check: " + executable);
        }
        String combined = result.stderr() + System.lineSeparator() + result.stdout();
        String version = extractVersion(combined);
        int major = majorVersion(version);
        if (major < requiredJavaMajor) {
            throw new IOException("The selected IBC release requires Java " + requiredJavaMajor
                    + " or newer, but StartIBC would use Java " + version
                    + " from " + javaDirectory);
        }
        return new ResolvedJava(javaDirectory, executable, version, major, source, layout);
    }

    private static Path configuredRuntime(Path config) throws IOException {
        if (!SecureFileOperations.isRegularFile(config)) return null;
        String text = BoundedFileReader.readString(config, StandardCharsets.UTF_8,
                MAX_JRE_CONFIG_BYTES, "TWS/Gateway Java runtime configuration");
        String first = text.lines().findFirst().orElse("").trim();
        if (first.startsWith("\uFEFF")) first = first.substring(1).trim();
        if (first.isEmpty()) return null;
        Path runtimeRoot;
        try {
            runtimeRoot = Path.of(first).toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            throw new IOException("TWS/Gateway Java runtime configuration contains an invalid path: " + config, ex);
        }
        Path bin = runtimeRoot.resolve("bin");
        return SecureFileOperations.isRegularFile(bin.resolve("java.exe")) ? bin : null;
    }

    private static String extractVersion(String output) throws IOException {
        Matcher quoted = QUOTED_VERSION.matcher(output);
        if (quoted.find()) return quoted.group(1);
        Matcher leading = LEADING_VERSION.matcher(output);
        if (leading.find()) return leading.group(1);
        throw new IOException("Could not determine the version of the Java runtime selected for IBC");
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
