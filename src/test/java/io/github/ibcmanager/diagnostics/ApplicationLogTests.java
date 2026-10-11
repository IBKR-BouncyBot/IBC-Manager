package io.github.ibcmanager.diagnostics;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.config.ManagedConfigService;
import io.github.ibcmanager.model.PortListenerState;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.ProfileStatus;
import io.github.ibcmanager.model.RuntimeState;
import io.github.ibcmanager.model.TargetType;
import io.github.ibcmanager.runtime.OfflineApplicationLayoutResolver;
import io.github.ibcmanager.security.UnavailableCredentialStore;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;
import io.github.ibcmanager.validation.ProfileValidator;
import io.github.ibcmanager.workaround.ManagedRuntimeService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/** Export integration tests; temporary fixtures only, no real installation or broker connection. */
public final class ApplicationLogTests implements TestSuite {
    @Override public String name() { return "Accessible broker logs: discovery, bounded export, redaction and isolation"; }
    @Override public List<NamedTest> tests() {
        List<NamedTest> tests = new ArrayList<>(List.of(
                new NamedTest("diagnostics contain launcher current rotation and account-session plaintext", this::launcher),
                new NamedTest("encrypted broker logs are inventoried but never copied without redaction", this::encrypted),
                new NamedTest("runtime metadata and VM options are included without state credentials", this::runtimeDetails),
                new NamedTest("external password token username account and encryption fields are masked", this::redaction),
                new NamedTest("missing and non-text logs do not prevent the rest of the bundle", this::binary),
                new NamedTest("no recursive scan of unrelated directories or credentials", this::scope),
                new NamedTest("same settings and installation directory is scanned only once", this::duplicates),
                new NamedTest("TWS plaintext logs use the selected TWS installation", this::tws),
                new NamedTest("external-log count is bounded and current launcher wins", this::fileBudget),
                new NamedTest("total external-log byte budget is enforced", this::byteBudget),
                new NamedTest("oversized plaintext tails drop earlier and partial lines", this::tail),
                new NamedTest("giant sensitive line is omitted not partially disclosed", this::oversizeLine),
                new NamedTest("missing application layout still exports configured launcher log", this::missingLayout),
                new NamedTest("blank settings path never scans the current working directory", this::blankSettings),
                new NamedTest("native launcher identity is read only and cannot change application flags", () -> identity(false, false)),
                new NamedTest("native launcher UTF16 identity is readable without executing it", () -> identity(true, false)),
                new NamedTest("native launcher unaligned UTF16 identity is supported", () -> identity(true, true)),
                new NamedTest("native launcher property spanning scan blocks is not truncated", this::identityBoundary),
                new NamedTest("install4j javaOptions metadata decodes built-in XML references before redaction", this::installerMetadata),
                new NamedTest("unknown XML references never disclose unredacted installer data", this::unknownInstallerEntity),
                new NamedTest("native crash-log environment credentials and all token hashes are redacted", this::crashRedaction)));
        for (String mode : List.of("file", "directory", "root", "ancestor")) {
            tests.add(new NamedTest("application diagnostics never follow a symbolic " + mode, () -> links(mode)));
        }
        for (String encoding : List.of("utf8", "utf16le", "utf16be")) {
            tests.add(new NamedTest("external log decoding retains complete " + encoding + " text", () -> encoding(encoding)));
        }
        return tests;
    }

