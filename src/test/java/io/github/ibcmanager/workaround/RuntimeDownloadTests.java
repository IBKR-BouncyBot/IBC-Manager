package io.github.ibcmanager.workaround;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.app.Version;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.runtime.OfflineApplicationLayoutResolver;
import io.github.ibcmanager.security.CommandResult;
import io.github.ibcmanager.storage.ProfileRepository;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Real downloader/installer logic with deterministic HTTP responses, not live TLS or Windows execution. */
public final class RuntimeDownloadTests implements TestSuite {
    private static final String FILE = "jbr-25.0.4-windows-x64-b508.27.zip";
    private static final String ORIGIN = "https://cache-redirector.jetbrains.com/intellij-jbr/";
    private static final String CDN = "https://d2xrhe97vsfxuc.cloudfront.net/";
    private static final String API = "https://api.github.com/repos/JetBrains/JetBrainsRuntime/releases?per_page=100&page=1";
    private static final String META = RuntimeCatalog.MAVEN + "javafx-controls/maven-metadata.xml";

    @Override public String name() { return "Publisher HTTP redirects: actual transport, artifact binding and rollback"; }
    @Override public List<NamedTest> tests() {
        List<NamedTest> tests = new ArrayList<>();
        tests.add(new NamedTest("documented JetBrains checksum redirect is accepted by the actual HTTP loop", this::checksumRedirect));
        for (int status : List.of(301, 302, 303, 307, 308)) {
            tests.add(new NamedTest("JBR archive download follows validated HTTP " + status, () -> archiveRedirect(status)));
        }
        tests.add(new NamedTest("same-host relative redirect retains the original artifact binding", this::relativeRedirect));
        tests.add(new NamedTest("four-component Java25 versions work without pinning a build", this::newerVersion));
        tests.add(new NamedTest("Maven metadata and artifact requests still use the existing publisher path", this::maven));
        tests.add(new NamedTest("direct CDN request is not accepted as a publisher", this::directCdn));
        tests.add(new NamedTest("Maven cannot redirect into the JBR-only CDN exception", this::unrelatedOrigin));
        tests.add(new NamedTest("a trusted intermediate does not erase original-publisher restrictions", this::intermediateOrigin));
        tests.add(new NamedTest("CDN cannot redirect onward to an arbitrary host", this::onwardRedirect));
        for (String target : List.of(
                "https://another-distribution.cloudfront.net/" + FILE,
                "https://d2xrhe97vsfxuc.cloudfront.net.evil.invalid/" + FILE,
                "https://sub.d2xrhe97vsfxuc.cloudfront.net/" + FILE,
                "http://d2xrhe97vsfxuc.cloudfront.net/" + FILE,
                "https://user@d2xrhe97vsfxuc.cloudfront.net/" + FILE,
                "https://d2xrhe97vsfxuc.cloudfront.net:8443/" + FILE,
                CDN + FILE + "#fragment", CDN + FILE + "?url=https://evil.invalid",
                CDN + "unrelated.exe", CDN + "intellij-jbr/" + FILE,
                CDN + FILE.replace("508.27", "508.28"), CDN + FILE.replace("x64", "aarch64"),
                CDN + "../" + FILE, CDN + "%2e%2e/" + FILE,
                CDN + "%2f" + FILE, CDN + "x%5c" + FILE,
                CDN + FILE.replace("jbr-", "%6abr-"), CDN + FILE + ".checksum")) {
            tests.add(new NamedTest("unapproved or changed CDN target is rejected " + tests.size(), () -> deniedTarget(target)));
        }
        for (String file : List.of("jbr-21-windows-x64-b1.zip", "jbr-26-windows-x64-b1.zip",
                "jbr_jcef-25-windows-x64-b1.zip", "jbr-25-ea-windows-x64-b1.zip", "jbr-25-windows-x64-b1.tar.gz")) {
            tests.add(new NamedTest("CDN allowance cannot select an unsupported runtime " + file, () -> deniedFamily(file)));
        }
        tests.add(new NamedTest("checksum request cannot redirect to archive bytes", this::changedChecksumTarget));
        tests.add(new NamedTest("explicit HTTPS port 443 and host casing are accepted", this::httpsAuthority));
        for (String location : Arrays.asList(null, "", "  ", "https://[bad", "https://bad host/path")) {
            tests.add(new NamedTest("empty or malformed Location is rejected " + tests.size(), () -> badLocation(location)));
        }
        tests.add(new NamedTest("five permitted redirects can finish at the exact CDN artifact", this::fiveHops));
        tests.add(new NamedTest("redirect loop stops at the existing five-hop budget", this::loop));
        tests.add(new NamedTest("HTTP failure disconnects the response", this::httpFailure));
        tests.add(new NamedTest("header read failure disconnects the response", this::headerFailure));
        tests.add(new NamedTest("body read failure disconnects the response", this::bodyFailure));
        tests.add(new NamedTest("text size bound still applies after CDN redirection", this::textLimit));
        tests.add(new NamedTest("declared archive size rejects before a file is created", this::declaredLimit));
        tests.add(new NamedTest("streaming bound applies when Content-Length is absent", this::streamLimit));
        tests.add(new NamedTest("empty CDN response cannot be accepted as a checksum", this::empty));
        tests.add(new NamedTest("cancellation is retained after a valid redirect", this::cancel));
        tests.add(new NamedTest("existing output is never overwritten by a redirected download", this::existingFile));
        tests.add(new NamedTest("full setup and removal use real redirect/checksum code and restore prior files", () -> setup("success")));
        tests.add(new NamedTest("wrong checksum after allowed CDN redirect rolls back before execution", () -> setup("checksum")));
        tests.add(new NamedTest("denied CDN redirect rolls back profile options and staged files", () -> setup("denied")));
        tests.add(new NamedTest("cancelled CDN download leaves profile options and manual runtime unchanged", () -> setup("cancel")));
        return tests;
    }

