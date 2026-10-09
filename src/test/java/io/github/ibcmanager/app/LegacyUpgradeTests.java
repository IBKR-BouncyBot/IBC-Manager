package io.github.ibcmanager.app;

import io.github.ibcmanager.config.ManagedConfigService;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.TargetType;
import io.github.ibcmanager.storage.ProfileCodec;
import io.github.ibcmanager.storage.ProfileRepository;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class LegacyUpgradeTests implements TestSuite {
    @Override public String name() { return "1.x data-directory upgrade and retained credentials"; }
    @Override public List<NamedTest> tests() {
        return List.of(
                new NamedTest("legacy profile migrates in place with ID and credential bytes intact", this::migrate),
                new NamedTest("migration preserves original profile and configuration backups", this::backups),
                new NamedTest("repeat startup does not remigrate or rewrite profiles", this::idempotent),
                new NamedTest("legacy TWS stays TWS but disabled rather than converted", this::unsupported),
                new NamedTest("interrupted profile/config migration restores then retries", this::interrupted),
                new NamedTest("malformed transaction cannot escape the profile directory", this::malformed),
                new NamedTest("format five cannot select an external engine", this::noExternal),
                new NamedTest("live legacy process prevents migration without changing configuration", this::active),
                new NamedTest("invalid managed configuration rolls migration back without changing credential bytes", this::invalidConfig),
                new NamedTest("older profile formats migrate without reintroducing external engine selection", this::olderFormats),
                new NamedTest("migration failure releases startup lock and log resources", this::startupFailure));
    }

    private static Profile prepare(AppPaths paths, Path root, TargetType target) throws Exception {
        Profile profile = TestSupport.validProfile(root).toBuilder().targetType(target).build();
        Files.createDirectories(paths.profileDirectory(profile.id()));
        String legacy = new ProfileCodec().encode(profile).replace("formatVersion=5", "formatVersion=4")
                .replace("legacyIbcPath=", "ibcPath=").replace("engine=integrated\n", "");
        Files.writeString(paths.profileFile(profile.id()), legacy);
        if (target == TargetType.GATEWAY) new ManagedConfigService(paths).ensureManagedConfig(profile);
        Files.createDirectories(paths.credentials());
        Files.write(paths.credentialFile(profile.id()), new byte[] {1, 2, 3, 4});
        return profile;
    }
    private void migrate() throws Exception {
        Path root = TestSupport.tempDirectory("upgrade"); AppPaths paths = new AppPaths(root.resolve("data"));
        try {
            Profile before = prepare(paths, root, TargetType.GATEWAY);
            Assertions.equals(1, new LegacyUpgradeService(paths).migrate().size(), "one upgrade");
            Profile after = new ProfileRepository(paths).loadAll().profiles().get(0);
            Assertions.equals(before.id(), after.id(), "profile identity");
            Assertions.equals(before.username(), after.username(), "username");
            Assertions.equals(before.twsSettingsPath(), after.twsSettingsPath(), "Gateway settings path");
            Assertions.equals(before.reloginAfterSecondFactorTimeout(), after.reloginAfterSecondFactorTimeout(), "five minute retry policy");
            Assertions.equals(5, new ProfileCodec().sourceFormatVersion(Files.readString(paths.profileFile(before.id()))), "format");
            Assertions.isTrue(java.util.Arrays.equals(new byte[] {1, 2, 3, 4}, Files.readAllBytes(paths.credentialFile(before.id()))), "encrypted bytes not decrypted/rewritten");
        } finally { TestSupport.deleteTree(root); }
    }
    private void backups() throws Exception {
        Path root = TestSupport.tempDirectory("upgrade-backup"); AppPaths paths = new AppPaths(root.resolve("data"));
        try {
            Profile before = prepare(paths, root, TargetType.GATEWAY);
            byte[] original = Files.readAllBytes(paths.profileFile(before.id()));
            byte[] config = Files.readAllBytes(paths.profileConfig(before.id()));
            new LegacyUpgradeService(paths).migrate();
            Path backup = paths.profileDirectory(before.id()).resolve("upgrade-2.0.0-backup");
            Assertions.isTrue(java.util.Arrays.equals(original, Files.readAllBytes(backup.resolve("profile.properties"))), "exact original profile");
            Assertions.isTrue(java.util.Arrays.equals(config, Files.readAllBytes(backup.resolve("config.ini"))), "exact original config");
        } finally { TestSupport.deleteTree(root); }
    }
    private void idempotent() throws Exception {
        Path root = TestSupport.tempDirectory("upgrade-repeat"); AppPaths paths = new AppPaths(root.resolve("data"));
        try {
            Profile before = prepare(paths, root, TargetType.GATEWAY);
            new LegacyUpgradeService(paths).migrate();
            var modified = Files.getLastModifiedTime(paths.profileFile(before.id()));
            Assertions.isTrue(new LegacyUpgradeService(paths).migrate().isEmpty(), "no second migration");
            Assertions.equals(modified, Files.getLastModifiedTime(paths.profileFile(before.id())), "no unnecessary write");
        } finally { TestSupport.deleteTree(root); }
    }
    private void unsupported() throws Exception {
        Path root = TestSupport.tempDirectory("upgrade-tws"); AppPaths paths = new AppPaths(root.resolve("data"));
        try {
            Profile old = prepare(paths, root, TargetType.TWS);
            new LegacyUpgradeService(paths).migrate();
            Profile result = new ProfileRepository(paths).loadAll().profiles().get(0);
            Assertions.equals(old.id(), result.id(), "ID preserved");
            Assertions.equals(TargetType.TWS, result.targetType(), "not silently converted");
            Assertions.isFalse(result.enabled(), "not launchable");
            Assertions.isFalse(result.autoStart(), "not autostarted");
        } finally { TestSupport.deleteTree(root); }
    }
    private void interrupted() throws Exception {
        Path root = TestSupport.tempDirectory("upgrade-interrupted"); AppPaths paths = new AppPaths(root.resolve("data"));
        try {
            Profile old = prepare(paths, root, TargetType.GATEWAY);
            Path dir = paths.profileDirectory(old.id()); Path backup = dir.resolve("upgrade-2.0.0-backup");
            Files.createDirectories(backup);
            Files.copy(paths.profileFile(old.id()), backup.resolve("profile.properties"));
            Files.copy(paths.profileConfig(old.id()), backup.resolve("config.ini"));
            Files.writeString(dir.resolve("upgrade-2.0.0.pending"), "upgrade-2.0.0-backup\ntrue\n");
            Files.writeString(paths.profileFile(old.id()), "incomplete");
            Files.writeString(paths.profileConfig(old.id()), "broken");
            new LegacyUpgradeService(paths).migrate();
            Assertions.equals(old.id(), new ProfileRepository(paths).loadAll().profiles().get(0).id(), "restored complete profile");
            Assertions.contains(Files.readString(paths.profileConfig(old.id())), "CommandServerPort", "restored configuration");
            Assertions.isFalse(Files.exists(dir.resolve("upgrade-2.0.0.pending")), "transaction completed");
        } finally { TestSupport.deleteTree(root); }
    }
    private void malformed() throws Exception {
        Path root = TestSupport.tempDirectory("upgrade-malformed"); AppPaths paths = new AppPaths(root.resolve("data"));
        try {
            Profile old = prepare(paths, root, TargetType.GATEWAY);
            Files.writeString(paths.profileDirectory(old.id()).resolve("upgrade-2.0.0.pending"), "../../other\ntrue\n");
            String original = Files.readString(paths.profileFile(old.id()));
            Assertions.throwsType(IOException.class, () -> new LegacyUpgradeService(paths).migrate(), "unsafe marker rejected");
            Assertions.equals(original, Files.readString(paths.profileFile(old.id())), "original untouched");
        } finally { TestSupport.deleteTree(root); }
    }
    private void active() throws Exception {
        Path root = TestSupport.tempDirectory("upgrade-active"); AppPaths paths = new AppPaths(root.resolve("data"));
        try {
            Profile old = prepare(paths, root, TargetType.GATEWAY);
            String original = Files.readString(paths.profileFile(old.id()));
            ProcessHandle current = ProcessHandle.current();
            Files.createDirectories(paths.runtimeDirectory(old.id()));
            Files.writeString(paths.runtimeState(old.id()), "pid=" + current.pid() + "\nstartedAt="
                    + current.info().startInstant().orElseThrow() + "\nfingerprint=\n");
            Assertions.throwsType(IOException.class, () -> new LegacyUpgradeService(paths).migrate(), "live profile refused");
            Assertions.equals(original, Files.readString(paths.profileFile(old.id())), "profile not changed");
            Assertions.isFalse(Files.exists(paths.profileDirectory(old.id()).resolve("upgrade-2.0.0.pending")),
                    "no migration transaction starts before process check");
        } finally { TestSupport.deleteTree(root); }
    }
    private void invalidConfig() throws Exception {
        Path root = TestSupport.tempDirectory("upgrade-invalid-config"); AppPaths paths = new AppPaths(root.resolve("data"));
        try {
            Profile old = prepare(paths, root, TargetType.GATEWAY);
            Files.writeString(paths.profileConfig(old.id()), "CommandServerPort=not-a-number\n");
            String original = Files.readString(paths.profileFile(old.id()));
            String config = Files.readString(paths.profileConfig(old.id()));
            Assertions.throwsType(IOException.class, () -> new LegacyUpgradeService(paths).migrate(), "invalid config fails");
            Assertions.equals(original, Files.readString(paths.profileFile(old.id())), "profile rolled back");
            Assertions.equals(config, Files.readString(paths.profileConfig(old.id())), "config rolled back");
            Assertions.isTrue(java.util.Arrays.equals(new byte[] {1, 2, 3, 4},
                    Files.readAllBytes(paths.credentialFile(old.id()))), "credential retained");
        } finally { TestSupport.deleteTree(root); }
    }
    private void olderFormats() throws Exception {
        for (int format : List.of(1, 2, 3)) {
            Path root = TestSupport.tempDirectory("upgrade-format-" + format); AppPaths paths = new AppPaths(root.resolve("data"));
            try {
                Profile old = prepare(paths, root, TargetType.GATEWAY);
                Path file = paths.profileFile(old.id());
                Files.writeString(file, Files.readString(file).replace("formatVersion=4", "formatVersion=" + format));
                new LegacyUpgradeService(paths).migrate();
                String result = Files.readString(file);
                Assertions.contains(result, "formatVersion=5", "migrated format " + format);
                Assertions.contains(result, "engine=integrated", "engine selection");
                Assertions.equals(old.id(), new ProfileCodec().decode(result).id(), "identity preserved");
            } finally { TestSupport.deleteTree(root); }
        }
    }
    private void startupFailure() throws Exception {
        Path root = TestSupport.tempDirectory("upgrade-startup-error"); AppPaths paths = new AppPaths(root.resolve("data"));
        try {
            Profile old = prepare(paths, root, TargetType.GATEWAY);
            Files.writeString(paths.profileDirectory(old.id()).resolve("upgrade-2.0.0.pending"), "invalid");
            SingleInstanceLock lock = SingleInstanceLock.acquire(paths.lockFile());
            io.github.ibcmanager.logging.AppLog log = io.github.ibcmanager.logging.AppLog.initialize(paths);
            try {
                Assertions.throwsType(IOException.class, () -> IbcManagerApp.initializeServices(paths, log, lock),
                        "startup upgrade reports its error");
                try (SingleInstanceLock replacement = SingleInstanceLock.acquire(paths.lockFile())) {
                    Assertions.isTrue(replacement != null, "failed startup must not leave the data lock held");
                }
            } finally {
                log.close(); lock.close();
            }
        } finally { TestSupport.deleteTree(root); }
    }
    private void noExternal() throws Exception {
        var codec = new ProfileCodec();
        Assertions.throwsType(IllegalArgumentException.class,
                () -> codec.decode(codec.encode(Profile.builder().build()).replace("engine=integrated", "engine=external")),
                "external engine mode rejected");
    }
}
