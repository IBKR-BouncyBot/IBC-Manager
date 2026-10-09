package io.github.ibcmanager.app;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public final class AppPaths {
    private final Path root;

    public AppPaths(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public static AppPaths systemDefault() {
        return systemDefault(System.getenv(), System.getProperty("user.home", "."), OperatingSystem.current());
    }

    public static AppPaths systemDefault(Map<String, String> environment, String userHome, OperatingSystem os) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(userHome, "userHome");
        Objects.requireNonNull(os, "os");
        Path base;
        if (os == OperatingSystem.WINDOWS) {
            String localAppData = environment.getOrDefault("LOCALAPPDATA", "").trim();
            base = localAppData.isEmpty() ? Path.of(userHome, "AppData", "Local") : Path.of(localAppData);
            return new AppPaths(base.resolve("IBCManager"));
        }
        if (os == OperatingSystem.MAC) {
            return new AppPaths(Path.of(userHome, "Library", "Application Support", "IBCManager"));
        }
        return new AppPaths(Path.of(userHome, ".local", "share", "ibc-manager"));
    }

    public Path root() { return root; }
    public Path profiles() { return root.resolve("profiles"); }
    public Path credentials() { return root.resolve("credentials"); }
    public Path runtime() { return root.resolve("runtime"); }
    public Path logs() { return root.resolve("logs"); }
    public Path diagnostics() { return root.resolve("diagnostics"); }
    public Path deletions() { return root.resolve(".deletions"); }
    public Path lockFile() { return root.resolve("ibc-manager.lock"); }
    public Path appLog() { return logs().resolve("ibc-manager-0.log"); }
    public Path profileDirectory(java.util.UUID profileId) { return profiles().resolve(profileId.toString()); }
    public Path profileFile(java.util.UUID profileId) { return profileDirectory(profileId).resolve("profile.properties"); }
    public Path profileConfig(java.util.UUID profileId) { return profileDirectory(profileId).resolve("config.ini"); }
    public Path profileLog(java.util.UUID profileId) { return logs().resolve(profileId + ".log"); }
    public Path runtimeDirectory(java.util.UUID profileId) { return runtime().resolve(profileId.toString()); }
    public Path runtimeState(java.util.UUID profileId) { return runtimeDirectory(profileId).resolve("process.properties"); }
    public Path recoveryHistory(java.util.UUID profileId) { return runtimeDirectory(profileId).resolve("recovery-history.properties"); }
    public Path credentialFile(java.util.UUID profileId) { return credentials().resolve(profileId + ".dpapi"); }
}
