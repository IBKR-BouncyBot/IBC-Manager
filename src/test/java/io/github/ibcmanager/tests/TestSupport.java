package io.github.ibcmanager.tests;

import io.github.ibcmanager.app.Version;
import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.TargetType;
import io.github.ibcmanager.model.TradingMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class TestSupport {
    private static final int WINDOWS_DELETE_ATTEMPTS = 101;
    private static final long WINDOWS_DELETE_DELAY_MILLIS = 50L;

    private TestSupport() { }

    public static Path tempDirectory(String prefix) throws IOException {
        return Files.createTempDirectory("ibc-manager-" + prefix + "-");
    }

    public static void deleteTree(Path root) throws IOException {
        if (root == null) return;
        int attempts = isWindows() ? WINDOWS_DELETE_ATTEMPTS : 1;
        retryTransientFileOperation(() -> deleteTreeOnce(root), attempts, WINDOWS_DELETE_DELAY_MILLIS);
    }

    static void retryTransientFileOperation(IoOperation operation, int maximumAttempts,
            long delayMillis) throws IOException {
        if (operation == null) throw new IllegalArgumentException("Operation must not be null");
        if (maximumAttempts < 1) throw new IllegalArgumentException("At least one attempt is required");
        if (delayMillis < 0) throw new IllegalArgumentException("Retry delay must not be negative");

        FileSystemException lastFailure = null;
        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            try {
                operation.run();
                return;
            } catch (FileSystemException failure) {
                lastFailure = failure;
                if (attempt == maximumAttempts) throw failure;
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    IOException result = new IOException(
                            "Interrupted while waiting to retry temporary-file cleanup", interrupted);
                    result.addSuppressed(failure);
                    throw result;
                }
            }
        }
        throw lastFailure == null ? new IOException("Temporary-file cleanup did not run") : lastFailure;
    }

    private static void deleteTreeOnce(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @FunctionalInterface
    interface IoOperation {
        void run() throws IOException;
    }

    public static List<String> javaCommand(String mode, String... arguments) {
        return javaCommandWithJvmOptions(List.of(), mode, arguments);
    }

    public static List<String> javaCommandWithJvmOptions(
            List<String> jvmOptions, String mode, String... arguments) {
        if (jvmOptions == null) throw new IllegalArgumentException("JVM options must not be null");
        if (mode == null || mode.isBlank()) throw new IllegalArgumentException("Subprocess mode is required");
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.addAll(jvmOptions);
        command.add("-classpath");
        command.add(absoluteClasspath());
        command.add(SubprocessFixture.class.getName());
        command.add(mode);
        command.addAll(List.of(arguments));
        return List.copyOf(command);
    }

    public static Path javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path executable = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(executable)) {
            throw new IllegalStateException("Could not locate the current Java executable: " + executable);
        }
        return executable;
    }

    private static String absoluteClasspath() {
        String raw = System.getProperty("java.class.path", "");
        String[] entries = raw.split(Pattern.quote(File.pathSeparator), -1);
        List<String> normalized = new ArrayList<>(entries.length);
        for (String entry : entries) {
            Path path = entry.isEmpty() ? Path.of("") : Path.of(entry);
            normalized.add(path.toAbsolutePath().normalize().toString());
        }
        return String.join(File.pathSeparator, normalized);
    }

    public static Profile validProfile(Path root) throws IOException {
        UUID id = UUID.randomUUID();
        Path ibc = createCompleteIbcInstallation(root.resolve("IBC"));
        Path tws = createOfflineGatewayInstallation(root.resolve("Jts"), "1045");
        Path settings = root.resolve("settings-" + id);
        Files.createDirectories(settings);
        return Profile.builder()
                .id(id)
                .name("Paper Gateway")
                .enabled(true)
                .targetType(TargetType.GATEWAY)
                .tradingMode(TradingMode.PAPER)
                .twsMajorVersion("1045")
                .ibcPath(ibc.toAbsolutePath())
                .twsPath(tws.toAbsolutePath())
                .twsSettingsPath(settings.toAbsolutePath())
                .apiPort(4002)
                .commandServerPort(7462)
                .bindAddress("127.0.0.1")
                .username("paper-user")
                .credentialMode(CredentialMode.MANUAL)
                .settings(Map.of("AcceptIncomingConnectionAction", "accept"))
                .build();
    }

    public static Path createCompleteIbcInstallation(Path ibc) throws IOException {
        Files.createDirectories(ibc.resolve("scripts"));
        Files.writeString(ibc.resolve("version"), Version.IBC_MINIMUM_SUPPORTED_VERSION + "\n");
        Files.writeString(ibc.resolve("config.ini"), "IbLoginId=\nIbPassword=\n");
        Files.writeString(ibc.resolve("LICENSE.txt"), "GPL-3.0 test fixture\n");
        writeIbcJar(ibc.resolve("IBC.jar"));
        Files.writeString(ibc.resolve("scripts").resolve("StartIBC.bat"),
                "@echo off\r\n"
                + "rem IBC.jar\r\n"
                + "rem /Gateway\r\n"
                + "rem /TwsPath:\r\n"
                + "rem /TwsSettingsPath:\r\n"
                + "rem /IbcPath:\r\n"
                + "rem /Config:\r\n"
                + "rem /JavaPath:\r\n"
                + "rem /Mode:\r\n"
                + "rem /On2FATimeout:\r\n"
                + "rem Starting IBC with this command:\r\n"
                + "rem getExtraJavaOptions.ps1\r\n"
                + "rem EXTRA_JAVA_OPTIONS\r\n"
                + "rem IBCSessionId\r\n"
                + "rem IBC is paused\r\n");
        Files.writeString(ibc.resolve("scripts").resolve("getExtraJavaOptions.ps1"),
                "param([string]$Install4J)\r\n"
                + "$confPath = Join-Path $Install4J \"i4jparams.conf\"\r\n"
                + "$line = Select-String -Path $confPath -Pattern 'javaOptions'\r\n"
                + "Write-Output $line\r\n");
        return ibc;
    }

    public static Path createOfflineGatewayInstallation(Path twsRoot, String version) throws IOException {
        Path program = twsRoot.resolve("ibgateway").resolve(version);
        Files.createDirectories(program.resolve("jars"));
        Files.createDirectories(program.resolve(".install4j"));
        Files.writeString(program.resolve("ibgateway.vmoptions"), "-Xmx512m\n");
        Files.writeString(program.resolve(".install4j").resolve("pref_jre.cfg"),
                Path.of(System.getProperty("java.home")).toAbsolutePath().normalize() + System.lineSeparator());
        return twsRoot;
    }


    public static Path createOfflineTwsInstallation(Path twsRoot, String version) throws IOException {
        Path program = twsRoot.resolve(version);
        Files.createDirectories(program.resolve("jars"));
        Files.createDirectories(program.resolve(".install4j"));
        Files.writeString(program.resolve("tws.vmoptions"), "-Xmx512m\n");
        Files.writeString(program.resolve(".install4j").resolve("pref_jre.cfg"),
                Path.of(System.getProperty("java.home")).toAbsolutePath().normalize() + System.lineSeparator());
        return twsRoot;
    }

    public static void writeIbcJar(Path jarPath) throws IOException {
        writeIbcJar(jarPath, Version.IBC_MINIMUM_SUPPORTED_VERSION, 61);
    }

    public static void writeIbcJar(Path jarPath, String version, int classMajor) throws IOException {
        if (classMajor < 45 || classMajor > 0xFFFF) {
            throw new IllegalArgumentException("classMajor is outside the class-file range");
        }
        Files.createDirectories(jarPath.toAbsolutePath().normalize().getParent());
        List<String> classes = List.of(
                "ibcalpha/ibc/IbcTws.class",
                "ibcalpha/ibc/IbcGateway.class",
                "ibcalpha/ibc/CommandDispatcher.class",
                "ibcalpha/ibc/RestartTask.class",
                "ibcalpha/ibc/DefaultSettings.class");
        byte[] versionClass = ibcVersionInfoClassBytes(version);
        versionClass[6] = (byte) (classMajor >>> 8);
        versionClass[7] = (byte) classMajor;
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (String name : classes) {
                JarEntry entry = new JarEntry(name);
                entry.setTime(0L);
                output.putNextEntry(entry);
                output.write(new byte[] {0});
                output.closeEntry();
            }
            JarEntry versionEntry = new JarEntry("ibcalpha/ibc/IbcVersionInfo.class");
            versionEntry.setTime(0L);
            output.putNextEntry(versionEntry);
            output.write(versionClass);
            output.closeEntry();
        } finally {
            java.util.Arrays.fill(versionClass, (byte) 0);
        }
    }

    public static byte[] ibcVersionInfoClassBytes() throws IOException {
        return classBytes(FixtureIbcVersionInfo.class);
    }

    public static byte[] ibcVersionInfoClassBytes(String version) throws IOException {
        if (Version.IBC_MINIMUM_SUPPORTED_VERSION.equals(version)) {
            return classBytes(FixtureIbcVersionInfo.class);
        }
        if ("4.0.0".equals(version)) {
            return classBytes(FixtureFutureIbcVersionInfo.class);
        }
        if ("9.9.9".equals(version)) {
            return classBytes(FixtureMismatchedIbcVersionInfo.class);
        }
        throw new IOException("No compiled IBC version fixture for " + version);
    }

    public static byte[] mismatchedIbcVersionInfoClassBytes() throws IOException {
        return classBytes(FixtureMismatchedIbcVersionInfo.class);
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resourceName = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = TestSupport.class.getResourceAsStream(resourceName)) {
            if (input == null) throw new IOException("Missing compiled IBC version fixture: " + resourceName);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            input.transferTo(output);
            return output.toByteArray();
        }
    }

    /** The ConstantValue attribute is read without loading the class from the synthetic IBC JAR. */
    public static final class FixtureIbcVersionInfo {
        public static final String IBC_VERSION = Version.IBC_MINIMUM_SUPPORTED_VERSION;
        private FixtureIbcVersionInfo() { }
    }

    public static final class FixtureFutureIbcVersionInfo {
        public static final String IBC_VERSION = "4.0.0";
        private FixtureFutureIbcVersionInfo() { }
    }

    public static final class FixtureMismatchedIbcVersionInfo {
        public static final String IBC_VERSION = "9.9.9";
        private FixtureMismatchedIbcVersionInfo() { }
    }

}
