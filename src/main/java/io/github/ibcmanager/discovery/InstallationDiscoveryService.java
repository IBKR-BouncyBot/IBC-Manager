package io.github.ibcmanager.discovery;

import io.github.ibcmanager.app.OperatingSystem;
import io.github.ibcmanager.model.TargetType;
import io.github.ibcmanager.security.SecureFileOperations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public final class InstallationDiscoveryService {
    private static final Comparator<DetectedInstallation> ORDER = Comparator
            .comparingInt((DetectedInstallation item) -> Integer.parseInt(item.version())).reversed()
            .thenComparing(DetectedInstallation::targetType)
            .thenComparing(item -> item.twsRoot().toString(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(item -> item.ibcPath().toString(), String.CASE_INSENSITIVE_ORDER);

    private final OperatingSystem operatingSystem;
    private final Map<String, String> environment;
    private final String userHome;

    public InstallationDiscoveryService() {
        this(OperatingSystem.current(), System.getenv(), System.getProperty("user.home", ""));
    }

    public InstallationDiscoveryService(OperatingSystem operatingSystem,
            Map<String, String> environment, String userHome) {
        this.operatingSystem = Objects.requireNonNull(operatingSystem, "operatingSystem");
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        this.userHome = userHome == null ? "" : userHome.trim();
    }

    public boolean isAvailable() {
        return operatingSystem == OperatingSystem.WINDOWS;
    }

    public List<DetectedInstallation> discover() {
        if (!isAvailable()) return List.of();
        return discover(defaultIbcCandidates(environment, userHome), defaultTwsRoots(environment, userHome));
    }

    public static List<DetectedInstallation> discover(List<Path> ibcCandidates, List<Path> twsRoots) {
        Objects.requireNonNull(ibcCandidates, "ibcCandidates");
        Objects.requireNonNull(twsRoots, "twsRoots");
        List<Path> ibcDirectories = findIbcDirectories(ibcCandidates);
        if (ibcDirectories.isEmpty()) return List.of();

        List<AppInstallation> applications = findApplications(twsRoots);
        List<DetectedInstallation> result = new ArrayList<>();
        for (Path ibc : ibcDirectories) {
            for (AppInstallation app : applications) {
                result.add(new DetectedInstallation(ibc, app.root(), app.root(), app.targetType(), app.version()));
            }
        }
        return result.stream().distinct().sorted(ORDER).toList();
    }

    static List<Path> defaultIbcCandidates(Map<String, String> environment, String userHome) {
        Set<Path> result = new LinkedHashSet<>();
        add(result, "C:\\IBC");
        addChild(result, userHome, "IBC");
        addChild(result, environment.get("PROGRAMDATA"), "IBC");
        addChild(result, environment.get("LOCALAPPDATA"), "IBC");
        addChild(result, environment.get("ProgramFiles"), "IBC");
        addChild(result, environment.get("ProgramFiles(x86)"), "IBC");
        return List.copyOf(result);
    }

    static List<Path> defaultTwsRoots(Map<String, String> environment, String userHome) {
        Set<Path> result = new LinkedHashSet<>();
        add(result, "C:\\Jts");
        addChild(result, userHome, "Jts");
        addChild(result, environment.get("LOCALAPPDATA"), "Jts");
        addChild(result, environment.get("PROGRAMDATA"), "Jts");
        return List.copyOf(result);
    }

    private static List<Path> findIbcDirectories(List<Path> candidates) {
        Set<Path> result = new LinkedHashSet<>();
        for (Path candidate : normalizeDistinct(candidates)) {
            if (isIbcDirectory(candidate)) result.add(realOrNormalized(candidate));
            for (Path child : children(candidate)) {
                if (isIbcDirectory(child)) result.add(realOrNormalized(child));
            }
        }
        return result.stream().sorted(Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private static List<AppInstallation> findApplications(List<Path> roots) {
        Set<AppInstallation> result = new LinkedHashSet<>();
        for (Path root : normalizeDistinct(roots)) {
            Path normalizedRoot = realOrNormalized(root);
            scanVersionChildren(normalizedRoot, normalizedRoot, TargetType.TWS, result);
            scanVersionChildren(normalizedRoot.resolve("ibgateway"), normalizedRoot, TargetType.GATEWAY, result);
        }
        return result.stream()
                .sorted(Comparator.comparingInt((AppInstallation app) -> Integer.parseInt(app.version())).reversed()
                        .thenComparing(AppInstallation::targetType)
                        .thenComparing(app -> app.root().toString(), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static void scanVersionChildren(Path versionsDirectory, Path root,
            TargetType targetType, Set<AppInstallation> output) {
        for (Path child : children(versionsDirectory)) {
            String version = child.getFileName() == null ? "" : child.getFileName().toString();
            if (!version.matches("[0-9]{3,5}")) continue;
            if (hasJarsDirectory(child)) {
                output.add(new AppInstallation(realOrNormalized(root), targetType, version));
            }
        }
    }

    private static boolean isIbcDirectory(Path directory) {
        return SecureFileOperations.isRegularFile(directory.resolve("IBC.jar"))
                && SecureFileOperations.isRegularFile(directory.resolve("scripts").resolve("StartIBC.bat"));
    }

    private static boolean hasJarsDirectory(Path versionDirectory) {
        return SecureFileOperations.isDirectory(versionDirectory.resolve("jars"))
                || SecureFileOperations.isDirectory(versionDirectory.resolve("JARS"));
    }

    private static List<Path> children(Path directory) {
        if (!SecureFileOperations.isDirectory(directory)) return List.of();
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(SecureFileOperations::isDirectory).toList();
        } catch (IOException | SecurityException ignored) {
            return List.of();
        }
    }

    private static List<Path> normalizeDistinct(List<Path> paths) {
        Set<Path> result = new LinkedHashSet<>();
        for (Path path : paths) {
            if (path != null && !path.toString().isBlank()) result.add(path.toAbsolutePath().normalize());
        }
        return List.copyOf(result);
    }

    private static Path realOrNormalized(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | SecurityException ignored) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static void addChild(Set<Path> result, String parent, String child) {
        if (parent == null || parent.isBlank()) return;
        add(result, Path.of(parent.trim()).resolve(child).toString());
    }

    private static void add(Set<Path> result, String value) {
        if (value == null || value.isBlank()) return;
        Path path = Path.of(value.trim()).toAbsolutePath().normalize();
        String key = path.toString().toLowerCase(Locale.ROOT);
        boolean duplicate = result.stream().anyMatch(existing ->
                existing.toString().toLowerCase(Locale.ROOT).equals(key));
        if (!duplicate) result.add(path);
    }

    private record AppInstallation(Path root, TargetType targetType, String version) { }
}