    private void identity(boolean utf16, boolean odd) throws Exception {
        try (Fixture f = new Fixture()) {
            String args = "\0-DinstallDir=C:\\Jts\\ibgateway\\1050\\\\\0-Dchannel=latest\0-DfullVersion=10501e\0"
                    + "-DinstallerVersion=4.154\0-DproductName=IB Gateway\0-Dpassword=NATIVE-SECRET\0";
            byte[] text = args.getBytes(utf16 ? StandardCharsets.UTF_16LE : StandardCharsets.ISO_8859_1);
            byte[] data = new byte[text.length + (odd ? 1 : 0)];
            System.arraycopy(text, 0, data, odd ? 1 : 0, text.length);
            Path exe = f.layout.programPath().resolve("ibgateway.exe"); Files.write(exe, data);
            byte[] options = Files.readAllBytes(f.layout.vmOptionsPath());
            String evidence = f.export().get("runtime-details/launcher-identity.txt");
            Assertions.contains(evidence, "-DfullVersion=10501e", "read exact installed identity, not 10501f example");
            Assertions.contains(evidence, "-DproductName=IB Gateway", "do not borrow TWS name");
            Assertions.contains(evidence, "-Dchannel=latest", "do not force stable");
            Assertions.notContains(evidence, "NATIVE-SECRET", "only identity whitelist exported");
            Assertions.isTrue(java.util.Arrays.equals(options, Files.readAllBytes(f.layout.vmOptionsPath())), "never applies flags");
            Assertions.isTrue(java.util.Arrays.equals(data, Files.readAllBytes(exe)), "never patches executable");
        }
    }
    private void identityBoundary() throws Exception {
        try (Fixture f = new Fixture()) {
            String prefix = "x".repeat(65530);
            Path exe = f.layout.programPath().resolve("ibgateway.exe");
            Files.writeString(exe, prefix + "\0-DfullVersion=10501e\0", StandardCharsets.ISO_8859_1);
            var result = LauncherMetadata.scan(exe);
            Assertions.equals(java.util.Set.of("-DfullVersion=10501e"), result.properties(), "one complete property only");
            Assertions.equals(Files.size(exe), result.scanned(), "scanned byte count");
        }
    }
    private void installerMetadata() throws Exception {
        try (Fixture f = new Fixture()) {
            Files.writeString(f.layout.install4jPath().resolve("i4jparams.conf"),
                    "<config>\n<variable name=\"privateVariable\" value=\"DO-NOT-COPY\" />\n"
                    + "<variable name=\"javaOptions\" value=\"-DfullVersion=10501e -Dchannel=latest -Dpassword=&quot;XML-SECRET&quot; -Dtoken=&#34;TOKEN-XML&#34;\" />\n</config>\n");
            var evidence = f.export();
            String detail = evidence.get("runtime-details/install4j-java-options.txt");
            Assertions.contains(detail, "-DfullVersion=10501e", "real option value retained");
            for (String secret : List.of("XML-SECRET", "TOKEN-XML", "DO-NOT-COPY"))
                Assertions.notContains(String.join("\n", evidence.values()), secret, "private attributes never copied");
        }
    }
    private void unknownInstallerEntity() throws Exception {
        try (Fixture f = new Fixture()) {
            Files.writeString(f.layout.install4jPath().resolve("i4jparams.conf"),
                    "<variable name=\"javaOptions\" value=\"-Dpassword=&external;PRIVATE\" />");
            var evidence = f.export();
            Assertions.isFalse(evidence.containsKey("runtime-details/install4j-java-options.txt"), "unsafe metadata omitted");
            Assertions.notContains(String.join("\n", evidence.values()), "PRIVATE", "no raw fallback");
            Assertions.contains(evidence.get("application-log-inventory.txt"), "Install4j javaOptions unavailable", "omission explained");
        }
    }
    private void crashRedaction() throws Exception {
        try (Fixture f = new Fixture()) {
            f.write("hs_err_pid999.log", "Environment:\nAWS_SECRET_ACCESS_KEY=ENV-SECRET\nSERVICE_TOKEN=ENV-TOKEN\n"
                    + "Authorization: Bearer HEADER-SECRET\nshortTokenHashes=HASH-ONE;HASH-TWO]\nuserDirname=FOLDER-SECRET,\n");
            String logs = external(f.export());
            for (String secret : List.of("ENV-SECRET", "ENV-TOKEN", "HEADER-SECRET", "HASH-ONE", "HASH-TWO", "FOLDER-SECRET"))
                Assertions.notContains(logs, secret, "credentials in native crash logs masked");
            Assertions.contains(logs, "Environment:", "crash section is present");
        }
    }

