package io.github.ibcmanager.workaround;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.runtime.OfflineApplicationLayoutResolver;
import io.github.ibcmanager.security.CommandResult;
import io.github.ibcmanager.storage.ProfileRepository;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;
import io.github.ibcmanager.workaround.RuntimeDownloadTests.Network;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Missing checksum/probe results are optional; corrupt downloads and known incompatibilities remain blocking. */
public final class UnavailableChecksumTests implements TestSuite {
    private static final String FILE = "jbr-25.0.4-windows-x64-b508.27.zip";
    private static final String ORIGIN = "https://cache-redirector.jetbrains.com/intellij-jbr/";
    private static final String CDN = "https://d2xrhe97vsfxuc.cloudfront.net/";
    private static final String JBR_HASH = CDN + FILE + ".checksum";
    private static final String API = "https://api.github.com/repos/JetBrains/JetBrainsRuntime/releases?per_page=100&page=1";

    @Override public String name() { return "Unavailable publisher checksums: scoped continuation, retained validation and consent"; }
    @Override public List<NamedTest> tests() {
        List<NamedTest> cases = new ArrayList<>();
        for (int status : List.of(400, 401, 403, 404, 408, 410, 429, 500, 502, 503, 504)) {
            cases.add(new NamedTest("all checksum resources unavailable with HTTP " + status + " still install and remove",
                    () -> scenario("http-" + status, true, 9, 1)));
        }
        for (String mode : List.of("dns", "connect", "header", "body", "read", "timeout")) {
            cases.add(new NamedTest("checksum-only " + mode + " failures are non-blocking and audited",
                    () -> scenario(mode, true, 9, 1)));
        }
        for (String mode : List.of("jar-404", "zip-404", "jar-transfer",
                "module-missing", "module-wrong", "native-missing", "descriptor-missing", "pe-invalid",
                "release-invalid", "bad-zip", "save-failure")) {
            int executed = switch (mode) {
                case "runtime-invalid", "runtime-timeout", "module-missing", "module-wrong", "save-failure" -> 1;
                default -> 0;
            };
            cases.add(new NamedTest("unavailable checksums do not bypass " + mode,
                    () -> scenario(mode, false, 0, executed)));
        }
        for (String mode : List.of("runtime-invalid", "runtime-timeout")) {
            cases.add(new NamedTest("missing checksums and unavailable runtime probe both continue: " + mode,
                    () -> scenario(mode, true, 9, 1)));
        }
        for (String mode : List.of("tls-header", "tls-body", "tls-read", "tls-nested", "cancel-header",
                "cancel-checksum", "cancel-warning", "cancel-download", "cancel-validation", "empty-checksum",
                "oversize-checksum", "malformed-checksum", "mismatched-checksum", "changed-target",
                "unsafe-host", "insecure-redirect", "redirect-loop", "invalid-status")) {
            cases.add(new NamedTest("checksum continuation still rejects " + mode,
                    () -> scenario(mode, false, 0, mode.equals("cancel-validation") ? 1 : 0)));
        }
        cases.add(new NamedTest("unavailable catalogue metadata cannot change pinned installation",
                () -> scenario("metadata-failure", true, 9, 1)));
        cases.add(new NamedTest("mixed unavailable and published checksums retain exact per-artifact provenance",
                () -> scenario("mixed", true, 4, 1)));
        cases.add(new NamedTest("published checksum mismatch remains fatal beside other skipped checksums",
                () -> scenario("mixed-mismatch", false, 0, 0)));
        cases.add(new NamedTest("stable JBR ZIP without checksum link is selectable", () -> catalog("zip-only", true)));
        cases.add(new NamedTest("JBR pin cannot be replaced by catalogue checksum-only content", () -> catalog("checksum-only", false)));
        cases.add(new NamedTest("JBR pin cannot be replaced by catalogue suffix content", () -> catalog("suffix", false)));
        cases.add(new NamedTest("JBR compatibility coordinates do not change with catalogue order", () -> catalog("newer", true)));
        cases.add(new NamedTest("confirmation names less assurance and omits removed release-specific prefix", this::consent));
        cases.add(new NamedTest("connect failure in actual opener is typed without exposing query data", this::openerFailure));
        cases.add(new NamedTest("TLS failure in actual opener never becomes unavailable-checksum evidence", this::openerTls));
        return cases;
    }