    private void checksumRedirect() throws Exception {
        Network n = redirected(FILE + ".checksum", 302, bytes("a".repeat(128) + "  " + FILE + "\n"));
        String result = n.downloads.text(URI.create(ORIGIN + FILE + ".checksum"), 8192);
        Assertions.contains(result, "a".repeat(128), "checksum body survives transport");
        Assertions.equals(List.of(URI.create(ORIGIN + FILE + ".checksum"), URI.create(CDN + FILE + ".checksum")), n.requested(), "documented prefix-stripping redirect");
        n.assertClosed();
    }
    private void archiveRedirect(int status) throws Exception {
        Path root = TestSupport.tempDirectory("cdn-file");
        try {
            Network n = redirected(FILE, status, new byte[] {80, 75, 3, 4, 0, -1});
            Path output = root.resolve("runtime.zip");
            n.downloads.file(URI.create(ORIGIN + FILE), output, 1024, () -> false);
            Assertions.isTrue(Arrays.equals(n.routes.get(CDN + FILE).body, Files.readAllBytes(output)), "exact bytes");
            n.assertClosed();
        } finally { TestSupport.deleteTree(root); }
    }
    private void relativeRedirect() throws Exception {
        Network n = redirected(FILE, 302, bytes("archive"));
        n.routes.get(ORIGIN + FILE).location = "./" + FILE + "?hop=1";
        n.redirect(ORIGIN + FILE + "?hop=1", 307, CDN + FILE);
        Assertions.equals("archive", n.downloads.text(URI.create(ORIGIN + FILE), 100), "relative hop preserved");
        Assertions.equals(3, n.calls.size(), "three validated requests"); n.assertClosed();
    }
    private void newerVersion() throws Exception {
        String name = "jbr-25.0.4.1-windows-x64-b635.70.zip.checksum";
        Network n = redirected(name, 302, bytes("checksum"));
        Assertions.equals("checksum", n.downloads.text(URI.create(ORIGIN + name), 100), "25 line, not one hardcoded version"); n.assertClosed();
    }
    private void maven() throws Exception {
        Network n = new Network(); n.data(META, bytes("<versions><version>25</version></versions>"));
        Assertions.contains(n.downloads.text(URI.create(META), 100), "<version>25</version>", "Maven unchanged"); n.assertClosed();
    }
    private void directCdn() {
        Network n = new Network();
        Assertions.throwsType(IOException.class, () -> n.downloads.text(URI.create(CDN + FILE), 100), "CDN is redirect-only");
        Assertions.equals(0, n.calls.size(), "no request before origin authorization");
    }
    private void unrelatedOrigin() throws Exception {
        Network n = new Network(); n.redirect(META, 302, CDN + FILE);
        Assertions.throwsType(IOException.class, () -> n.downloads.text(URI.create(META), 100), "origin family bound");
        Assertions.equals(1, n.calls.size(), "CDN not contacted"); n.assertClosed();
    }
    private void intermediateOrigin() throws Exception {
        Network n = new Network(); n.redirect(META, 302, ORIGIN + FILE); n.redirect(ORIGIN + FILE, 302, CDN + FILE);
        Assertions.throwsType(IOException.class, () -> n.downloads.text(URI.create(META), 100), "first publisher retained");
        Assertions.equals(2, n.calls.size(), "intermediate cannot grant CDN trust"); n.assertClosed();
    }
    private void onwardRedirect() throws Exception {
        Network n = redirected(FILE, 302, bytes("archive")); n.routes.get(CDN + FILE).status = 302;
        n.routes.get(CDN + FILE).location = "https://evil.invalid/file";
        Assertions.throwsType(IOException.class, () -> n.downloads.text(URI.create(ORIGIN + FILE), 100), "all hops validated");
        Assertions.equals(2, n.calls.size(), "arbitrary host not contacted"); n.assertClosed();
    }
    private void deniedTarget(String target) throws Exception {
        Network n = new Network(); n.redirect(ORIGIN + FILE, 302, target);
        Assertions.throwsType(IOException.class, () -> n.downloads.text(URI.create(ORIGIN + FILE), 100), "target refused");
        Assertions.equals(1, n.calls.size(), "refusal happens before target connection"); n.assertClosed();
    }
    private void deniedFamily(String file) throws Exception {
        Network n = redirected(file, 302, bytes("untrusted"));
        Assertions.throwsType(IOException.class, () -> n.downloads.text(URI.create(ORIGIN + file), 100), "strict supported artifact pattern");
        Assertions.equals(1, n.calls.size(), "CDN not contacted"); n.assertClosed();
    }
    private void changedChecksumTarget() throws Exception {
        Network n = new Network(); n.redirect(ORIGIN + FILE + ".checksum", 302, CDN + FILE);
        Assertions.throwsType(IOException.class, () -> n.downloads.text(URI.create(ORIGIN + FILE + ".checksum"), 8192), "sidecar cannot become archive");
        Assertions.equals(1, n.calls.size(), "wrong artifact not contacted"); n.assertClosed();
    }
    private void httpsAuthority() throws Exception {
        Network n = new Network(); String target = "https://D2XRHE97VSFXUC.CLOUDFRONT.NET:443/" + FILE;
        n.redirect(ORIGIN + FILE, 302, target); n.data(target, bytes("valid"));
        Assertions.equals("valid", n.downloads.text(URI.create(ORIGIN + FILE), 100), "HTTPS default port and case accepted"); n.assertClosed();
    }
    private void badLocation(String location) throws Exception {
        Network n = new Network(); n.redirect(ORIGIN + FILE, 302, location);
        Assertions.throwsType(IOException.class, () -> n.downloads.text(URI.create(ORIGIN + FILE), 100), "bad location is controlled error");
        Assertions.equals(1, n.calls.size(), "no second connection"); n.assertClosed();
    }
    private void fiveHops() throws Exception {
        Network n = new Network(); String first = ORIGIN + FILE;
        for (int i = 0; i < 5; i++) {
            String from = i == 0 ? first : first + "?hop=" + i;
            String to = i == 4 ? CDN + FILE : first + "?hop=" + (i + 1);
            n.redirect(from, 302, to);
        }
        n.data(CDN + FILE, bytes("archive"));
        Assertions.equals("archive", n.downloads.text(URI.create(first), 100), "fifth redirect can complete");
        Assertions.equals(6, n.calls.size(), "five redirects plus final body"); n.assertClosed();
    }
    private void loop() throws Exception {
        Network n = new Network(); n.redirect(ORIGIN + FILE, 302, ORIGIN + FILE);
        IOException error = Assertions.throwsType(IOException.class, () -> n.downloads.text(URI.create(ORIGIN + FILE), 100), "loop must stop");
        Assertions.contains(error.getMessage(), "Too many", "bounded failure");
        Assertions.equals(6, n.calls.size(), "at most five redirect hops plus original request"); n.assertClosed();
    }
    private void httpFailure() throws Exception {
        Network n = redirected(FILE, 302, bytes("forbidden")); n.routes.get(CDN + FILE).status = 403;
        IOException error = Assertions.throwsType(IOException.class, () -> n.downloads.text(URI.create(ORIGIN + FILE), 100), "HTTP failure");
        Assertions.contains(error.getMessage(), "HTTP 403", "actionable status"); n.assertClosed();
    }
    private void headerFailure() throws Exception {
        Network n = redirected(FILE, 302, bytes("body")); n.routes.get(CDN + FILE).headerFailure = true;
        Assertions.throwsType(IOException.class, () -> n.downloads.text(URI.create(ORIGIN + FILE), 100), "header read failure"); n.assertClosed();
    }
    private void bodyFailure() throws Exception {
        Network n = redirected(FILE, 302, bytes("body")); n.routes.get(CDN + FILE).bodyFailure = true;
        Assertions.throwsType(IOException.class, () -> n.downloads.text(URI.create(ORIGIN + FILE), 100), "body failure"); n.assertClosed();
    }
    private void textLimit() throws Exception {
        Network n = redirected(FILE + ".checksum", 302, new byte[8193]);
        Assertions.throwsType(IOException.class, () -> n.downloads.text(URI.create(ORIGIN + FILE + ".checksum"), 8192), "bounded sidecar"); n.assertClosed();
    }
    private void declaredLimit() throws Exception {
        Path root = TestSupport.tempDirectory("cdn-limit");
        try {
            Network n = redirected(FILE, 302, new byte[20]); Path target = root.resolve("out");
            Assertions.throwsType(IOException.class, () -> n.downloads.file(URI.create(ORIGIN + FILE), target, 10, () -> false), "declared size rejected");
            Assertions.isFalse(Files.exists(target), "no target created"); n.assertClosed();
        } finally { TestSupport.deleteTree(root); }
    }
    private void streamLimit() throws Exception {
        Path root = TestSupport.tempDirectory("cdn-stream-limit");
        try {
            Network n = redirected(FILE, 302, new byte[20]); n.routes.get(CDN + FILE).length = -1;
            Assertions.throwsType(IOException.class, () -> n.downloads.file(URI.create(ORIGIN + FILE), root.resolve("out"), 10, () -> false), "stream bound retained"); n.assertClosed();
        } finally { TestSupport.deleteTree(root); }
    }
    private void empty() throws Exception {
        Network n = redirected(FILE + ".checksum", 302, new byte[0]);
        Assertions.throwsType(IOException.class, () -> n.downloads.text(URI.create(ORIGIN + FILE + ".checksum"), 8192), "empty checksum refused"); n.assertClosed();
    }
    private void cancel() throws Exception {
        Path root = TestSupport.tempDirectory("cdn-cancel");
        try {
            Network n = redirected(FILE, 302, bytes("body"));
            Assertions.throwsType(IOException.class, () -> n.downloads.file(URI.create(ORIGIN + FILE), root.resolve("out"), 100, () -> true), "cancel retained"); n.assertClosed();
        } finally { TestSupport.deleteTree(root); }
    }
    private void existingFile() throws Exception {
        Path root = TestSupport.tempDirectory("cdn-existing");
        try {
            Path target = Files.writeString(root.resolve("out"), "keep"); Network n = redirected(FILE, 302, bytes("replace"));
            Assertions.throwsType(IOException.class, () -> n.downloads.file(URI.create(ORIGIN + FILE), target, 100, () -> false), "exclusive creation retained");
            Assertions.equals("keep", Files.readString(target), "existing bytes unchanged"); n.assertClosed();
        } finally { TestSupport.deleteTree(root); }
    }

