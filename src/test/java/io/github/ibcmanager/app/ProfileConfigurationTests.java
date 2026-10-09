package io.github.ibcmanager.app;

import io.github.ibcmanager.config.IbcConfigDocument;
import io.github.ibcmanager.config.ManagedConfigService;
import io.github.ibcmanager.config.ProfileConfiguration;
import io.github.ibcmanager.config.RuntimeConfigFactory;
import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.security.CredentialStore;
import io.github.ibcmanager.security.CredentialStoreException;
import io.github.ibcmanager.security.SecureChars;
import io.github.ibcmanager.storage.ProfileCodec;
import io.github.ibcmanager.storage.ProfileRepository;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;
import io.github.ibcmanager.ui.ProfileSettingsTableModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ProfileConfigurationTests implements TestSuite {
    @Override public String name() { return "Single Profile configuration and one-time legacy import"; }
    @Override public List<NamedTest> tests() {
        return List.of(
                new NamedTest("format six requires profile ownership and rejects external sources", this::codec),
                new NamedTest("legacy managed values and credentials are preserved without decryption", this::managed),
                new NamedTest("base-file values become visible self-contained profile values", this::baseImport),
                new NamedTest("sparse legacy configuration preserves engine fallbacks", this::sparseImport),
                new NamedTest("external credentials are imported once without changing their source", this::external),
                new NamedTest("external config without a password migrates as manual", this::externalManual),
                new NamedTest("missing external source aborts import without altering the profile", this::missingExternal),
                new NamedTest("credential import failure rolls back original files", this::credentialFailure),
                new NamedTest("invalid configuration aborts and retains the exact original", this::invalid),
                new NamedTest("unsupported legacy login modes are not silently made operational", this::invalidLegacyMode),
                new NamedTest("live legacy process blocks configuration import", this::active),
                new NamedTest("invalid import journal cannot target other files", this::badJournal),
                new NamedTest("interrupted import restores original credential bytes before retry", this::interrupted),
                new NamedTest("repeat migration does not rewrite current profiles", this::idempotent),
                new NamedTest("edited generated INI cannot change current runtime or editor", this::oneAuthority),
                new NamedTest("current profiles cannot use the legacy raw save path", this::rawSave),
                new NamedTest("profile table shows imported values and excludes inactive options", this::table),
                new NamedTest("additional engine properties remain visible and removable", this::additional),
                new NamedTest("obsolete diagnostic alias is converted to current controls", this::diagnosticAlias));
    }

    private void codec() throws Exception {
        Profile p = Profile.builder().profileOnlyConfiguration(true).build();
        String text = new ProfileCodec().encode(p);
        Assertions.contains(text, "formatVersion=6", "current format");
        Assertions.equals(p, new ProfileCodec().decode(text), "round trip");
        Assertions.throwsType(IllegalArgumentException.class,
                () -> new ProfileCodec().decode(text.replace("configuration=profile", "configuration=external")), "ownership required");
        Assertions.throwsType(IllegalArgumentException.class,
                () -> new ProfileCodec().encode(p.toBuilder().baseConfigPath(Path.of("outside.ini")).build()), "external path forbidden");
        Assertions.throwsType(IllegalArgumentException.class,
                () -> new ProfileCodec().decode(text.replace("credentialMode=MANUAL", "credentialMode=EXISTING_CONFIG")), "external credential mode forbidden");
    }

    private void managed() throws Exception {
        try (Fixture f = new Fixture()) {
            f.profile = f.profile.toBuilder().credentialMode(CredentialMode.ENCRYPTED).build();
            f.saveLegacy();
            Files.createDirectories(f.paths.credentials());
            byte[] protectedBytes = {1, 2, 3, 4};
            Files.write(f.paths.credentialFile(f.profile.id()), protectedBytes);
            IbcConfigDocument doc = f.configs.loadManagedConfig(f.profile);
            doc.set("LoginDialogDisplayTimeout", "120"); doc.set("FutureOption", "kept");
            f.configs.saveManagedConfig(f.profile, doc);
            byte[] original = Files.readAllBytes(f.paths.profileFile(f.profile.id()));
            byte[] config = Files.readAllBytes(f.paths.profileConfig(f.profile.id()));
            f.migrate(); Profile updated = f.loaded();
            Assertions.isTrue(updated.profileOnlyConfiguration(), "self-contained");
            Assertions.equals(f.profile.id(), updated.id(), "UUID retained");
            Assertions.equals("120", updated.settings().get("LoginDialogDisplayTimeout"), "managed advanced value retained");
            Assertions.equals("kept", updated.settings().get("FutureOption"), "unknown key retained");
            Assertions.isTrue(Arrays.equals(protectedBytes, Files.readAllBytes(f.paths.credentialFile(updated.id()))), "no credential rewrite");
            Assertions.equals(0, f.store.loads, "no credential decryption");
            Path backup = f.backup();
            Assertions.isTrue(Arrays.equals(original, Files.readAllBytes(backup.resolve("profile.properties"))), "original profile backup");
            Assertions.isTrue(Arrays.equals(config, Files.readAllBytes(backup.resolve("config.ini"))), "original config backup");
        }
    }

    private void baseImport() throws Exception {
        try (Fixture f = new Fixture()) {
            Path base = f.root.resolve("original.ini"); Files.writeString(base, "LoginDialogDisplayTimeout=120\n");
            f.profile = f.profile.toBuilder().baseConfigPath(base).build(); f.saveLegacy(); f.migrate();
            Files.delete(base); Profile p = f.loaded();
            Assertions.equals("", p.baseConfigPath().toString(), "dependency removed");
            Assertions.equals("120", ProfileConfiguration.document(p).get("LoginDialogDisplayTimeout").orElseThrow(), "value persists without base");
            ProfileSettingsTableModel model = new ProfileSettingsTableModel(p.settings());
            int row = row(model, "LoginDialogDisplayTimeout");
            Assertions.equals("120", model.getValueAt(row, 2), "actual value displayed");
            model.reset(row);
            Assertions.equals("60", model.getValueAt(row, 2), "reset goes to included default, not hidden base");
        }
    }

    private void sparseImport() throws Exception {
        try (Fixture f = new Fixture()) {
            Path base = f.root.resolve("sparse.ini"); Files.writeString(base, "IbLoginId=test-user\n");
            f.profile = f.profile.toBuilder().baseConfigPath(base).credentialMode(CredentialMode.EXISTING_CONFIG).build();
            f.saveLegacy(); f.migrate();
            IbcConfigDocument current = ProfileConfiguration.document(f.loaded());
            Assertions.equals("", current.get("LoginDialogDisplayTimeout").orElseThrow(), "missing retains getter fallback");
            Assertions.equals("", current.get("SuppressInfoMessages").orElseThrow(), "template does not add a policy");
            ProfileSettingsTableModel model = new ProfileSettingsTableModel(f.loaded().settings());
            Assertions.equals("Profile", model.getValueAt(row(model, "SuppressInfoMessages"), 3), "explicit imported fallback visible");
        }
    }

    private void external() throws Exception {
        try (Fixture f = new Fixture()) {
            Path base = f.root.resolve("external.ini");
            String text = "IbLoginId=external-user\nIbPassword=test-only-password\nLoginDialogDisplayTimeout=120\n";
            Files.writeString(base, text);
            f.profile = f.profile.toBuilder().baseConfigPath(base).credentialMode(CredentialMode.EXISTING_CONFIG).build();
            f.saveLegacy(); f.migrate(); Profile p = f.loaded();
            Assertions.equals(CredentialMode.ENCRYPTED, p.credentialMode(), "external password imported");
            Assertions.equals("external-user", p.username(), "actual username retained");
            Assertions.equals(text, Files.readString(base), "external file untouched");
            try (SecureChars secret = f.store.load(p.id())) {
                Assertions.equals("test-only-password", secret.revealAsString(), "password association retained");
            }
            Assertions.isFalse(Files.readString(f.paths.profileFile(p.id())).contains("test-only-password"), "profile contains no password");
            Assertions.isFalse(Files.readString(f.paths.profileConfig(p.id())).contains("test-only-password"), "projection contains no password");
            Files.delete(base);
            try (SecureChars secret = f.store.load(p.id()); var lease = new RuntimeConfigFactory(f.paths, f.configs).create(p, secret)) {
                Assertions.equals("test-only-password", IbcConfigDocument.parseBytes(Files.readAllBytes(lease.path())).get("IbPassword").orElseThrow(), "runtime no longer needs external file");
            }
        }
    }

    private void externalManual() throws Exception {
        try (Fixture f = new Fixture()) {
            Path base = f.root.resolve("external.ini"); Files.writeString(base, "IbLoginId=external-user\nIbPassword=\n");
            f.profile = f.profile.toBuilder().baseConfigPath(base).credentialMode(CredentialMode.EXISTING_CONFIG).build();
            f.saveLegacy(); f.migrate();
            Assertions.equals(CredentialMode.MANUAL, f.loaded().credentialMode(), "no invented password");
            Assertions.isFalse(f.store.exists(f.profile.id()), "no credential created");
        }
    }

    private void missingExternal() throws Exception {
        try (Fixture f = new Fixture()) {
            f.profile = f.profile.toBuilder().baseConfigPath(f.root.resolve("missing.ini"))
                    .credentialMode(CredentialMode.EXISTING_CONFIG).build(); f.saveLegacy();
            byte[] original = Files.readAllBytes(f.paths.profileFile(f.profile.id()));
            Assertions.throwsType(IOException.class, f::migrate, "missing source rejected");
            Assertions.isTrue(Arrays.equals(original, Files.readAllBytes(f.paths.profileFile(f.profile.id()))), "original restored");
        }
    }

    private void credentialFailure() throws Exception {
        try (Fixture f = new Fixture()) {
            Path base = f.root.resolve("external.ini"); Files.writeString(base, "IbLoginId=external\nIbPassword=fixture\n");
            f.profile = f.profile.toBuilder().baseConfigPath(base).credentialMode(CredentialMode.EXISTING_CONFIG).build(); f.saveLegacy();
            byte[] original = Files.readAllBytes(f.paths.profileFile(f.profile.id())); f.store.failSave = true;
            Assertions.throwsType(IOException.class, f::migrate, "credential write failure propagated");
            Assertions.isTrue(Arrays.equals(original, Files.readAllBytes(f.paths.profileFile(f.profile.id()))), "profile unchanged");
            Assertions.isFalse(f.store.exists(f.profile.id()), "no partial credential");
        }
    }

    private void invalid() throws Exception {
        try (Fixture f = new Fixture()) {
            f.saveLegacy(); Files.writeString(f.paths.profileConfig(f.profile.id()), "LoginDialogDisplayTimeout=nonsense\n");
            byte[] original = Files.readAllBytes(f.paths.profileFile(f.profile.id()));
            Assertions.throwsType(IOException.class, f::migrate, "invalid value rejected");
            Assertions.isTrue(Arrays.equals(original, Files.readAllBytes(f.paths.profileFile(f.profile.id()))), "profile restored");
            Assertions.equals("LoginDialogDisplayTimeout=nonsense\n", Files.readString(f.paths.profileConfig(f.profile.id())), "config restored exactly");
        }
    }

    private void invalidLegacyMode() throws Exception {
        for (String invalidSetting : List.of("ReadOnlyLogin=yes", "FIX=yes", "logindialogdisplaytimeout=9")) {
            try (Fixture f = new Fixture()) {
                Path source = f.root.resolve("invalid-external.ini");
                Files.writeString(source, "IbLoginId=test\nIbPassword=test-password\n" + invalidSetting + "\n");
                f.profile = f.profile.toBuilder().credentialMode(CredentialMode.EXISTING_CONFIG).baseConfigPath(source).build();
                f.saveLegacy();
                byte[] original = Files.readAllBytes(f.paths.profileFile(f.profile.id()));
                Assertions.throwsType(IOException.class, f::migrate, "invalid mode is rejected before replacing credentials");
                Assertions.isTrue(Arrays.equals(original, Files.readAllBytes(f.paths.profileFile(f.profile.id()))), "profile restored");
                Assertions.isFalse(f.store.exists(f.profile.id()), "no credential was imported from unsupported mode");
            }
        }
    }

    private void active() throws Exception {
        try (Fixture f = new Fixture()) {
            f.saveLegacy(); ProcessHandle process = ProcessHandle.current();
            Files.createDirectories(f.paths.runtimeDirectory(f.profile.id()));
            Files.writeString(f.paths.runtimeState(f.profile.id()), "pid=" + process.pid() + "\nstartedAt="
                    + process.info().startInstant().orElseThrow() + "\nfingerprint=\n");
            Assertions.throwsType(IOException.class, f::migrate, "live process rejected");
            Assertions.isFalse(f.loaded().profileOnlyConfiguration(), "legacy remains unchanged");
        }
    }

    private void badJournal() throws Exception {
        try (Fixture f = new Fixture()) {
            f.saveLegacy(); Files.writeString(f.marker(), "../../outside\ntrue\ntrue\n");
            Assertions.throwsType(IOException.class, f::migrate, "unsafe journal rejected");
            Assertions.isFalse(f.loaded().profileOnlyConfiguration(), "original retained");
        }
    }

    private void interrupted() throws Exception {
        try (Fixture f = new Fixture()) {
            f.saveLegacy(); Files.createDirectories(f.paths.credentials());
            byte[] bytes = {4, 3, 2, 1}; Files.write(f.paths.credentialFile(f.profile.id()), bytes);
            Path backup = f.paths.profileDirectory(f.profile.id()).resolve("upgrade-profile-config-backup-" + UUID.randomUUID());
            Files.createDirectories(backup);
            Files.copy(f.paths.profileFile(f.profile.id()), backup.resolve("profile.properties"));
            Files.copy(f.paths.profileConfig(f.profile.id()), backup.resolve("config.ini"));
            Files.copy(f.paths.credentialFile(f.profile.id()), backup.resolve("credential.dpapi"));
            Files.writeString(f.marker(), backup.getFileName() + "\ntrue\ntrue\n");
            Files.writeString(f.paths.profileFile(f.profile.id()), "partial");
            Files.writeString(f.paths.profileConfig(f.profile.id()), "broken");
            Files.write(f.paths.credentialFile(f.profile.id()), new byte[] {9});
            f.migrate();
            Assertions.isTrue(f.loaded().profileOnlyConfiguration(), "restored then migrated");
            Assertions.isTrue(Arrays.equals(bytes, Files.readAllBytes(f.paths.credentialFile(f.profile.id()))), "original encrypted data restored");
            Assertions.isFalse(Files.exists(f.marker()), "transaction finished");
        }
    }

    private void idempotent() throws Exception {
        try (Fixture f = new Fixture()) {
            f.saveLegacy(); f.migrate();
            var time = Files.getLastModifiedTime(f.paths.profileFile(f.profile.id()));
            byte[] bytes = Files.readAllBytes(f.paths.profileFile(f.profile.id()));
            f.migrate();
            Assertions.equals(time, Files.getLastModifiedTime(f.paths.profileFile(f.profile.id())), "no rewrite");
            Assertions.isTrue(Arrays.equals(bytes, Files.readAllBytes(f.paths.profileFile(f.profile.id()))), "same bytes");
        }
    }

    private void oneAuthority() throws Exception {
        try (Fixture f = new Fixture()) {
            f.saveLegacy(); f.migrate(); Profile p = f.loaded();
            Files.writeString(f.paths.profileConfig(p.id()), "CommandServerPort=9999\nLoginDialogDisplayTimeout=999\n");
            Assertions.equals(p, f.configs.synchronizeEditableProfile(p), "editor not overridden by INI");
            Assertions.equals(Integer.toString(p.commandServerPort()), f.configs.loadRuntimeBase(p).get("CommandServerPort").orElseThrow(), "profile owns launch");
            Files.delete(f.paths.profileConfig(p.id()));
            try (var lease = new RuntimeConfigFactory(f.paths, f.configs).create(p, null)) {
                Assertions.isTrue(Files.isRegularFile(lease.path()), "launch independent of projection file");
            }
        }
    }

    private void rawSave() throws Exception {
        try (Fixture f = new Fixture()) {
            f.saveLegacy(); f.migrate(); Profile p = f.loaded();
            Assertions.throwsType(IOException.class, () -> f.configs.saveManagedConfig(p, IbcConfigDocument.parse("")), "raw save blocked");
            Assertions.throwsType(IOException.class, () -> new ProfileSaveService(new ProfileRepository(f.paths), f.store, f.configs)
                    .saveManagedConfig(p, IbcConfigDocument.parse("")), "transactional raw save blocked too");
        }
    }

    private void table() {
        ProfileSettingsTableModel model = new ProfileSettingsTableModel(Map.of("LoginDialogDisplayTimeout", "120"));
        Assertions.equals("120", model.getValueAt(row(model, "LoginDialogDisplayTimeout"), 2), "effective value shown");
        for (String key : List.of("SecondFactorAuthenticationExitInterval", "StoreSettingsOnServer", "ReadOnlyLogin", "ConfirmOrderIdReset")) {
            Assertions.equals(-1, row(model, key), "unused key removed: " + key);
        }
        Assertions.isTrue(row(model, "ReadOnlyApi") >= 0, "API permission control is retained");
    }

    private void additional() {
        ProfileSettingsTableModel model = new ProfileSettingsTableModel(Map.of("CustomOption", "abc"));
        Assertions.equals("abc", model.getValueAt(row(model, "CustomOption"), 2), "unknown value visible");
        model.addProperty("OtherOption", "xyz");
        Assertions.equals("xyz", model.settings().get("OtherOption"), "additional value stored");
        model.reset(row(model, "CustomOption"));
        Assertions.isFalse(model.settings().containsKey("CustomOption"), "custom property removed explicitly");
        Assertions.throwsType(IllegalArgumentException.class, () -> model.addProperty("IbPassword", "secret"), "cannot add secret field");
        Assertions.throwsType(IllegalArgumentException.class, () -> model.addProperty("SecondFactorAuthenticationTimeout", "42"), "cannot duplicate generated field");
    }

    private void diagnosticAlias() throws Exception {
        try (Fixture f = new Fixture()) {
            f.saveLegacy(); IbcConfigDocument doc = f.configs.loadManagedConfig(f.profile);
            doc.set("LogComponents", "yes"); f.configs.saveManagedConfig(f.profile, doc); f.migrate();
            IbcConfigDocument current = ProfileConfiguration.document(f.loaded());
            Assertions.equals("all", current.get("LogStructureScope").orElseThrow(), "legacy scope retained");
            Assertions.equals("open", current.get("LogStructureWhen").orElseThrow(), "legacy timing retained");
            Assertions.isTrue(current.get("LogComponents").isEmpty(), "old alias no longer overrides controls");
        }
    }

    private static int row(ProfileSettingsTableModel model, String key) {
        for (int i = 0; i < model.getRowCount(); i++) if (model.definitionAt(i).key().equals(key)) return i;
        return -1;
    }

    private static final class Fixture implements AutoCloseable {
        final Path root = TestSupport.tempDirectory("profile-only");
        final AppPaths paths = new AppPaths(root.resolve("data"));
        final ManagedConfigService configs = new ManagedConfigService(paths);
        final TestStore store = new TestStore(paths);
        Profile profile = TestSupport.validProfile(root);
        Fixture() throws Exception { }
        void saveLegacy() throws Exception {
            new ProfileRepository(paths).save(profile);
            if (profile.credentialMode() != CredentialMode.EXISTING_CONFIG) configs.ensureManagedConfig(profile);
        }
        void migrate() throws IOException { new ProfileConfigurationUpgradeService(paths, store).migrate(); }
        Profile loaded() throws IOException { return new ProfileRepository(paths).loadAll().profiles().get(0); }
        Path marker() { return paths.profileDirectory(profile.id()).resolve("upgrade-profile-config.pending"); }
        Path backup() throws IOException {
            try (var children = Files.list(paths.profileDirectory(profile.id()))) {
                return children.filter(p -> p.getFileName().toString().startsWith("upgrade-profile-config-backup-")).findFirst().orElseThrow();
            }
        }
        @Override public void close() throws IOException { TestSupport.deleteTree(root); }
    }

    /** Test-only encoding; production uses Windows DPAPI. */
    private static final class TestStore implements CredentialStore {
        final AppPaths paths; boolean failSave; int loads;
        TestStore(AppPaths paths) { this.paths = paths; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean exists(UUID id) { return Files.exists(paths.credentialFile(id)); }
        @Override public void save(UUID id, char[] password) throws CredentialStoreException {
            if (failSave) throw new CredentialStoreException("simulated credential write failure");
            try {
                Files.createDirectories(paths.credentials());
                Files.write(paths.credentialFile(id), Base64.getEncoder().encode(new String(password).getBytes(StandardCharsets.UTF_8)));
            } catch (IOException e) { throw new CredentialStoreException("fixture write failure", e); }
        }
        @Override public SecureChars load(UUID id) throws CredentialStoreException {
            loads++;
            try { return new SecureChars(new String(Base64.getDecoder().decode(Files.readAllBytes(paths.credentialFile(id))), StandardCharsets.UTF_8).toCharArray()); }
            catch (IOException e) { throw new CredentialStoreException("fixture read failure", e); }
        }
        @Override public void delete(UUID id) throws CredentialStoreException {
            try { Files.deleteIfExists(paths.credentialFile(id)); }
            catch (IOException e) { throw new CredentialStoreException("fixture delete failure", e); }
        }
    }
}
