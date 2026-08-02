package io.github.ibcmanager.tests;

import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.TargetType;
import io.github.ibcmanager.model.TradingMode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.UUID;

public final class TestSupport {
    private TestSupport() { }

    public static Path tempDirectory(String prefix) throws IOException {
        return Files.createTempDirectory("ibc-manager-" + prefix + "-");
    }

    public static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    public static List<String> javaCommand(String mode, String... arguments) {
        if (mode == null || mode.isBlank()) throw new IllegalArgumentException("Subprocess mode is required");
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
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
        Path ibc = root.resolve("IBC");
        Path tws = root.resolve("Jts");
        Path settings = root.resolve("settings-" + id);
        Files.createDirectories(ibc.resolve("scripts"));
        Files.createDirectories(tws.resolve("ibgateway").resolve("1045").resolve("jars"));
        Files.createDirectories(settings);
        Files.writeString(ibc.resolve("IBC.jar"), "test");
        Files.writeString(ibc.resolve("scripts").resolve("StartIBC.bat"), "@echo off\r\n");
        Files.writeString(tws.resolve("ibgateway").resolve("1045").resolve("ibgateway.vmoptions"), "-Xmx512m\n");
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
}