    private void setup(String mode) throws Exception {
        Path root = TestSupport.tempDirectory("cdn-setup");
        try {
            Network n = new Network(); byte[] archive = zip(Map.of("jbr/bin/java.exe", pe(), "jbr/release", bytes("JAVA_VERSION=\"25.0.4\"\nIMPLEMENTOR=\"JetBrains s.r.o.\"\n")));
            n.data(API, bytes("[{\"tag_name\":\"jbr-release-25.0.4b508.27\",\"draft\":false,\"prerelease\":false,\"body\":\"" + ORIGIN + FILE + " " + ORIGIN + FILE + ".checksum\"}]"));
            n.data(META, bytes("<versions><version>25.0.4</version></versions>"));
            n.redirect(ORIGIN + FILE + ".checksum", 302, CDN + FILE + ".checksum");
            n.redirect(ORIGIN + FILE, 302, mode.equals("denied") ? "https://other.cloudfront.net/" + FILE : CDN + FILE);
            n.data(CDN + FILE + ".checksum", bytes(mode.equals("checksum") ? "0".repeat(128) : hash(archive, "SHA-512")));
            n.data(CDN + FILE, archive);
            for (String module : RuntimeCatalog.FX_MODULES) {
                Map<String, byte[]> entries = new LinkedHashMap<>(); entries.put("module-info.class", JavafxModuleFixture.descriptor(module, "25.0.4"));
                String lib = switch (module) { case "graphics" -> "glass.dll"; case "media" -> "jfxmedia.dll"; case "web" -> "jfxwebkit.dll"; default -> null; };
                if (lib != null) entries.put(lib, pe());
                byte[] jar = zip(entries); String uri = RuntimeCatalog.MAVEN + RuntimeCatalog.artifactId(module) + "/25.0.4/" + RuntimeCatalog.artifactId(module) + "-25.0.4-win.jar";
                n.data(uri, jar); n.data(uri + ".sha256", bytes(hash(jar, "SHA-256")));
            }
            Profile profile = TestSupport.validProfile(root.resolve("application"));
            TestSupport.createOfflineGatewayInstallation(profile.twsPath(), "1050");
            Path manual = Files.createDirectories(root.resolve("manual/bin")); Files.writeString(manual.resolve("keep.txt"), "manual");
            profile = profile.toBuilder().twsMajorVersion("1050").profileOnlyConfiguration(true).baseConfigPath(Path.of("")).ibcJavaPath(manual).build();
            Path options = new OfflineApplicationLayoutResolver().resolve(profile).vmOptionsPath();
            String original = "# original\r\n-Xmx768m\r\n--module-path=C:/manual/fx\r\n--add-modules=javafx.controls\r\n"; Files.writeString(options, original);
            AppPaths paths = new AppPaths(root.resolve("data")); ProfileRepository repo = new ProfileRepository(paths); repo.save(profile);
            AtomicInteger executions = new AtomicInteger();
            RuntimeBundleInstaller installer = new RuntimeBundleInstaller(n.downloads, (cmd, in, timeout) -> {
                executions.incrementAndGet(); Assertions.equals("--list-modules", cmd.get(cmd.size() - 1), "only module verification, no broker login");
                return new CommandResult(0, RuntimeCatalog.FX_MODULES.stream().map(m -> RuntimeCatalog.moduleName(m) + "@25.0.4\n").collect(java.util.stream.Collectors.joining()), "", false);
            });
            ManagedRuntimeService service = new ManagedRuntimeService(paths, repo, installer::install);
            Profile before = profile; Profile desired = profile.toBuilder().managedHeadlessRuntime(true).build();
            if (!mode.equals("success")) {
                IOException error = Assertions.throwsType(IOException.class, () -> service.save(before, desired, (previous, next) -> repo.save(next), p -> { }, s -> { },
                        () -> mode.equals("cancel") && n.requested().contains(URI.create(CDN + FILE))), "failed setup rolls back");
                String reason = switch (mode) { case "checksum" -> "checksum mismatch"; case "denied" -> "not allowed"; default -> "cancelled"; };
                Assertions.contains(error.getMessage(), reason, "failure cause retained");
                Assertions.equals(0, executions.get(), "unverified archive never executes");
                Assertions.equals(original, Files.readString(options), "options unchanged");
                Assertions.equals(before, repo.loadAll().profiles().get(0), "profile unchanged");
                Assertions.isFalse(Files.exists(ManagedRuntimeService.directory(options)), "no managed installation activated");
                try (var siblings = Files.list(options.getParent())) {
                    Assertions.isFalse(siblings.anyMatch(p -> p.getFileName().toString().startsWith(".ibc-runtime-stage-")), "staged files cleaned");
                }
            } else {
                Profile enabled = service.save(before, desired, (previous, next) -> repo.save(next), p -> { }, s -> { }, () -> false);
                Assertions.equals(1, executions.get(), "one runtime module check");
                Assertions.isTrue(enabled.managedHeadlessRuntime(), "installed ownership set");
                Assertions.fileExists(enabled.ibcJavaPath().resolve("java.exe"), "JBR selected");
                Assertions.notEquals(original, Files.readString(options), "options applied after checksums");
                Assertions.isTrue(n.requested().contains(URI.create(CDN + FILE + ".checksum")), "sidecar crossed real redirect loop");
                Assertions.isTrue(n.requested().contains(URI.create(CDN + FILE)), "archive crossed real redirect loop");
                Profile restored = service.save(enabled, enabled.toBuilder().managedHeadlessRuntime(false).build(), (previous, next) -> repo.save(next), p -> { }, s -> { }, () -> false);
                Assertions.equals(manual, restored.ibcJavaPath(), "previous override restored");
                Assertions.equals(original, Files.readString(options), "original options restored exactly");
                Assertions.isFalse(Files.exists(ManagedRuntimeService.directory(options)), "owned payload removed");
            }
            Assertions.equals("manual", Files.readString(manual.resolve("keep.txt")), "manual runtime preserved"); n.assertClosed();
        } finally { TestSupport.deleteTree(root); }
    }