    private void scenario(String mode, boolean success, int expectedSkips, int expectedExecutions) throws Exception {
        Path root = TestSupport.tempDirectory("unavailable-checksum");
        List<java.util.logging.LogRecord> warnings = new ArrayList<>();
        var logger = java.util.logging.Logger.getLogger(RuntimeBundleInstaller.class.getName());
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) { warnings.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        try {
            Network n = network();
            // Start with every checksum missing, while every required artifact remains present.
            List<String> hashes = new ArrayList<>(List.of(JBR_HASH));
            for (String module : RuntimeCatalog.FX_MODULES) {
                hashes.add(artifact(module) + ".sha256"); hashes.add(artifact(module) + ".sha1");
            }
            for (String h : hashes) {
                var reply = n.routes.get(h);
                reply.status = mode.startsWith("http-") ? Integer.parseInt(mode.substring(5)) : 404;
                if (List.of("dns", "connect", "header", "body", "read", "timeout").contains(mode)) {
                    reply.status = 200;
                    switch (mode) {
                        case "dns" -> reply.headerException = new UnknownHostException("test publisher");
                        case "connect" -> reply.headerException = new java.net.ConnectException("test refused");
                        case "header" -> reply.headerFailure = true;
                        case "body" -> reply.bodyFailure = true;
                        case "read" -> reply.readException = new SocketException("test reset");
                        case "timeout" -> reply.headerException = new SocketTimeoutException("test timeout");
                        default -> throw new AssertionError(mode);
                    }
                }
            }
            switch (mode) {
                case "jar-404" -> n.routes.get(artifact("base")).status = 404;
                case "zip-404" -> n.routes.get(CDN + FILE).status = 404;
                case "jar-transfer" -> n.routes.get(artifact("base")).bodyFailure = true;
                case "native-missing" -> n.data(artifact("graphics"), RuntimeDownloadTests.zip(Map.of("module-info.class", JavafxModuleFixture.descriptor("graphics", "25.0.4"))));
                case "descriptor-missing" -> n.data(artifact("base"), RuntimeDownloadTests.zip(Map.of("other", new byte[] {1})));
                case "pe-invalid" -> n.data(CDN + FILE, runtime(new byte[] {0}, release("25")));
                case "release-invalid" -> n.data(CDN + FILE, runtime(RuntimeDownloadTests.pe(), release("21")));
                case "bad-zip" -> n.data(CDN + FILE, new byte[] {1, 2, 3});
                case "tls-header" -> { n.routes.get(JBR_HASH).status = 200; n.routes.get(JBR_HASH).headerException = new javax.net.ssl.SSLHandshakeException("bad TLS"); }
                case "tls-body" -> { n.routes.get(JBR_HASH).status = 200; n.routes.get(JBR_HASH).bodyException = new javax.net.ssl.SSLException("bad TLS"); }
                case "tls-read" -> { n.routes.get(JBR_HASH).status = 200; n.routes.get(JBR_HASH).readException = new javax.net.ssl.SSLException("bad TLS"); }
                case "tls-nested" -> { n.routes.get(JBR_HASH).status = 200; n.routes.get(JBR_HASH).bodyException = new IOException(new javax.net.ssl.SSLException("bad TLS")); }
                case "cancel-header" -> n.routes.get(JBR_HASH).headerException = new InterruptedIOException("cancelled");
                case "empty-checksum" -> n.data(JBR_HASH, new byte[0]);
                case "oversize-checksum" -> n.data(JBR_HASH, new byte[8193]);
                case "malformed-checksum" -> n.data(JBR_HASH, RuntimeDownloadTests.bytes("not a hash"));
                case "mismatched-checksum" -> n.data(JBR_HASH, RuntimeDownloadTests.bytes("0".repeat(128)));
                case "changed-target" -> { String other = "https://intellij-jbr.s3.amazonaws.com/other.zip.checksum";
                    n.redirect(ORIGIN + FILE + ".checksum", 302, other); n.data(other, new byte[0]); n.routes.get(other).status = 404; }
                case "unsafe-host" -> n.redirect(ORIGIN + FILE + ".checksum", 302, "https://untrusted.invalid/checksum");
                case "insecure-redirect" -> n.redirect(ORIGIN + FILE + ".checksum", 302, "http://d2xrhe97vsfxuc.cloudfront.net/" + FILE + ".checksum");
                case "redirect-loop" -> n.redirect(ORIGIN + FILE + ".checksum", 302, ORIGIN + FILE + ".checksum");
                case "invalid-status" -> n.routes.get(JBR_HASH).status = 206;
                case "metadata-failure" -> n.routes.get(RuntimeCatalog.MAVEN + "javafx-controls/maven-metadata.xml").status = 503;
                case "mixed", "mixed-mismatch" -> {
                    n.routes.get(JBR_HASH).status = 200;
                    for (String module : List.of("graphics", "fxml", "swing", "web")) n.routes.get(artifact(module) + ".sha1").status = 200;
                    if (mode.equals("mixed-mismatch")) n.data(artifact("graphics") + ".sha1", RuntimeDownloadTests.bytes("0".repeat(40)));
                }
                default -> { }
            }
            Profile base = TestSupport.validProfile(root.resolve("app"));
            TestSupport.createOfflineGatewayInstallation(base.twsPath(), "1050");
            Path manual = Files.createDirectories(root.resolve("manual/bin")); Files.writeString(manual.resolve("keep.txt"), "keep");
            Profile before = base.toBuilder().twsMajorVersion("1050").profileOnlyConfiguration(true)
                    .baseConfigPath(Path.of("")).ibcJavaPath(manual).build();
            Path options = new OfflineApplicationLayoutResolver().resolve(before).vmOptionsPath();
            String original = "# user settings\r\n-Xmx768m\r\n--module-path=C:/Manual/FX\r\n";
            Files.writeString(options, original);
            AppPaths paths = new AppPaths(root.resolve("data")); ProfileRepository repo = new ProfileRepository(paths); repo.save(before);
            AtomicInteger executions = new AtomicInteger();
            RuntimeBundleInstaller installer = new RuntimeBundleInstaller(n.downloads, (cmd, input, timeout) -> {
                executions.incrementAndGet();
                Assertions.equals("--list-modules", cmd.get(cmd.size() - 1), "compatibility probe only; no Gateway login");
                String modules = RuntimeCatalog.FX_MODULES.stream()
                        .filter(m -> !mode.equals("module-missing") || !m.equals("web"))
                        .map(m -> RuntimeCatalog.moduleName(m) + "@" + (mode.equals("module-wrong") ? "21" : "25.0.4") + "\n")
                        .collect(java.util.stream.Collectors.joining());
                return new CommandResult(mode.equals("runtime-invalid") ? 1 : 0, modules, "test probe", mode.equals("runtime-timeout"));
            });
            ManagedRuntimeService service = new ManagedRuntimeService(paths, repo, installer::install);
            List<String> progress = new ArrayList<>();
            BooleanSupplier cancelled = () -> switch (mode) {
                case "cancel-checksum" -> n.requested().contains(URI.create(JBR_HASH));
                case "cancel-warning" -> !warnings.isEmpty();
                case "cancel-download" -> n.requested().contains(URI.create(CDN + FILE));
                case "cancel-validation" -> executions.get() > 0;
                default -> false;
            };
            ManagedRuntimeService.ProfileWriter writer = (old, next) -> {
                if (mode.equals("save-failure")) throw new IOException("simulated profile save failure"); repo.save(next);
            };
            Profile desired = before.toBuilder().managedHeadlessRuntime(true).build();
            if (success) {
                Profile installed = service.save(before, desired, writer, p -> { }, progress::add, cancelled);
                Path managed = ManagedRuntimeService.directory(options);
                String provenance = Files.readString(managed.resolve("payload/download-provenance.txt"));
                Assertions.equals(expectedSkips, occurrences(provenance, "Publisher checksum verification SKIPPED"), "per-artifact skips recorded honestly");
                Assertions.equals(expectedSkips, (int) warnings.stream().filter(w -> w.getMessage().contains("Publisher checksum verification SKIPPED")).count(), "each unchecked artifact emits a persistent warning");
                Assertions.equals(9, occurrences(provenance, "Local SHA-256"), "local integrity recorded for every artifact");
                Assertions.equals(9 - expectedSkips, occurrences(provenance, "Published SHA-"), "only checked artifacts claim publisher verification");
                if (mode.equals("runtime-invalid") || mode.equals("runtime-timeout")) {
                    Assertions.contains(provenance, "Runtime compatibility verification SKIPPED", "unavailable probe audited");
                    Assertions.isFalse(progress.stream().anyMatch(s -> s.contains("Runtime compatibility checks passed")), "not marked passed");
                } else Assertions.isTrue(progress.stream().anyMatch(s -> s.contains("Runtime compatibility checks passed")), "successful validation distinct from publisher verification");
                Assertions.equals(installed, repo.loadAll().profiles().get(0), "committed profile matches installed runtime");
                Assertions.isTrue(Files.exists(installed.ibcJavaPath().resolve("java.exe")), "required runtime exists");
                ManagedRuntimeService.checkBeforeStart(installed, paths);
                Profile restored = service.save(installed, installed.toBuilder().managedHeadlessRuntime(false).build(),
                        (old, next) -> repo.save(next), p -> { }, s -> { }, () -> false);
                Assertions.equals(manual, restored.ibcJavaPath(), "manual override restored");
                Assertions.isFalse(Files.exists(managed), "unverified downloads have the same owned cleanup");
            } else {
                Assertions.throwsType(IOException.class, () -> service.save(before, desired, writer, p -> { }, progress::add, cancelled), "unsafe/incomplete install fails");
                Assertions.equals(before, repo.loadAll().profiles().get(0), "failed setup does not change profile");
                Assertions.isFalse(Files.exists(ManagedRuntimeService.directory(options)), "failed activation is rolled back");
            }
            Assertions.isFalse(n.requested().stream().anyMatch(u -> u.getPath().endsWith("maven-metadata.xml")), "pinned versions need no catalogue request");
            Assertions.equals(expectedExecutions, executions.get(), "invalid transfer/content cannot reach runtime validation");
            Assertions.equals(original, Files.readString(options), "options restored or untouched");
            Assertions.equals("keep", Files.readString(manual.resolve("keep.txt")), "manual files preserved");
            try (var entries = Files.list(options.getParent())) {
                Assertions.isFalse(entries.anyMatch(p -> p.getFileName().toString().startsWith(".ibc-runtime-stage-")), "no staging leftovers");
            }
            n.assertClosed();
        } finally { logger.removeHandler(handler); TestSupport.deleteTree(root); }
    }

