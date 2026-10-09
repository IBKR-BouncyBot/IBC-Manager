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
    @Override public String name() { return "Windows IB Gateway-only installation discovery"; }
    @Override public List<NamedTest> tests() {
        return List.of(new NamedTest("Gateway detected without any external IBC", this::gateway),
                new NamedTest("TWS installations are excluded", this::tws),
                new NamedTest("duplicate roots and external IBC candidates cannot duplicate Gateway", this::dedup),
                new NamedTest("Windows discovery remains unavailable on non-Windows hosts", this::platform),
                new NamedTest("uppercase Gateway JARS accepted, malformed versions rejected", this::uppercase));
    }
    private void gateway() throws Exception {
        Path root = TestSupport.tempDirectory("discovery-gateway");
        try {
            Path jts = TestSupport.createOfflineGatewayInstallation(root.resolve("Jts"), "1050");
            var found = InstallationDiscoveryService.discover(List.of(), List.of(jts));
            Assertions.equals(1, found.size(), "Gateway found with no IBC");
            Assertions.equals(TargetType.GATEWAY, found.get(0).targetType(), "Gateway only");
            Assertions.equals("1050", found.get(0).version(), "version retained");
        } finally { TestSupport.deleteTree(root); }
    }
    private void tws() throws Exception {
        Path root = TestSupport.tempDirectory("discovery-no-tws");
        try {
            Path jts = TestSupport.createOfflineTwsInstallation(root.resolve("Jts"), "1050");
            Assertions.isTrue(InstallationDiscoveryService.discover(List.of(), List.of(jts)).isEmpty(), "no TWS offered");
        } finally { TestSupport.deleteTree(root); }
    }
    private void dedup() throws Exception {
        Path root = TestSupport.tempDirectory("discovery-dedup");
        try {
            Path jts = TestSupport.createOfflineGatewayInstallation(root.resolve("Jts"), "1045");
            var result = InstallationDiscoveryService.discover(List.of(root.resolve("IBC-A"), root.resolve("IBC-B")), List.of(jts, jts.resolve(".")));
            Assertions.equals(1, result.size(), "external path has no effect");
            Assertions.equals(jts.toRealPath(), result.get(0).twsRoot(), "normalized root");
        } finally { TestSupport.deleteTree(root); }
    }
    private void platform() {
        var service = new InstallationDiscoveryService(OperatingSystem.LINUX, Map.of(), "/home/test");
        Assertions.isFalse(service.isAvailable(), "not supported");
        Assertions.equals(List.of(), service.discover(), "no non-Windows discovery");
        Assertions.isTrue(new InstallationDiscoveryService(OperatingSystem.WINDOWS, Map.of(), "").isAvailable(), "Windows supported");
        Assertions.isTrue(InstallationDiscoveryService.defaultTwsRoots(Map.of(), "").stream().anyMatch(p -> p.toString().contains("Jts")), "conventional root retained");
    }
    private void uppercase() throws Exception {
        Path root = TestSupport.tempDirectory("discovery-case");
        try {
            Path jts = TestSupport.createOfflineGatewayInstallation(root.resolve("Jts"), "1050");
            Path program = jts.resolve("ibgateway/1050");
            Files.move(program.resolve("jars"), program.resolve("JARS"));
            Files.createDirectories(jts.resolve("ibgateway/abc/jars"));
            var result = InstallationDiscoveryService.discover(List.of(), List.of(jts));
            Assertions.equals(1, result.size(), "only valid Gateway");
        } finally { TestSupport.deleteTree(root); }
    }
}