    private static Network redirected(String file, int status, byte[] body) {
        Network n = new Network(); n.redirect(ORIGIN + file, status, CDN + file); n.data(CDN + file, body); return n;
    }
    static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static String hash(byte[] b, String algorithm) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(b)); }
    static byte[] pe() {
        byte[] b = new byte[80]; ByteBuffer h = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        b[0] = 'M'; b[1] = 'Z'; h.putInt(60, 64); h.putInt(64, 0x4550); h.putShort(68, (short) 0x8664); return b;
    }
    static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            for (var f : files.entrySet()) { z.putNextEntry(new ZipEntry(f.getKey())); z.write(f.getValue()); z.closeEntry(); }
        }
        return b.toByteArray();
    }
    static final class Reply {
        int status; String location; byte[] body; long length; boolean headerFailure; boolean bodyFailure;
        IOException headerException; IOException bodyException; IOException readException;
        Reply(int status, String location, byte[] body) { this.status = status; this.location = location; this.body = body; this.length = body.length; }
    }
    static final class Network {
        final Map<String, Reply> routes = new LinkedHashMap<>(); final List<Connection> calls = new ArrayList<>();
        final RuntimeDownloads downloads = new RuntimeDownloads.Official(uri -> {
            Reply reply = routes.get(uri.toString());
            if (reply == null) throw new AssertionError("Unplanned network request: " + uri);
            Connection c = new Connection(uri, reply); calls.add(c); return c;
        });
        void redirect(String from, int status, String to) { routes.put(from, new Reply(status, to, new byte[0])); }
        void data(String uri, byte[] body) { routes.put(uri, new Reply(200, null, body)); }
        List<URI> requested() { return calls.stream().map(c -> c.uri).toList(); }
        void assertClosed() {
            for (Connection c : calls) {
                Assertions.isTrue(c.disconnected, "all connections are disconnected");
                Assertions.isFalse(c.getInstanceFollowRedirects(), "automatic unvalidated redirects disabled");
                Assertions.equals(15000, c.getConnectTimeout(), "connect bound retained");
                Assertions.equals(30000, c.getReadTimeout(), "read bound retained");
                Assertions.equals("IBC-Manager/" + Version.VERSION + "-runtime-setup", c.getRequestProperty("User-Agent"), "current release agent");
                if (c.inputOpened) Assertions.isTrue(c.inputClosed, "input stream closed");
            }
        }
    }
    static final class Connection extends HttpURLConnection {
        final URI uri; final Reply reply; boolean disconnected; boolean inputOpened; boolean inputClosed;
        Connection(URI uri, Reply reply) throws IOException { super(uri.toURL()); this.uri = uri; this.reply = reply; }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { disconnected = true; }
        @Override public int getResponseCode() throws IOException {
            if (reply.headerException != null) throw reply.headerException;
            if (reply.headerFailure) throw new IOException("Simulated response-header failure"); return reply.status;
        }
        @Override public String getHeaderField(String name) { return name.equals("Location") ? reply.location : null; }
        @Override public long getContentLengthLong() { return reply.length; }
        @Override public InputStream getInputStream() throws IOException {
            if (reply.bodyException != null) throw reply.bodyException;
            if (reply.bodyFailure) throw new IOException("Simulated response-body failure"); inputOpened = true;
            return new java.io.FilterInputStream(new ByteArrayInputStream(reply.body)) {
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (reply.readException != null) throw reply.readException;
                    return super.read(b, off, len);
                }
                @Override public void close() throws IOException { inputClosed = true; super.close(); }
            };
        }
    }
}