    private void launcher() throws Exception {
        try (Fixture f = new Fixture()) {
            f.write("launcher.log", "LATEST-LAUNCH\n"); f.write("launcher.20261010.log", "PREVIOUS-LAUNCH\n");
            f.write("logs/hs_err_pid123.log", "JVM-CRASH\n");
            f.write("a".repeat(40) + "/ibgateway.20261011.log", "POSTAUTH invalid twsInfo\n");
            Map<String, String> entries = f.export(); String logs = external(entries);
            for (String expected : List.of("LATEST-LAUNCH", "PREVIOUS-LAUNCH", "JVM-CRASH", "POSTAUTH invalid twsInfo"))
                Assertions.contains(logs, expected, "readable evidence exported");
            Assertions.equals("LATEST-LAUNCH\n", Files.readString(f.settings.resolve("launcher.log")), "no source edits");
            Assertions.contains(entries.get("application-log-inventory.txt"), "launcher.log", "source mapping");
        }
    }
    private void encrypted() throws Exception {
        try (Fixture f = new Fixture()) {
            f.write("b".repeat(40) + "/ibgateway.20261011.193156.ibgzenc", "ENCRYPTED-PRIVATE-BYTES");
            f.write("ibgateway.log.gz", "ARCHIVE-PRIVATE-BYTES");
            f.write("gateway-exported-logs.txt", "PLAINTEXT-REJECTION invalid twsInfo\n");
            Map<String, String> entries = f.export(); String text = String.join("\n", entries.values());
            Assertions.notContains(text, "ENCRYPTED-PRIVATE-BYTES", "unredactable bytes never shared");
            Assertions.notContains(text, "ARCHIVE-PRIVATE-BYTES", "compressed bytes not parsed as text");
            Assertions.contains(text, "export plaintext", "clear missing-evidence instructions");
            Assertions.contains(external(entries), "PLAINTEXT-REJECTION", "accessible export included");
            Assertions.notContains(text, "b".repeat(40), "session directory pseudonymized");
        }
    }
    private void runtimeDetails() throws Exception {
        try (Fixture f = new Fixture()) {
            Path dir = ManagedRuntimeService.directory(f.layout.vmOptionsPath());
            Path payload = Files.createDirectories(dir.resolve("payload/jbr"));
            Files.writeString(payload.resolve("release"), "JAVA_VERSION=\"25.0.4\"\n");
            Files.writeString(dir.resolve("payload/download-provenance.txt"), "JBR 25.0.4-b508.27\nJavaFX 25.0.2\n");
            Files.writeString(dir.resolve("state.properties"), "PRIVATE-OWNERSHIP-CONTENT");
            Files.writeString(dir.resolve("pending.properties"), "PRIVATE-PREVIOUS-PROFILE");
            Files.createDirectories(f.paths.logs());
            Files.writeString(f.paths.logs().resolve("ibc-manager-1.log"), "MANAGER-PREVIOUS\n");
            Files.writeString(f.layout.vmOptionsPath(), "-Xmx768m\n-Dpassword=VM-SECRET\n");
            Map<String, String> entries = f.export(); String text = String.join("\n", entries.values());
            Assertions.contains(entries.get("runtime-details/download-provenance.txt"), "25.0.4-b508.27", "exact build");
            Assertions.contains(entries.get("runtime-details/managed-java-release.txt"), "25.0.4", "installed metadata");
            Assertions.contains(entries.get("runtime-details/manager-rotation-1.txt"), "MANAGER-PREVIOUS", "rotated Manager output");
            for (String secret : List.of("PRIVATE-OWNERSHIP-CONTENT", "PRIVATE-PREVIOUS-PROFILE", "VM-SECRET")) Assertions.notContains(text, secret, "excluded/private data");
        }
    }
    private void redaction() throws Exception {
        try (Fixture f = new Fixture()) {
            f.write("launcher.log", "IbPassword=PW-SECRET\nusername='USER-SECRET' token=TOKEN-SECRET, logKey=KEY-SECRET, logUploadKey=UPLOAD-SECRET\n"
                    + "sessionId=SESSION-SECRET, ccpToken=CCP-SECRET, shortTokenHashes=HASH-SECRET;\n"
                    + "account=U12345678, mac=9C:69:D3:6C:6F:37\nGateway owner paper-user\n"
                    + "java.version=25.0.4\nreason [invalid twsInfo]\n");
            String text = String.join("\n", f.export().values());
            for (String secret : List.of("PW-SECRET", "USER-SECRET", "TOKEN-SECRET", "KEY-SECRET", "UPLOAD-SECRET", "SESSION-SECRET", "CCP-SECRET", "HASH-SECRET", "U12345678", "9C:69:D3:6C:6F:37"))
                Assertions.notContains(text, secret, "broker secrets masked: " + secret);
            Assertions.contains(text, "java.version=25.0.4", "runtime diagnosis preserved");
            Assertions.contains(text, "reason [invalid twsInfo]", "error preserved");
        }
    }
    private void binary() throws Exception {
        try (Fixture f = new Fixture()) {
            f.write("launcher.log", "WORKING-LOG\n");
            Files.write(f.settings.resolve("tws.log"), new byte[] {(byte) 0xff, 0, 1});
            Map<String, String> e = f.export();
            Assertions.contains(external(e), "WORKING-LOG", "one bad file must not block export");
            Assertions.contains(e.get("application-log-inventory.txt"), "UNAVAILABLE", "missing/non-text reasons visible");
        }
    }
    private void scope() throws Exception {
        try (Fixture f = new Fixture()) {
            f.write("credentials/launcher.log", "PRIVATE-CREDENTIAL-DIR");
            f.write("autorestart", "PRIVATE-AUTH-TOKEN"); f.write("ibg.xml", "PRIVATE-SETTINGS");
            f.write("notes.txt", "PRIVATE-NOTE"); f.write("logs/logs/logs/launcher.log", "TOO-DEEP");
            String text = String.join("\n", f.export().values());
            for (String secret : List.of("PRIVATE-CREDENTIAL-DIR", "PRIVATE-AUTH-TOKEN", "PRIVATE-SETTINGS", "PRIVATE-NOTE", "TOO-DEEP")) Assertions.notContains(text, secret, "scope excludes unrelated files");
        }
    }
    private void duplicates() throws Exception {
        try (Fixture f = new Fixture()) {
            f.profile = f.profile.toBuilder().twsSettingsPath(f.layout.programPath()).build();
            Files.writeString(f.layout.programPath().resolve("launcher.log"), "ONE-COPY\n");
            String logs = external(f.export());
            Assertions.equals(logs.indexOf("ONE-COPY"), logs.lastIndexOf("ONE-COPY"), "deduplicated roots");
            Assertions.contains(logs, "ONE-COPY", "not omitted altogether");
        }
    }
    private void tws() throws Exception {
        try (Fixture f = new Fixture()) {
            TestSupport.createOfflineTwsInstallation(f.profile.twsPath(), "1045");
            f.profile = f.profile.toBuilder().targetType(TargetType.TWS).build();
            Path program = new OfflineApplicationLayoutResolver().resolve(f.profile).programPath();
            Files.writeString(program.resolve("tws.log"), "TWS-LOG\n");
            Assertions.contains(external(f.export()), "TWS-LOG", "TWS support");
        }
    }
    private void fileBudget() throws Exception {
        try (Fixture f = new Fixture()) {
            f.write("launcher.log", "KEEP-CURRENT\n");
            Files.setLastModifiedTime(f.settings.resolve("launcher.log"), FileTime.fromMillis(1));
            for (int i = 0; i < 30; i++) f.write("launcher." + i + ".log", "ROTATION-" + i + "\n");
            Map<String, String> e = f.export();
            Assertions.equals(16L, e.keySet().stream().filter(k -> k.startsWith("application-logs/")).count(), "bounded count");
            Assertions.contains(external(e), "KEEP-CURRENT", "current launcher prioritized even if rotation mtime is newer");
            Assertions.contains(e.get("application-log-inventory.txt"), "bundle limit", "omissions explained");
        }
    }
    private void byteBudget() throws Exception {
        try (Fixture f = new Fixture()) {
            for (int i = 0; i < 16; i++) f.write("launcher." + i + ".log", ("LINE-" + i + "-" + "x".repeat(480) + "\n").repeat(4300));
            Map<String, String> e = f.export();
            long size = e.entrySet().stream().filter(v -> v.getKey().startsWith("application-logs/") || v.getKey().startsWith("runtime-details/"))
                    .mapToLong(v -> v.getValue().getBytes(StandardCharsets.UTF_8).length).sum();
            Assertions.isTrue(size <= ApplicationLogCollector.MAX_TOTAL_BYTES, "total bytes capped");
            Assertions.contains(e.get("application-log-inventory.txt"), "bundle limit", "cap visible");
        }
    }
    private void tail() throws Exception {
        try (Fixture f = new Fixture()) {
            f.write("launcher.log", "OLD-OMIT\n" + "a line of log data\n".repeat(130000) + "NEWEST-RETAIN\n");
            String text = external(f.export());
            Assertions.notContains(text, "OLD-OMIT", "old bytes omitted"); Assertions.contains(text, "NEWEST-RETAIN", "newest retained");
            Assertions.contains(text, "earlier bytes/partial first line omitted", "truncation marked");
        }
    }
    private void oversizeLine() throws Exception {
        try (Fixture f = new Fixture()) {
            f.write("launcher.log", "token=" + "a".repeat(70000) + "PRIVATE-SUFFIX\nFOLLOWING-LINE\n");
            String text = external(f.export());
            Assertions.notContains(text, "PRIVATE-SUFFIX", "whole oversized line dropped"); Assertions.contains(text, "FOLLOWING-LINE", "next line retained");
        }
    }
    private void missingLayout() throws Exception {
        try (Fixture f = new Fixture()) {
            f.write("launcher.log", "AVAILABLE-BEFORE-INSTALLATION-RESOLVES\n");
            f.profile = f.profile.toBuilder().twsMajorVersion("9999").build();
            Map<String, String> e = f.export();
            Assertions.contains(external(e), "AVAILABLE-BEFORE-INSTALLATION-RESOLVES", "settings logs independent of layout");
            Assertions.contains(e.get("application-log-inventory.txt"), "Installation details unavailable", "reason captured");
        }
    }
    private void blankSettings() throws Exception {
        try (Fixture f = new Fixture()) {
            f.profile = f.profile.toBuilder().twsSettingsPath(Path.of("")).build();
            Assertions.contains(f.export().get("application-log-inventory.txt"), "current directory was not scanned", "empty path guarded");
        }
    }
    private void links(String mode) throws Exception {
        try (Fixture f = new Fixture()) {
            Path outside = Files.createDirectory(f.root.resolve("outside")); Files.writeString(outside.resolve("launcher.log"), "DO-NOT-DISCLOSE\n");
            try {
                switch (mode) {
                    case "file" -> Files.createSymbolicLink(f.settings.resolve("launcher.log"), outside.resolve("launcher.log"));
                    case "directory" -> Files.createSymbolicLink(f.settings.resolve("logs"), outside);
                    case "root" -> { Path link = Files.createSymbolicLink(f.root.resolve("linked"), outside); f.profile = f.profile.toBuilder().twsSettingsPath(link).build(); }
                    case "ancestor" -> { Path nested = Files.createDirectory(outside.resolve("logs")); Files.writeString(nested.resolve("launcher.log"), "DO-NOT-DISCLOSE\n");
                        Path link = Files.createSymbolicLink(f.root.resolve("linked"), outside); f.profile = f.profile.toBuilder().twsSettingsPath(link.resolve("logs")).build(); }
                    default -> throw new AssertionError(mode);
                }
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                // Windows symlink tests require a developer-mode/elevated test account.
                System.out.println("    [PLATFORM LIMIT] Symbolic-link fixture unavailable: " + unavailable.getClass().getSimpleName()); return;
            }
            Assertions.notContains(String.join("\n", f.export().values()), "DO-NOT-DISCLOSE", "no link traversal");
        }
    }
    private void encoding(String encoding) throws Exception {
        try (Fixture f = new Fixture()) {
            String expected = "r\u00e9sum\u00e9 completed\n";
            byte[] data = switch (encoding) {
                case "utf16le" -> ("\ufeff" + expected).getBytes(StandardCharsets.UTF_16LE);
                case "utf16be" -> ("\ufeff" + expected).getBytes(StandardCharsets.UTF_16BE);
                default -> expected.getBytes(StandardCharsets.UTF_8);
            };
            Files.write(f.settings.resolve("launcher.log"), data);
            Assertions.contains(external(f.export()), expected, "supported encoding decoded");
        }
    }
    private static String external(Map<String, String> e) {
        return e.entrySet().stream().filter(entry -> entry.getKey().startsWith("application-logs/"))
                .map(Map.Entry::getValue).collect(java.util.stream.Collectors.joining("\n"));
    }
    private static final class Fixture implements AutoCloseable {
        final Path root = TestSupport.tempDirectory("application-log-export");
        final AppPaths paths = new AppPaths(root.resolve("data"));
        Profile profile = TestSupport.validProfile(root.resolve("installed"));
        final Path settings = profile.twsSettingsPath();
        final OfflineApplicationLayoutResolver.Layout layout = new OfflineApplicationLayoutResolver().resolve(profile);
        Fixture() throws IOException { }
        void write(String relative, String text) throws IOException { Path file = settings.resolve(relative); Files.createDirectories(file.getParent()); Files.writeString(file, text); }
        Map<String, String> export() throws IOException {
            var service = new DiagnosticBundleService(paths, new ManagedConfigService(paths), new ProfileValidator(new UnavailableCredentialStore("fixture")), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
            var status = new ProfileStatus(profile.id(), RuntimeState.ERROR, false, false, PortListenerState.NOT_LISTENING, -1, null, 0, "invalid twsInfo", Instant.EPOCH);
            Path bundle = service.create(profile, status, List.of("runtime marker"));
            Map<String, String> entries = new LinkedHashMap<>();
            try (ZipFile zip = new ZipFile(bundle.toFile())) {
                var iterator = zip.entries(); while (iterator.hasMoreElements()) { var e = iterator.nextElement(); entries.put(e.getName(), new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8)); }
            }
            return entries;
        }
        @Override public void close() throws IOException { TestSupport.deleteTree(root); }
    }
}
