package io.github.ibcmanager.diagnostics;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.app.Version;
import io.github.ibcmanager.config.ManagedConfigService;
import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.ProfileStatus;
import io.github.ibcmanager.model.PortListenerState;
import io.github.ibcmanager.model.RuntimeState;
import io.github.ibcmanager.security.UnavailableCredentialStore;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;
import io.github.ibcmanager.validation.ProfileValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class DiagnosticsTests implements TestSuite {
    private static final Instant NOW = Instant.parse("2026-08-01T08:15:30Z");

    @Override public String name() { return "Diagnostic bundle creation and redaction"; }

    @Override
    public List<NamedTest> tests() {
        return List.of(
                new NamedTest("creates a complete deterministic diagnostic bundle", this::completeBundle),
                new NamedTest("sanitizes unsafe profile names in the bundle filename", this::safeFilename),
                new NamedTest("redacts configuration status and log secrets", this::redactsSecrets),
                new NamedTest("masks usernames without losing diagnostic context", this::masksUsername),
                new NamedTest("reports missing logs without failing export", this::missingLogs),
                new NamedTest("limits each log tail to two MiB", this::logTailLimit),
                new NamedTest("redacts existing config without modifying its source", this::existingConfig),
                new NamedTest("captures configuration read errors inside the bundle", this::configError),
                new NamedTest("includes validation errors for invalid profiles", this::validationOutput),
                new NamedTest("creates unique bundles generated in the same second", this::sameSecondUnique),
                new NamedTest("removes temporary output after a failed final move", this::failedMoveCleanup));
    }

    private void completeBundle() throws Exception {
        try (Fixture fixture = new Fixture("Paper Gateway")) {
            Files.createDirectories(fixture.paths.logs());
            Files.writeString(fixture.paths.profileLog(fixture.profile.id()), "profile line\n");
            Files.writeString(fixture.paths.appLog(), "manager line\n");
            Path bundle = fixture.service.create(fixture.profile, fixture.status("Ready"));
            Assertions.fileExists(bundle, "diagnostic ZIP must be created");
            Assertions.equals("IBC-Manager-Diagnostics-Paper-Gateway-20260801-081530.zip",
                    bundle.getFileName().toString(), "filename must use safe name and fixed UTC timestamp");
            Map<String, String> entries = readTextEntries(bundle);
            Assertions.equals(List.of("manifest.txt", "profile.txt", "validation.txt", "config-redacted.ini",
                            "profile-log-tail.txt", "manager-log-tail.txt"),
                    new ArrayList<>(entries.keySet()), "bundle entry order must be stable");
            Assertions.contains(entries.get("manifest.txt"), "Manager version: " + Version.VERSION,
                    "manifest must identify manager version");
            Assertions.contains(entries.get("manifest.txt"), "IBC release channel: " + Version.IBC_RELEASE_CHANNEL,
                    "manifest must identify the IBC release channel");
            Assertions.contains(entries.get("manifest.txt"),
                    "IBC compatibility floor: " + Version.IBC_MINIMUM_SUPPORTED_VERSION,
                    "manifest must identify the IBC compatibility floor");
            Assertions.contains(entries.get("manifest.txt"), "Generated UTC: " + NOW,
                    "manifest must use supplied UTC clock");
            Assertions.contains(entries.get("profile.txt"), "Target: IB Gateway", "profile summary must include target");
            Assertions.contains(entries.get("profile-log-tail.txt"), "profile line", "profile log must be included");
            Assertions.contains(entries.get("manager-log-tail.txt"), "manager line", "manager log must be included");
            try (ZipFile zip = new ZipFile(bundle.toFile(), StandardCharsets.UTF_8)) {
                var enumeration = zip.entries();
                while (enumeration.hasMoreElements()) {
                    ZipEntry entry = enumeration.nextElement();
                    Assertions.isTrue(entry.getTime() <= 315532800000L,
                            "entry timestamps must be normalized for reproducible bundles: " + entry.getName());
                }
            }
        }
    }

    private void safeFilename() throws Exception {
        try (Fixture fixture = new Fixture("  ../Live: NBIS | account?  ")) {
            Path bundle = fixture.service.create(fixture.profile, fixture.status("Stopped"));
            String name = bundle.getFileName().toString();
            Assertions.equals("IBC-Manager-Diagnostics-..-Live-NBIS-account-20260801-081530.zip", name,
                    "unsafe filename characters must be normalized");
            Assertions.notContains(name, "/", "bundle filename must not contain path separators");
            Assertions.notContains(name, ":", "bundle filename must be portable on Windows");
        }
    }

    private void redactsSecrets() throws Exception {
        try (Fixture fixture = new Fixture("Secrets")) {
            Path imported = fixture.root.resolve("imported.ini");
            Files.writeString(imported, "IbLoginId=other\nIbPassword=ConfigSecret123\nFIXPassword=FixSecret456\n");
            fixture.profile = fixture.profile.toBuilder().baseConfigPath(imported).build();
            Files.createDirectories(fixture.paths.logs());
            Files.writeString(fixture.paths.profileLog(fixture.profile.id()),
                    "IbPassword=ProfileLogSecret\nStartIBC /PW:CommandSecret /Mode:paper\n");
            Files.writeString(fixture.paths.appLog(), "FIXPassword=ManagerLogSecret\n");
            Path bundle = fixture.service.create(fixture.profile,
                    fixture.status("StartIBC /PW:StatusSecret /Mode:paper"));
            Map<String, String> entries = readTextEntries(bundle);
            String all = String.join("\n", entries.values());
            for (String secret : List.of("ConfigSecret123", "FixSecret456", "ProfileLogSecret",
                    "CommandSecret", "ManagerLogSecret", "StatusSecret")) {
                Assertions.notContains(all, secret, "diagnostics must not contain secret " + secret);
            }
            Assertions.contains(all, "[REDACTED]", "redaction markers must make sanitization visible");
            Assertions.contains(entries.get("config-redacted.ini"), "IbPassword=",
                    "managed persistent config must retain an explicitly blank password setting");
        }
    }

    private void masksUsername() throws Exception {
        try (Fixture fixture = new Fixture("Username")) {
            fixture.profile = fixture.profile.toBuilder().username("long-user@example.test").build();
            String summary = readTextEntries(fixture.service.create(fixture.profile, fixture.status("OK"))).get("profile.txt");
            Assertions.contains(summary, "Username: l***t", "only username edges may remain");
            Assertions.notContains(summary, "long-user@example.test", "full username must not be exported");

            Profile shortName = fixture.profile.toBuilder().username("ab").build();
            String shortSummary = readTextEntries(fixture.service.create(shortName, fixture.status("OK"))).get("profile.txt");
            Assertions.contains(shortSummary, "Username: **", "short usernames must be fully hidden");
        }
    }

    private void missingLogs() throws Exception {
        try (Fixture fixture = new Fixture("No Logs")) {
            Map<String, String> entries = readTextEntries(fixture.service.create(fixture.profile, fixture.status("Stopped")));
            Assertions.equals("Log file does not exist.\n", entries.get("profile-log-tail.txt"),
                    "missing profile log must be represented clearly");
            Assertions.equals("Log file does not exist.\n", entries.get("manager-log-tail.txt"),
                    "missing manager log must be represented clearly");
        }
    }

    private void logTailLimit() throws Exception {
        try (Fixture fixture = new Fixture("Large Log")) {
            Files.createDirectories(fixture.paths.logs());
            byte[] prefix = "SHOULD-NOT-APPEAR\n".repeat(8192).getBytes(StandardCharsets.UTF_8);
            byte[] tail = "tail-data-0123456789\n".repeat(110_000).getBytes(StandardCharsets.UTF_8);
            byte[] all = new byte[prefix.length + tail.length];
            System.arraycopy(prefix, 0, all, 0, prefix.length);
            System.arraycopy(tail, 0, all, prefix.length, tail.length);
            Files.write(fixture.paths.profileLog(fixture.profile.id()), all);
            String exported = readTextEntries(fixture.service.create(fixture.profile, fixture.status("OK")))
                    .get("profile-log-tail.txt");
            Assertions.isTrue(exported.getBytes(StandardCharsets.UTF_8).length <= 2 * 1024 * 1024,
                    "exported log tail must not exceed two MiB");
            Assertions.contains(exported, "tail-data", "newest log content must be retained");
            Assertions.notContains(exported, "SHOULD-NOT-APPEAR", "old content beyond the tail limit must be omitted");
        }
    }

    private void existingConfig() throws Exception {
        try (Fixture fixture = new Fixture("Existing")) {
            Path existing = fixture.root.resolve("external-config.ini");
            String source = "# original\nIbLoginId=external-user\nIbPassword=ExternalSecret\nUnknownFuture=value\n";
            Files.writeString(existing, source);
            fixture.profile = fixture.profile.toBuilder()
                    .credentialMode(CredentialMode.EXISTING_CONFIG)
                    .baseConfigPath(existing)
                    .build();
            String exported = readTextEntries(fixture.service.create(fixture.profile, fixture.status("OK")))
                    .get("config-redacted.ini");
            Assertions.notContains(exported, "ExternalSecret", "existing config secret must be redacted");
            Assertions.contains(exported, "UnknownFuture=value", "unknown settings must survive diagnostic rendering");
            Assertions.equals(source, Files.readString(existing), "diagnostic export must never modify source config");
        }
    }

    private void configError() throws Exception {
        try (Fixture fixture = new Fixture("Broken Existing")) {
            Path missing = fixture.root.resolve("missing.ini");
            fixture.profile = fixture.profile.toBuilder()
                    .credentialMode(CredentialMode.EXISTING_CONFIG)
                    .baseConfigPath(missing)
                    .build();
            Map<String, String> entries = readTextEntries(fixture.service.create(fixture.profile, fixture.status("OK")));
            Assertions.isTrue(entries.containsKey("config-error.txt"), "config read error must be captured as an entry");
            Assertions.isFalse(entries.containsKey("config-redacted.ini"), "invalid source must not create a fake config entry");
            Assertions.contains(entries.get("config-error.txt"), "Could not read configuration",
                    "config error must be understandable");
        }
    }

    private void validationOutput() throws Exception {
        try (Fixture fixture = new Fixture("Invalid")) {
            fixture.profile = fixture.profile.toBuilder().apiPort(70000).commandServerPort(70000).build();
            String validation = readTextEntries(fixture.service.create(fixture.profile, fixture.status("Invalid")))
                    .get("validation.txt");
            Assertions.contains(validation, "ERROR [apiPort]", "API validation error must be included");
            Assertions.contains(validation, "ERROR [commandServerPort]", "command port validation error must be included");
            Assertions.contains(validation, "must differ", "cross-field port error must be included");
        }
    }

    private void sameSecondUnique() throws Exception {
        try (Fixture fixture = new Fixture("Duplicate Time")) {
            Path first = fixture.service.create(fixture.profile, fixture.status("First"));
            Path second = fixture.service.create(fixture.profile, fixture.status("Second"));
            Assertions.notEquals(first, second, "same-second exports must not overwrite or fail");
            Assertions.fileExists(first, "first bundle must remain intact");
            Assertions.fileExists(second, "second bundle must be created with a unique name");
        }
    }

    private void failedMoveCleanup() throws Exception {
        try (Fixture fixture = new Fixture("Move Failure")) {
            Files.createDirectories(fixture.paths.diagnostics().getParent());
            Files.writeString(fixture.paths.diagnostics(), "not a directory");
            Assertions.throwsType(IOException.class,
                    () -> fixture.service.create(fixture.profile, fixture.status("Failure")),
                    "an unusable diagnostics path must fail clearly");
            try (var stream = Files.walk(fixture.paths.root())) {
                long temporary = stream.filter(path -> path.getFileName().toString().startsWith(".diagnostics-")).count();
                Assertions.equals(0L, temporary, "failed exports must not leave temporary ZIP files");
            }
        }
    }

    private static Map<String, String> readTextEntries(Path bundle) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(bundle.toFile(), StandardCharsets.UTF_8)) {
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                try (var stream = zip.getInputStream(entry)) {
                    entries.put(entry.getName(), new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return entries;
    }

    private static final class Fixture implements AutoCloseable {
        private final Path root;
        private final AppPaths paths;
        private final DiagnosticBundleService service;
        private Profile profile;

        private Fixture(String name) throws IOException {
            root = TestSupport.tempDirectory("diagnostics");
            paths = new AppPaths(root.resolve("data"));
            profile = TestSupport.validProfile(root.resolve("install")).toBuilder().name(name).build();
            ManagedConfigService configs = new ManagedConfigService(paths);
            ProfileValidator validator = new ProfileValidator(new UnavailableCredentialStore("test"));
            service = new DiagnosticBundleService(paths, configs, validator,
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }

        private ProfileStatus status(String message) {
            return new ProfileStatus(profile.id(), RuntimeState.RUNNING, true, true,
                    PortListenerState.LISTENING, 1234,
                    NOW.minusSeconds(60), null, message, NOW);
        }

        @Override
        public void close() throws IOException {
            TestSupport.deleteTree(root);
        }
    }
}
