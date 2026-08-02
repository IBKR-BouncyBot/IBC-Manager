package io.github.ibcmanager.storage;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.app.ProfileSaveService;
import io.github.ibcmanager.config.IbcConfigDocument;
import io.github.ibcmanager.config.ManagedConfigService;
import io.github.ibcmanager.config.RuntimeConfigFactory;
import io.github.ibcmanager.config.RuntimeConfigLease;
import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.security.CredentialStore;
import io.github.ibcmanager.security.CredentialStoreException;
import io.github.ibcmanager.security.SecureChars;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class StorageTests implements TestSuite {
    @Override public String name() { return "Persistent storage and runtime configuration"; }

    @Override
    public List<NamedTest> tests() {
        return List.of(
                new NamedTest("atomic writer creates a complete file", this::atomicCreate),
                new NamedTest("atomic writer preserves the previous backup", this::atomicBackup),
                new NamedTest("repository saves loads and sorts profiles", this::repositoryRoundTrip),
                new NamedTest("repository recovers a corrupt profile from backup", this::repositoryRecovery),
                new NamedTest("repository skips unrecoverable profiles", this::repositoryCorruptBoth),
                new NamedTest("repository rejects a profile stored under the wrong ID", this::repositoryWrongDirectory),
                new NamedTest("repository deletes only the selected profile", this::repositoryDelete),
                new NamedTest("repository serializes concurrent saves", this::repositoryConcurrent),
                new NamedTest("managed config is created from the bundled template", this::managedTemplate),
                new NamedTest("managed config imports comments and removes secrets", this::managedImport),
                new NamedTest("managed config applies profile-controlled settings", this::managedApplyProfile),
                new NamedTest("managed config ignores reserved advanced overrides", this::managedReserved),
                new NamedTest("managed config refuses persistent plaintext passwords", this::managedRejectSecret),
                new NamedTest("managed config preserves imported CRLF layout", this::managedCrlf),
                new NamedTest("runtime config manual mode contains no password", this::runtimeManual),
                new NamedTest("runtime config encrypted mode injects the password temporarily", this::runtimeEncrypted),
                new NamedTest("runtime config existing-config mode copies the selected file", this::runtimeExisting),
                new NamedTest("runtime config rejects an empty encrypted password", this::runtimeEmptyPassword),
                new NamedTest("runtime config rejects config-breaking credentials", this::runtimeUnsafeCredentials),
                new NamedTest("runtime config cleanup removes a stale credential file", this::runtimeCleanStale),
                new NamedTest("profile save stores encrypted credentials without serializing them", this::profileSaveEncrypted),
                new NamedTest("profile save rejects config-breaking passwords before persistence", this::profileSaveUnsafePassword),
                new NamedTest("profile save retains an existing password when no replacement is supplied", this::profileSaveRetainPassword),
                new NamedTest("profile save removes credentials when switching to manual", this::profileSaveManual),
                new NamedTest("profile save rejects a changed profile ID", this::profileSaveChangedId),
                new NamedTest("profile save rolls back a newly created credential on repository failure", this::profileSaveRollbackNew),
                new NamedTest("profile save restores profile and credential after config failure", this::profileSaveRollbackExisting));
    }

    private void atomicCreate() throws Exception {
        Path root = TestSupport.tempDirectory("atomic-create");
        try {
            Path target = root.resolve("nested/file.txt");
            AtomicFileWriter.write(target, "complete".getBytes(StandardCharsets.UTF_8), true);
            Assertions.equals("complete", Files.readString(target), "target must contain the complete payload");
            try (var stream = Files.list(target.getParent())) {
                Assertions.isFalse(stream.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                        "successful writes must not leave temporary files");
            }
        } finally { TestSupport.deleteTree(root); }
    }

    private void atomicBackup() throws Exception {
        Path root = TestSupport.tempDirectory("atomic-backup");
        try {
            Path target = root.resolve("value.txt");
            AtomicFileWriter.write(target, "first".getBytes(StandardCharsets.UTF_8), true);
            AtomicFileWriter.write(target, "second".getBytes(StandardCharsets.UTF_8), true);
            Assertions.equals("second", Files.readString(target), "new value must replace target");
            Assertions.equals("first", Files.readString(root.resolve("value.txt.bak")),
                    "backup must contain the immediately previous value");
        } finally { TestSupport.deleteTree(root); }
    }

    private void repositoryRoundTrip() throws Exception {
        Path root = TestSupport.tempDirectory("repository-roundtrip");
        try {
            AppPaths paths = new AppPaths(root);
            ProfileRepository repository = new ProfileRepository(paths);
            Profile zeta = TestSupport.validProfile(root.resolve("zeta")).toBuilder().name("zeta").build();
            Profile alpha = TestSupport.validProfile(root.resolve("alpha")).toBuilder().name("Alpha").build();
            repository.save(zeta);
            repository.save(alpha);
            ProfileRepository.LoadResult loaded = repository.loadAll();
            Assertions.equals(List.of(alpha, zeta), loaded.profiles(), "profiles must load in case-insensitive name order");
            Assertions.equals(List.of(), loaded.warnings(), "valid profiles must not produce warnings");
            Assertions.notContains(Files.readString(paths.profileFile(alpha.id())), "password", "profile files must contain no password field");
        } finally { TestSupport.deleteTree(root); }
    }

    private void repositoryRecovery() throws Exception {
        Path root = TestSupport.tempDirectory("repository-recovery");
        try {
            AppPaths paths = new AppPaths(root);
            ProfileRepository repository = new ProfileRepository(paths);
            Profile original = TestSupport.validProfile(root.resolve("install")).toBuilder().name("Original").build();
            Profile changed = original.toBuilder().name("Changed").build();
            repository.save(original);
            repository.save(changed);
            Files.writeString(paths.profileFile(original.id()), "not=a valid profile\n");
            ProfileRepository.LoadResult loaded = repository.loadAll();
            Assertions.equals(List.of(original), loaded.profiles(), "backup must recover the previous complete profile");
            Assertions.isTrue(loaded.warnings().stream().anyMatch(value -> value.contains("Recovered profile")),
                    "recovery must be reported");
            Assertions.equals(original, new ProfileCodec().decode(Files.readString(paths.profileFile(original.id()))),
                    "recovered primary file must be rewritten");
        } finally { TestSupport.deleteTree(root); }
    }

    private void repositoryCorruptBoth() throws Exception {
        Path root = TestSupport.tempDirectory("repository-corrupt");
        try {
            AppPaths paths = new AppPaths(root);
            Profile profile = TestSupport.validProfile(root.resolve("install"));
            Path directory = paths.profileDirectory(profile.id());
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("profile.properties"), "broken");
            Files.writeString(directory.resolve("profile.properties.bak"), "also broken");
            ProfileRepository.LoadResult loaded = new ProfileRepository(paths).loadAll();
            Assertions.equals(0, loaded.profiles().size(), "unrecoverable profile must not be returned");
            Assertions.equals(1, loaded.warnings().size(), "one clear warning is expected");
        } finally { TestSupport.deleteTree(root); }
    }

    private void repositoryWrongDirectory() throws Exception {
        Path root = TestSupport.tempDirectory("repository-id");
        try {
            AppPaths paths = new AppPaths(root);
            Profile profile = TestSupport.validProfile(root.resolve("install"));
            Path wrong = paths.profiles().resolve(UUID.randomUUID().toString());
            Files.createDirectories(wrong);
            Files.writeString(wrong.resolve("profile.properties"), new ProfileCodec().encode(profile));
            ProfileRepository.LoadResult loaded = new ProfileRepository(paths).loadAll();
            Assertions.equals(0, loaded.profiles().size(), "mismatched directory identity must be rejected");
            Assertions.contains(loaded.warnings().get(0), "does not match", "warning must explain identity mismatch");
        } finally { TestSupport.deleteTree(root); }
    }

    private void repositoryDelete() throws Exception {
        Path root = TestSupport.tempDirectory("repository-delete");
        try {
            AppPaths paths = new AppPaths(root);
            ProfileRepository repository = new ProfileRepository(paths);
            Profile first = TestSupport.validProfile(root.resolve("first"));
            Profile second = TestSupport.validProfile(root.resolve("second"));
            repository.save(first);
            repository.save(second);
            repository.delete(first.id());
            Assertions.isFalse(Files.exists(paths.profileDirectory(first.id())), "selected directory must be removed recursively");
            Assertions.fileExists(paths.profileFile(second.id()), "other profile must remain");
        } finally { TestSupport.deleteTree(root); }
    }

    private void repositoryConcurrent() throws Exception {
        Path root = TestSupport.tempDirectory("repository-concurrent");
        var executor = Executors.newFixedThreadPool(6);
        try {
            ProfileRepository repository = new ProfileRepository(new AppPaths(root));
            List<Profile> profiles = new ArrayList<>();
            for (int i = 0; i < 18; i++) {
                profiles.add(TestSupport.validProfile(root.resolve("install-" + i)).toBuilder().name("P" + i).build());
            }
            for (Profile profile : profiles) executor.submit(() -> {
                try {
                    for (int pass = 0; pass < 4; pass++) repository.save(profile.toBuilder().autoStart((pass & 1) == 0).build());
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
            executor.shutdown();
            Assertions.isTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "concurrent saves must finish");
            ProfileRepository.LoadResult loaded = repository.loadAll();
            Assertions.equals(18, loaded.profiles().size(), "all profiles must remain readable");
            Assertions.equals(0, loaded.warnings().size(), "serialized concurrent saves must not corrupt files");
        } finally {
            executor.shutdownNow();
            TestSupport.deleteTree(root);
        }
    }

    private void managedTemplate() throws Exception {
        Path root = TestSupport.tempDirectory("managed-template");
        try {
            AppPaths paths = new AppPaths(root);
            Profile profile = TestSupport.validProfile(root.resolve("install"));
            ManagedConfigService service = new ManagedConfigService(paths);
            Path file = service.ensureManagedConfig(profile);
            Assertions.fileExists(file, "managed config must be created");
            IbcConfigDocument document = service.loadManagedConfig(profile);
            Assertions.equals(profile.username(), document.get("IbLoginId").orElseThrow(), "profile login ID must be applied");
            Assertions.equals("", document.get("IbPassword").orElseThrow(), "persistent password must be blank");
            Assertions.equals("paper", document.get("TradingMode").orElseThrow(), "trading mode must be applied");
        } finally { TestSupport.deleteTree(root); }
    }

    private void managedImport() throws Exception {
        Path root = TestSupport.tempDirectory("managed-import");
        try {
            AppPaths paths = new AppPaths(root);
            Path imported = root.resolve("source.ini");
            Files.writeString(imported, "# custom comment\nIbLoginId=edemo\nIbPassword=secret\nFIXLoginId=demouser\nFIXPassword=fix\nUnknownFutureSetting=value\n");
            Profile profile = TestSupport.validProfile(root.resolve("install")).toBuilder().baseConfigPath(imported).build();
            ManagedConfigService service = new ManagedConfigService(paths);
            String text = Files.readString(service.ensureManagedConfig(profile));
            Assertions.contains(text, "# custom comment", "comments must be preserved");
            Assertions.contains(text, "UnknownFutureSetting=value", "unknown settings must be preserved");
            Assertions.notContains(text, "secret", "IB password must be removed");
            Assertions.notContains(text, "FIXPassword=fix", "FIX password must be removed");
            Assertions.notContains(text, "IbLoginId=edemo", "demo identity must not leak into a new profile");
        } finally { TestSupport.deleteTree(root); }
    }

    private void managedApplyProfile() throws Exception {
        Path root = TestSupport.tempDirectory("managed-apply");
        try {
            Profile profile = TestSupport.validProfile(root.resolve("install")).toBuilder()
                    .apiPort(7497).commandServerPort(7999).bindAddress("127.0.0.2")
                    .minimizeMainWindow(false).setting("AcceptIncomingConnectionAction", "reject")
                    .setting("SecondFactorDevice", "IBKR Mobile").build();
            ManagedConfigService service = new ManagedConfigService(new AppPaths(root));
            IbcConfigDocument document = service.loadManagedConfig(profile);
            Assertions.equals("7497", document.get("OverrideTwsApiPort").orElseThrow(), "API port must be applied");
            Assertions.equals("7999", document.get("CommandServerPort").orElseThrow(), "command port must be applied");
            Assertions.equals("127.0.0.2", document.get("BindAddress").orElseThrow(), "bind address must be applied");
            Assertions.equals("no", document.get("MinimizeMainWindow").orElseThrow(), "minimize option must be applied");
            Assertions.equals("reject", document.get("AcceptIncomingConnectionAction").orElseThrow(), "advanced setting must be applied");
            Assertions.equals("IBKR Mobile", document.get("SecondFactorDevice").orElseThrow(),
                    "profile-tab second-factor device must be applied");
        } finally { TestSupport.deleteTree(root); }
    }

    private void managedReserved() throws Exception {
        Path root = TestSupport.tempDirectory("managed-reserved");
        try {
            Profile profile = TestSupport.validProfile(root.resolve("install")).toBuilder()
                    .apiPort(4002).setting("OverrideTwsApiPort", "9999")
                    .setting("TradingMode", "live").setting("IbPassword", "leak")
                    .setting("ibpassword", "case-variant-leak")
                    .setting("commandserverport", "9998")
                    .setting("SECONDFACTORDEVICE", "Primary device").build();
            IbcConfigDocument document = new ManagedConfigService(new AppPaths(root)).loadManagedConfig(profile);
            Assertions.equals("4002", document.get("OverrideTwsApiPort").orElseThrow(), "reserved API override must be ignored");
            Assertions.equals("paper", document.get("TradingMode").orElseThrow(), "reserved trading mode must be ignored");
            Assertions.equals("", document.get("IbPassword").orElseThrow(), "sensitive advanced setting must be ignored");
            Assertions.isTrue(document.get("ibpassword").isEmpty(),
                    "case-variant sensitive advanced setting must be ignored");
            Assertions.isTrue(document.get("commandserverport").isEmpty(),
                    "case-variant profile-controlled setting must be ignored");
            Assertions.equals("Primary device", document.get("SecondFactorDevice").orElseThrow(),
                    "case-insensitive direct setting lookup must use the profile value");
            Assertions.isTrue(document.get("SECONDFACTORDEVICE").isEmpty(),
                    "case-variant direct setting must not create a duplicate key");
        } finally { TestSupport.deleteTree(root); }
    }

    private void managedRejectSecret() throws Exception {
        Path root = TestSupport.tempDirectory("managed-secret");
        try {
            Profile profile = TestSupport.validProfile(root.resolve("install"));
            ManagedConfigService service = new ManagedConfigService(new AppPaths(root));
            for (String source : List.of(
                    "IbPassword=plaintext\n",
                    "IbPassword=hidden-duplicate\nIbPassword=\n",
                    "# FIXPassword=comment-secret\nFIXPassword=\n",
                    "not-a-setting IbPassword=raw-secret\nIbPassword=\n",
                    "ibpassword=case-variant-secret\nIbPassword=\n")) {
                IbcConfigDocument document = IbcConfigDocument.parse(source);
                Assertions.throwsType(IOException.class, () -> service.saveManagedConfig(profile, document),
                        "every persistent plaintext representation must be rejected: " + source);
            }
        } finally { TestSupport.deleteTree(root); }
    }

    private void managedCrlf() throws Exception {
        Path root = TestSupport.tempDirectory("managed-crlf");
        try {
            Path source = root.resolve("source.ini");
            Files.writeString(source, "# first\r\nIbLoginId=old\r\nIbPassword=\r\nTradingMode=live\r\n");
            Profile profile = TestSupport.validProfile(root.resolve("install")).toBuilder().baseConfigPath(source).build();
            String rendered = Files.readString(new ManagedConfigService(new AppPaths(root)).ensureManagedConfig(profile));
            Assertions.contains(rendered, "\r\n", "CRLF convention must be retained");
            Assertions.isFalse(rendered.replace("\r\n", "").contains("\n"), "mixed LF line endings must not be introduced");
        } finally { TestSupport.deleteTree(root); }
    }

    private void runtimeManual() throws Exception {
        Path root = TestSupport.tempDirectory("runtime-manual");
        try {
            AppPaths paths = new AppPaths(root);
            Profile profile = TestSupport.validProfile(root.resolve("install"));
            RuntimeConfigFactory factory = new RuntimeConfigFactory(paths, new ManagedConfigService(paths));
            RuntimeConfigLease lease = factory.create(profile, null);
            try {
                String text = Files.readString(lease.path());
                Assertions.contains(text, "IbLoginId=" + profile.username(), "manual runtime config must contain username");
                Assertions.contains(text, "IbPassword=", "manual runtime config must have a blank password field");
                Assertions.notContains(text, "IbPassword=secret", "manual runtime config must contain no secret");
            } finally { lease.close(); }
        } finally { TestSupport.deleteTree(root); }
    }

    private void runtimeEncrypted() throws Exception {
        Path root = TestSupport.tempDirectory("runtime-encrypted");
        try {
            AppPaths paths = new AppPaths(root);
            Profile profile = TestSupport.validProfile(root.resolve("install")).toBuilder()
                    .credentialMode(CredentialMode.ENCRYPTED).build();
            RuntimeConfigFactory factory = new RuntimeConfigFactory(paths, new ManagedConfigService(paths));
            RuntimeConfigLease lease;
            try (SecureChars password = new SecureChars("temporary secret".toCharArray())) {
                lease = factory.create(profile, password);
            }
            Assertions.contains(Files.readString(lease.path()), "IbPassword=temporary secret",
                    "encrypted password must be injected into only the runtime copy");
            Assertions.notContains(Files.readString(paths.profileConfig(profile.id())), "temporary secret",
                    "managed persistent config must stay secret-free");
            lease.close();
            Assertions.isFalse(Files.exists(lease.path()), "runtime copy must be removed on close");
        } finally { TestSupport.deleteTree(root); }
    }

    private void runtimeExisting() throws Exception {
        Path root = TestSupport.tempDirectory("runtime-existing");
        try {
            AppPaths paths = new AppPaths(root);
            Path source = root.resolve("existing.ini");
            Files.writeString(source, "# existing\nIbLoginId=external\nIbPassword=external-secret\nTradingMode=live\n");
            Profile profile = TestSupport.validProfile(root.resolve("install")).toBuilder()
                    .credentialMode(CredentialMode.EXISTING_CONFIG).baseConfigPath(source).build();
            RuntimeConfigLease lease = new RuntimeConfigFactory(paths, new ManagedConfigService(paths)).create(profile, null);
            try {
                String text = Files.readString(lease.path());
                Assertions.contains(text, "IbLoginId=external", "existing credentials must remain in the temporary copy");
                Assertions.contains(text, "IbPassword=external-secret", "existing password must remain in the temporary copy");
                Assertions.equals("paper", IbcConfigDocument.parse(text).get("TradingMode").orElseThrow(),
                        "profile-controlled trading mode must still be applied");
                Assertions.equals("# existing\nIbLoginId=external\nIbPassword=external-secret\nTradingMode=live\n",
                        Files.readString(source), "source config must never be modified");
            } finally { lease.close(); }
        } finally { TestSupport.deleteTree(root); }
    }

    private void runtimeEmptyPassword() throws Exception {
        Path root = TestSupport.tempDirectory("runtime-empty");
        try {
            AppPaths paths = new AppPaths(root);
            Profile profile = TestSupport.validProfile(root.resolve("install")).toBuilder()
                    .credentialMode(CredentialMode.ENCRYPTED).build();
            RuntimeConfigFactory factory = new RuntimeConfigFactory(paths, new ManagedConfigService(paths));
            try (SecureChars empty = new SecureChars(new char[0])) {
                Assertions.throwsType(IOException.class, () -> factory.create(profile, empty),
                        "empty encrypted password must be rejected");
            }
        } finally { TestSupport.deleteTree(root); }
    }

    private void runtimeUnsafeCredentials() throws Exception {
        Path root = TestSupport.tempDirectory("runtime-unsafe");
        try {
            AppPaths paths = new AppPaths(root);
            RuntimeConfigFactory factory = new RuntimeConfigFactory(paths, new ManagedConfigService(paths));
            Profile encrypted = TestSupport.validProfile(root.resolve("encrypted")).toBuilder()
                    .credentialMode(CredentialMode.ENCRYPTED).build();
            try (SecureChars password = new SecureChars("bad\npassword".toCharArray())) {
                Assertions.throwsType(IOException.class, () -> factory.create(encrypted, password),
                        "line-breaking password must not create a runtime config");
            }
            Profile manual = TestSupport.validProfile(root.resolve("manual")).toBuilder()
                    .username("bad\rusername").build();
            Assertions.throwsType(IOException.class, () -> factory.create(manual, null),
                    "line-breaking username must not create a runtime config");
            Assertions.isFalse(Files.exists(paths.runtimeDirectory(encrypted.id()).resolve("config.ini")),
                    "rejected password must leave no runtime config");
            Assertions.isFalse(Files.exists(paths.runtimeDirectory(manual.id()).resolve("config.ini")),
                    "rejected username must leave no runtime config");
        } finally { TestSupport.deleteTree(root); }
    }

    private void runtimeCleanStale() throws Exception {
        Path root = TestSupport.tempDirectory("runtime-stale");
        try {
            AppPaths paths = new AppPaths(root);
            Profile profile = TestSupport.validProfile(root.resolve("install"));
            Path stale = paths.runtimeDirectory(profile.id()).resolve("config.ini");
            Files.createDirectories(stale.getParent());
            Files.writeString(stale, "IbPassword=stale-secret\n");
            new RuntimeConfigFactory(paths, new ManagedConfigService(paths)).cleanStale(profile);
            Assertions.isFalse(Files.exists(stale), "stale runtime config must be removed before launch");
        } finally { TestSupport.deleteTree(root); }
    }

    private void profileSaveEncrypted() throws Exception {
        Path root = TestSupport.tempDirectory("save-encrypted");
        try {
            AppPaths paths = new AppPaths(root);
            MemoryCredentialStore credentials = new MemoryCredentialStore();
            ProfileRepository repository = new ProfileRepository(paths);
            Profile profile = TestSupport.validProfile(root.resolve("install")).toBuilder()
                    .credentialMode(CredentialMode.ENCRYPTED).build();
            char[] supplied = "stored-secret".toCharArray();
            new ProfileSaveService(repository, credentials, new ManagedConfigService(paths)).save(null, profile, supplied);
            Assertions.equals("stored-secret", credentials.value(profile.id()), "credential store must receive password");
            String serialized = Files.readString(paths.profileFile(profile.id()));
            Assertions.notContains(serialized, "stored-secret", "profile file must never serialize password");
            Assertions.notContains(Files.readString(paths.profileConfig(profile.id())), "stored-secret",
                    "managed config must never serialize password");
            Assertions.arrayEquals("stored-secret".toCharArray(), supplied,
                    "caller's supplied array must not be modified by the save service");
        } finally { TestSupport.deleteTree(root); }
    }

    private void profileSaveUnsafePassword() throws Exception {
        Path root = TestSupport.tempDirectory("save-unsafe-password");
        try {
            AppPaths paths = new AppPaths(root);
            MemoryCredentialStore credentials = new MemoryCredentialStore();
            Profile profile = TestSupport.validProfile(root.resolve("install")).toBuilder()
                    .credentialMode(CredentialMode.ENCRYPTED).build();
            ProfileSaveService service = new ProfileSaveService(new ProfileRepository(paths), credentials,
                    new ManagedConfigService(paths));
            Assertions.throwsType(CredentialStoreException.class,
                    () -> service.save(null, profile, "bad\npassword".toCharArray()),
                    "config-breaking password must fail before storage");
            Assertions.isFalse(credentials.exists(profile.id()), "rejected password must not reach credential storage");
            Assertions.isFalse(Files.exists(paths.profileFile(profile.id())),
                    "rejected password must not create a profile");
        } finally { TestSupport.deleteTree(root); }
    }

    private void profileSaveRetainPassword() throws Exception {
        Path root = TestSupport.tempDirectory("save-retain");
        try {
            AppPaths paths = new AppPaths(root);
            MemoryCredentialStore credentials = new MemoryCredentialStore();
            Profile profile = TestSupport.validProfile(root.resolve("install")).toBuilder()
                    .credentialMode(CredentialMode.ENCRYPTED).build();
            credentials.save(profile.id(), "existing".toCharArray());
            ProfileSaveService service = new ProfileSaveService(new ProfileRepository(paths), credentials,
                    new ManagedConfigService(paths));
            service.save(null, profile, new char[0]);
            Assertions.equals("existing", credentials.value(profile.id()), "blank replacement must retain existing secret");
        } finally { TestSupport.deleteTree(root); }
    }

    private void profileSaveManual() throws Exception {
        Path root = TestSupport.tempDirectory("save-manual");
        try {
            AppPaths paths = new AppPaths(root);
            MemoryCredentialStore credentials = new MemoryCredentialStore();
            Profile encrypted = TestSupport.validProfile(root.resolve("install")).toBuilder()
                    .credentialMode(CredentialMode.ENCRYPTED).build();
            credentials.save(encrypted.id(), "old".toCharArray());
            Profile manual = encrypted.toBuilder().credentialMode(CredentialMode.MANUAL).build();
            new ProfileSaveService(new ProfileRepository(paths), credentials, new ManagedConfigService(paths))
                    .save(encrypted, manual, null);
            Assertions.isFalse(credentials.exists(manual.id()), "stored secret must be deleted after switching to manual");
        } finally { TestSupport.deleteTree(root); }
    }

    private void profileSaveChangedId() throws Exception {
        Path root = TestSupport.tempDirectory("save-id");
        try {
            Profile previous = TestSupport.validProfile(root.resolve("first"));
            Profile changed = TestSupport.validProfile(root.resolve("second"));
            ProfileSaveService service = new ProfileSaveService(new ProfileRepository(new AppPaths(root)),
                    new MemoryCredentialStore(), new ManagedConfigService(new AppPaths(root)));
            Assertions.throwsType(IllegalArgumentException.class, () -> service.save(previous, changed, null),
                    "changing identity in an update must be rejected before any write");
        } finally { TestSupport.deleteTree(root); }
    }

    private void profileSaveRollbackNew() throws Exception {
        Path root = TestSupport.tempDirectory("save-rollback-new");
        try {
            Path blockingRoot = root.resolve("not-a-directory");
            Files.writeString(blockingRoot, "block");
            AppPaths paths = new AppPaths(blockingRoot);
            MemoryCredentialStore credentials = new MemoryCredentialStore();
            Profile profile = TestSupport.validProfile(root.resolve("install")).toBuilder()
                    .credentialMode(CredentialMode.ENCRYPTED).build();
            ProfileSaveService service = new ProfileSaveService(new ProfileRepository(paths), credentials,
                    new ManagedConfigService(paths));
            Assertions.throwsType(IOException.class, () -> service.save(null, profile, "new-secret".toCharArray()),
                    "repository failure must propagate");
            Assertions.isFalse(credentials.exists(profile.id()), "new secret must be rolled back after profile failure");
        } finally { TestSupport.deleteTree(root); }
    }

    private void profileSaveRollbackExisting() throws Exception {
        Path root = TestSupport.tempDirectory("save-rollback-existing");
        try {
            AppPaths paths = new AppPaths(root);
            MemoryCredentialStore credentials = new MemoryCredentialStore();
            ProfileRepository repository = new ProfileRepository(paths);
            ManagedConfigService configs = new ManagedConfigService(paths);
            Profile previous = TestSupport.validProfile(root.resolve("install")).toBuilder()
                    .name("Before").credentialMode(CredentialMode.ENCRYPTED).build();
            credentials.save(previous.id(), "old-secret".toCharArray());
            repository.save(previous);
            Files.createDirectories(paths.profileConfig(previous.id()));
            Profile updated = previous.toBuilder().name("After").build();
            ProfileSaveService service = new ProfileSaveService(repository, credentials, configs);
            Assertions.throwsType(IOException.class,
                    () -> service.save(previous, updated, "new-secret".toCharArray()),
                    "config write failure must propagate");
            Assertions.equals("old-secret", credentials.value(previous.id()), "old credential must be restored");
            Assertions.equals(previous, repository.loadAll().profiles().get(0), "previous profile must be restored");
        } finally { TestSupport.deleteTree(root); }
    }

    private static final class MemoryCredentialStore implements CredentialStore {
        private final Map<UUID, char[]> values = new HashMap<>();
        @Override public boolean isAvailable() { return true; }
        @Override public synchronized void save(UUID profileId, char[] password) {
            values.put(profileId, Arrays.copyOf(password, password.length));
        }
        @Override public synchronized SecureChars load(UUID profileId) throws CredentialStoreException {
            char[] value = values.get(profileId);
            if (value == null) throw new CredentialStoreException("missing");
            return new SecureChars(value);
        }
        @Override public synchronized boolean exists(UUID profileId) { return values.containsKey(profileId); }
        @Override public synchronized void delete(UUID profileId) { values.remove(profileId); }
        synchronized String value(UUID profileId) { return new String(values.get(profileId)); }
    }
}
