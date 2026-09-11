package io.github.ibcmanager.discovery;

import io.github.ibcmanager.app.OperatingSystem;
import io.github.ibcmanager.model.TargetType;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class InstallationDiscoveryTests implements TestSuite {
    @Override public String name() { return "IBC and offline TWS/Gateway installation discovery"; }

    @Override
    public List<NamedTest> tests() {
        return List.of(
                new NamedTest("discovers direct IBC and both application types", this::directDiscovery),
                new NamedTest("discovers IBC in one-level child directories", this::nestedIbc),
                new NamedTest("accepts uppercase JARS and ignores malformed versions", this::uppercaseAndMalformed),
                new NamedTest("combines and sorts multiple IBC and application installations", this::combinesAndSorts),
                new NamedTest("deduplicates repeated normalized search roots", this::deduplicates),
                new NamedTest("returns no application without a complete IBC installation", this::requiresIbc),
                new NamedTest("platform facade is disabled outside Windows", this::platformFacade),
                new NamedTest("default Windows candidates include conventional user and system roots", this::defaultCandidates),
                new NamedTest("detected installation record validates and normalizes input", this::recordValidation));
    }

    private void directDiscovery() throws Exception {
        Path root = TestSupport.tempDirectory("discovery-direct");
        try {
            Path ibc = completeIbc(root.resolve("IBC"));
            Path jts = root.resolve("Jts");
            TestSupport.createOfflineTwsInstallation(jts, "1044");
            TestSupport.createOfflineGatewayInstallation(jts, "1045");

            List<DetectedInstallation> result = InstallationDiscoveryService.discover(List.of(ibc), List.of(jts));
            Assertions.equals(2, result.size(), "both offline products must be detected");
            Assertions.equals(TargetType.GATEWAY, result.get(0).targetType(), "newest version must sort first");
            Assertions.equals("1045", result.get(0).version(), "Gateway version mismatch");
            Assertions.equals(TargetType.TWS, result.get(1).targetType(), "TWS installation missing");
            Assertions.equals("1044", result.get(1).version(), "TWS version mismatch");
            Assertions.equals(ibc.toRealPath(), result.get(0).ibcPath(), "IBC path must be canonical");
            Assertions.equals(jts.toRealPath(), result.get(0).twsRoot(), "TWS root must be canonical");
            Assertions.equals(jts.toRealPath(), result.get(0).settingsPath(),
                    "default settings path must be the detected Jts root");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void nestedIbc() throws Exception {
        Path root = TestSupport.tempDirectory("discovery-nested");
        try {
            Path parent = root.resolve("downloads");
            Path ibc = completeIbc(parent.resolve("IBC-3.24.2"));
            Path jts = root.resolve("Jts");
            TestSupport.createOfflineGatewayInstallation(jts, "1045");
            List<DetectedInstallation> result = InstallationDiscoveryService.discover(List.of(parent), List.of(jts));
            Assertions.equals(1, result.size(), "one-level IBC distribution must be found");
            Assertions.equals(ibc.toRealPath(), result.get(0).ibcPath(), "nested IBC path mismatch");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void uppercaseAndMalformed() throws Exception {
        Path root = TestSupport.tempDirectory("discovery-case");
        try {
            Path ibc = completeIbc(root.resolve("IBC"));
            Path jts = root.resolve("Jts");
            Files.createDirectories(jts.resolve("1050").resolve("JARS"));
            Files.createDirectories(jts.resolve("1050").resolve(".install4j"));
            Files.writeString(jts.resolve("1050").resolve("tws.vmoptions"), "-Xmx512m\n");
            Files.createDirectories(jts.resolve("ibgateway").resolve("abc").resolve("jars"));
            Files.createDirectories(jts.resolve("ibgateway").resolve("12").resolve("jars"));
            Files.createDirectories(jts.resolve("ibgateway").resolve("123456").resolve("jars"));
            Files.createDirectories(jts.resolve("ibgateway").resolve("1049"));
            List<DetectedInstallation> result = InstallationDiscoveryService.discover(List.of(ibc), List.of(jts));
            Assertions.equals(1, result.size(), "only a valid numeric version with jars must be accepted");
            Assertions.equals(TargetType.TWS, result.get(0).targetType(), "uppercase JARS TWS must be detected");
            Assertions.equals("1050", result.get(0).version(), "uppercase JARS version mismatch");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void combinesAndSorts() throws Exception {
        Path root = TestSupport.tempDirectory("discovery-combine");
        try {
            Path ibcA = completeIbc(root.resolve("IBC-A"));
            Path ibcB = completeIbc(root.resolve("IBC-B"));
            Path jtsA = root.resolve("Jts-A");
            Path jtsB = root.resolve("Jts-B");
            TestSupport.createOfflineGatewayInstallation(jtsA, "1045");
            TestSupport.createOfflineTwsInstallation(jtsB, "1046");
            List<DetectedInstallation> result = InstallationDiscoveryService.discover(
                    List.of(ibcB, ibcA), List.of(jtsA, jtsB));
            Assertions.equals(4, result.size(), "every valid IBC/application pairing must be offered");
            Assertions.equals("1046", result.get(0).version(), "newest version must be first");
            Assertions.equals("1046", result.get(1).version(), "newest version must occupy first group");
            Assertions.equals("1045", result.get(2).version(), "older version must follow");
            Assertions.isTrue(result.get(0).ibcPath().toString().compareToIgnoreCase(
                    result.get(1).ibcPath().toString()) <= 0, "equal versions must have deterministic IBC ordering");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void deduplicates() throws Exception {
        Path root = TestSupport.tempDirectory("discovery-dedup");
        try {
            Path ibc = completeIbc(root.resolve("IBC"));
            Path jts = root.resolve("Jts");
            TestSupport.createOfflineGatewayInstallation(jts, "1045");
            List<DetectedInstallation> result = InstallationDiscoveryService.discover(
                    List.of(ibc, ibc.resolve("."), ibc.toAbsolutePath()),
                    List.of(jts, jts.resolve("."), jts.toAbsolutePath()));
            Assertions.equals(1, result.size(), "normalized duplicate roots must not duplicate results");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void requiresIbc() throws Exception {
        Path root = TestSupport.tempDirectory("discovery-missing-ibc");
        try {
            Path incomplete = root.resolve("IBC");
            Files.createDirectories(incomplete.resolve("scripts"));
            Files.writeString(incomplete.resolve("IBC.jar"), "test");
            Path jts = root.resolve("Jts");
            TestSupport.createOfflineGatewayInstallation(jts, "1045");
            Assertions.equals(List.of(), InstallationDiscoveryService.discover(List.of(incomplete), List.of(jts)),
                    "missing StartIBC.bat must invalidate IBC installation");
            Assertions.equals(List.of(), InstallationDiscoveryService.discover(List.of(), List.of(jts)),
                    "no IBC search result must produce no pairings");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void platformFacade() {
        InstallationDiscoveryService linux = new InstallationDiscoveryService(
                OperatingSystem.LINUX, Map.of(), "/home/test");
        Assertions.isFalse(linux.isAvailable(), "discovery must be marked unavailable outside Windows");
        Assertions.equals(List.of(), linux.discover(), "non-Windows discovery must not inspect fake Windows paths");
        InstallationDiscoveryService windows = new InstallationDiscoveryService(
                OperatingSystem.WINDOWS, Map.of(), "");
        Assertions.isTrue(windows.isAvailable(), "Windows discovery must be available");
    }

    private void defaultCandidates() {
        Map<String, String> environment = Map.of(
                "PROGRAMDATA", "D:/ProgramData",
                "LOCALAPPDATA", "D:/Users/Test/AppData/Local",
                "ProgramFiles", "D:/Program Files",
                "ProgramFiles(x86)", "D:/Program Files (x86)");
        List<Path> ibc = InstallationDiscoveryService.defaultIbcCandidates(environment, "D:/Users/Test");
        List<Path> jts = InstallationDiscoveryService.defaultTwsRoots(environment, "D:/Users/Test");
        Assertions.isTrue(ibc.stream().anyMatch(path -> path.toString().endsWith("IBC")),
                "IBC candidates must include conventional IBC directory names");
        Assertions.isTrue(ibc.stream().anyMatch(path -> path.toString().contains("Program Files")),
                "IBC candidates must include Program Files");
        Assertions.isTrue(jts.stream().anyMatch(path -> path.toString().endsWith("Jts")),
                "TWS candidates must include conventional Jts roots");
        Assertions.equals(ibc.size(), Math.toIntExact(ibc.stream().distinct().count()),
                "IBC candidates must be unique");
        Assertions.equals(jts.size(), Math.toIntExact(jts.stream().distinct().count()),
                "TWS candidates must be unique");
    }

    private void recordValidation() throws Exception {
        Path root = TestSupport.tempDirectory("discovery-record");
        try {
            DetectedInstallation installation = new DetectedInstallation(
                    root.resolve("IBC/..//IBC"), root.resolve("Jts/..//Jts"), root.resolve("Jts"),
                    TargetType.GATEWAY, " 1045 ");
            Assertions.equals("1045", installation.version(), "version must be trimmed");
            Assertions.isTrue(installation.ibcPath().isAbsolute(), "IBC path must be absolute");
            Assertions.isTrue(installation.twsRoot().isAbsolute(), "TWS root must be absolute");
            Assertions.throwsType(IllegalArgumentException.class,
                    () -> new DetectedInstallation(root, root, root, TargetType.GATEWAY, "bad"),
                    "malformed version must be rejected");
            Assertions.throwsType(NullPointerException.class,
                    () -> new DetectedInstallation(null, root, root, TargetType.GATEWAY, "1045"),
                    "null path must be rejected");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private static Path completeIbc(Path path) throws Exception {
        return TestSupport.createCompleteIbcInstallation(path);
    }
}