    private void catalog(String mode, boolean success) throws Exception {
        Network n = network(); String url = ORIGIN + FILE;
        String body = switch (mode) { case "checksum-only" -> "[checksum](" + url + ".checksum)";
            case "suffix" -> url + ".backup"; default -> "[zip](" + url + ")"; };
        String json = releaseRecord("25.0.4", "508.27", body);
        if (mode.equals("newer")) json += "," + releaseRecord("25.0.3", "500.1", ORIGIN + "jbr-25.0.3-windows-x64-b500.1.zip");
        n.data(API, RuntimeDownloadTests.bytes("[" + json + "]"));
        Assertions.equals("25.0.4-b508.27", new RuntimeCatalog(n.downloads).resolve().jbrVersion(), "catalogue cannot change the fixed build");
        Assertions.equals(URI.create(ORIGIN + FILE), new RuntimeCatalog(n.downloads).resolve().jbrZip(), "exact vanilla x64 archive");
        n.assertClosed();
    }
    private void consent() {
        Assertions.contains(ManagedRuntimeService.CONSENT, "Unavailable checksum or runtime checks", "user is told about skipped checks before save");
        Assertions.contains(ManagedRuntimeService.CONSENT, "not fully verified", "reduced assurance is not disguised as verified");
        Assertions.contains(ManagedRuntimeService.CONSENT, "warning", "skips remain visible");
        Assertions.notContains(ManagedRuntimeService.CONSENT, "1050 / ", "remove obsolete option-prefix wording");
    }
    private void openerFailure() {
        RuntimeDownloads d = new RuntimeDownloads.Official(uri -> { throw new UnknownHostException("test"); });
        RuntimeDownloads.TransportException error = Assertions.throwsType(RuntimeDownloads.TransportException.class,
                () -> d.text(URI.create(ORIGIN + FILE + ".checksum?token=secret"), 8192), "typed network evidence");
        Assertions.notContains(error.getMessage(), "secret", "query omitted from diagnostics");
    }
    private void openerTls() {
        RuntimeDownloads d = new RuntimeDownloads.Official(uri -> { throw new javax.net.ssl.SSLHandshakeException("test"); });
        Assertions.throwsType(javax.net.ssl.SSLHandshakeException.class, () -> d.text(URI.create(ORIGIN + FILE + ".checksum"), 8192), "TLS validation failure not downgraded");
    }
    private static String releaseRecord(String version, String build, String body) {
        return "{\"tag_name\":\"jbr-release-" + version + "b" + build + "\",\"draft\":false,\"prerelease\":false,\"body\":\"" + body + "\"}";
    }
    private static String artifact(String module) { return RuntimeCatalog.MAVEN + RuntimeCatalog.artifactId(module) + "/25.0.4/" + RuntimeCatalog.artifactId(module) + "-25.0.4-win.jar"; }
    private static byte[] release(String version) { return RuntimeDownloadTests.bytes("JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"JetBrains s.r.o.\"\nIMPLEMENTOR_VERSION=\"JBR-25.0.4+1-508.27-nomod\"\nJAVA_RUNTIME_VERSION=\"25.0.4+1-b508.27\"\n"); }
    private static byte[] runtime(byte[] executable, byte[] metadata) throws IOException { return RuntimeDownloadTests.zip(Map.of("jbr/bin/java.exe", executable, "jbr/release", metadata)); }
    private static int occurrences(String text, String token) { return (text.length() - text.replace(token, "").length()) / token.length(); }
    private static Network network() throws Exception {
        Network n = new Network();
        n.data(API, RuntimeDownloadTests.bytes("[" + releaseRecord("25.0.4", "508.27", ORIGIN + FILE) + "]"));
        n.data(RuntimeCatalog.MAVEN + "javafx-controls/maven-metadata.xml", RuntimeDownloadTests.bytes("<versions><version>25.0.4</version></versions>"));
        byte[] zip = runtime(RuntimeDownloadTests.pe(), release("25.0.4"));
        n.redirect(ORIGIN + FILE, 302, CDN + FILE); n.redirect(ORIGIN + FILE + ".checksum", 302, JBR_HASH);
        n.data(CDN + FILE, zip); n.data(JBR_HASH, RuntimeDownloadTests.bytes(RuntimeDownloadTests.hash(zip, "SHA-512")));
        for (String module : RuntimeCatalog.FX_MODULES) {
            Map<String, byte[]> entries = new LinkedHashMap<>(); entries.put("module-info.class", JavafxModuleFixture.descriptor(module, "25.0.4"));
            String nativeName = switch (module) { case "graphics" -> "glass.dll"; case "media" -> "jfxmedia.dll"; case "web" -> "jfxwebkit.dll"; default -> null; };
            if (nativeName != null) entries.put(nativeName, RuntimeDownloadTests.pe());
            byte[] jar = RuntimeDownloadTests.zip(entries); n.data(artifact(module), jar);
            n.data(artifact(module) + ".sha256", RuntimeDownloadTests.bytes(RuntimeDownloadTests.hash(jar, "SHA-256")));
            n.data(artifact(module) + ".sha1", RuntimeDownloadTests.bytes(RuntimeDownloadTests.hash(jar, "SHA-1")));
        }
        return n;
    }
}
