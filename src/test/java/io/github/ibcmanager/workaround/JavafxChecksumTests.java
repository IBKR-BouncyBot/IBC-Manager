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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Maven's optional SHA-256 sidecar is distinct from a missing artifact or failed transfer. */
public final class JavafxChecksumTests implements TestSuite {
    private static final String FILE = "jbr-25.0.4-windows-x64-b508.27.zip";
    private static final String ORIGIN = "https://cache-redirector.jetbrains.com/intellij-jbr/";
    private static final String CDN = "https://d2xrhe97vsfxuc.cloudfront.net/";
    private static final String API = "https://api.github.com/repos/JetBrains/JetBrainsRuntime/releases?per_page=100&page=1";

    @Override public String name() { return "JavaFX repository checksums: real HTTP loop, verification and transactional setup"; }
    @Override public List<NamedTest> tests() {
        List<NamedTest> tests = new ArrayList<>();
        for (String module : RuntimeCatalog.FX_MODULES) {
            tests.add(new NamedTest("missing optional SHA-256 falls back to SHA-1 for " + module,
                    () -> setup("fallback", module, "25.0.4", true, "")));
            tests.add(new NamedTest("SHA-1 mismatch in " + module + " aborts complete setup",
                    () -> setup("sha1-mismatch", module, "25.0.4", false, "checksum mismatch")));
        }
        tests.add(new NamedTest("all eight Maven modules lacking SHA-256 install and remove through CDN setup",
                () -> setup("all-sha1", "base", "25.0.4", true, "")));
        tests.add(new NamedTest("mixed published checksum algorithms remain per artifact",
                () -> setup("mixed", "base", "25.0.4", true, "")));
        tests.add(new NamedTest("published SHA-256 is preferred without requesting SHA-1",
                () -> setup("sha256", "base", "25.0.4", true, "")));
        tests.add(new NamedTest("newer JavaFX catalogue release cannot replace pinned artifact versions",
                () -> setup("all-sha1", "base", "25.0.12", true, "")));
        tests.add(new NamedTest("unqualified JavaFX catalogue version cannot replace pinned artifact versions",
                () -> setup("all-sha1", "base", "25", true, "")));
        tests.add(new NamedTest("uppercase SHA-1 checksum is accepted",
                () -> setup("sha1-upper", "base", "25.0.4", true, "")));
        tests.add(new NamedTest("SHA-1 sidecar may include whitespace and the artifact filename",
                () -> setup("sha1-filename", "base", "25.0.4", true, "")));
        for (int status : List.of(400, 401, 403, 408, 410, 429, 500, 503)) {
            tests.add(new NamedTest("SHA-256 HTTP " + status + " uses the available SHA-1 checksum",
                    () -> setup("sha256-http-" + status, "base", "25.0.4", true, "")));
        }
        for (String mode : List.of("sha256-header", "sha256-body")) {
            tests.add(new NamedTest(mode + " transport failure uses the available SHA-1 checksum",
                    () -> setup(mode, "base", "25.0.4", true, "")));
        }
        for (String mode : List.of("empty", "short", "invalid")) {
            tests.add(new NamedTest("malformed published SHA-256 " + mode + " cannot use SHA-1",
                    () -> setup("sha256-" + mode, "base", "25.0.4", false, mode.equals("empty") ? "empty" : "valid SHA-256")));
            tests.add(new NamedTest("malformed fallback SHA-1 " + mode + " aborts setup",
                    () -> setup("sha1-" + mode, "base", "25.0.4", false, mode.equals("empty") ? "empty" : "valid SHA-1")));
        }
        tests.add(new NamedTest("mismatched published SHA-256 cannot be rescued by a correct SHA-1",
                () -> setup("sha256-mismatch", "base", "25.0.4", false, "checksum mismatch")));
        for (int status : List.of(404, 403, 429, 500)) {
            tests.add(new NamedTest("unavailable SHA-1 HTTP " + status + " warns and continues after unavailable SHA-256",
                    () -> setup("sha1-http-" + status, "base", "25.0.4", true, "")));
        }
        tests.add(new NamedTest("a missing JavaFX JAR remains an artifact error after valid fallback checksum",
                () -> setup("jar-404", "base", "25.0.4", false, "HTTP 404")));
        tests.add(new NamedTest("404 at another redirected resource is not proof the original sidecar is absent",
                () -> setup("redirected-404", "base", "25.0.4", false, "HTTP 404")));
        tests.add(new NamedTest("JetBrains missing SHA-512 warns and continues",
                () -> setup("jbr-404", "base", "25.0.4", true, "")));
        tests.add(new NamedTest("fallback does not bypass module descriptor validation",
                () -> setup("no-descriptor", "base", "25.0.4", false, "module descriptor")));
        tests.add(new NamedTest("fallback does not bypass Windows native library validation",
                () -> setup("no-native", "graphics", "25.0.4", false, "native library")));
        for (String mode : List.of("after-404", "after-sha1", "during-jar")) {
            tests.add(new NamedTest("cancellation " + mode + " rolls back before activation",
                    () -> setup("cancel-" + mode, "base", "25.0.4", false, "cancel")));
        }
        tests.add(new NamedTest("HTTP errors identify the failing path but not secret query parameters", this::errorContext));
        return tests;
    }

    private void setup(String mode, String changed, String advertisedVersion, boolean success, String reason) throws Exception {
        String fxVersion = "25.0.4";
        Path root = TestSupport.tempDirectory("maven-checksum");
        try {
            Network n = new Network();
            n.data(API, RuntimeDownloadTests.bytes("[{\"tag_name\":\"jbr-release-25.0.4b508.27\",\"draft\":false,\"prerelease\":false,\"body\":\""
                    + ORIGIN + FILE + " " + ORIGIN + FILE + ".checksum\"}]"));
            n.data(RuntimeCatalog.MAVEN + "javafx-controls/maven-metadata.xml", RuntimeDownloadTests.bytes(
                    "<metadata><versioning><versions><version>24.0.1</version><version>" + advertisedVersion
                    + "</version><version>26-ea+1</version></versions></versioning></metadata>"));
            byte[] runtime = RuntimeDownloadTests.zip(Map.of("jbr/bin/java.exe", RuntimeDownloadTests.pe(),
                    "jbr/release", RuntimeDownloadTests.bytes("JAVA_VERSION=\"25.0.4\"\nIMPLEMENTOR=\"JetBrains s.r.o.\"\nIMPLEMENTOR_VERSION=\"JBR-25.0.4+1-508.27-nomod\"\nJAVA_RUNTIME_VERSION=\"25.0.4+1-b508.27\"\n")));
            n.redirect(ORIGIN + FILE, 302, CDN + FILE);
            n.redirect(ORIGIN + FILE + ".checksum", 302, CDN + FILE + ".checksum");
            n.data(CDN + FILE, runtime);
            n.data(CDN + FILE + ".checksum", RuntimeDownloadTests.bytes(RuntimeDownloadTests.hash(runtime, "SHA-512")));
            if (mode.equals("jbr-404")) n.routes.get(CDN + FILE + ".checksum").status = 404;
            Map<String, Boolean> fallback = new LinkedHashMap<>();
            for (String module : RuntimeCatalog.FX_MODULES) {
                String uri = artifact(module, fxVersion);
                Map<String, byte[]> entries = new LinkedHashMap<>();
                entries.put("module-info.class", JavafxModuleFixture.descriptor(module, fxVersion));
                String nativeName = switch (module) { case "graphics" -> "glass.dll"; case "media" -> "jfxmedia.dll"; case "web" -> "jfxwebkit.dll"; default -> null; };
                if (nativeName != null) entries.put(nativeName, RuntimeDownloadTests.pe());
                if (module.equals(changed) && mode.equals("no-descriptor")) entries.remove("module-info.class");
                if (module.equals(changed) && mode.equals("no-native")) entries.remove(nativeName);
                byte[] jar = RuntimeDownloadTests.zip(entries);
                n.data(uri, jar);
                n.data(uri + ".sha256", RuntimeDownloadTests.bytes(RuntimeDownloadTests.hash(jar, "SHA-256")));
                n.data(uri + ".sha1", RuntimeDownloadTests.bytes(RuntimeDownloadTests.hash(jar, "SHA-1")));
                boolean missing = mode.equals("all-sha1") || (mode.equals("mixed") && RuntimeCatalog.FX_MODULES.indexOf(module) % 2 == 0)
                        || (module.equals(changed) && !mode.equals("sha256") && !mode.equals("mixed")
                        && !mode.startsWith("sha256-") && !mode.equals("redirected-404"));
                fallback.put(module, missing || (module.equals(changed)
                        && (mode.startsWith("sha256-http-") || mode.equals("sha256-header") || mode.equals("sha256-body"))));
                if (missing) n.routes.get(uri + ".sha256").status = 404;
                if (module.equals(changed)) configure(n, mode, uri, jar);
            }
            Profile original = TestSupport.validProfile(root.resolve("app"));
            TestSupport.createOfflineGatewayInstallation(original.twsPath(), "1050");
            Path manual = Files.createDirectories(root.resolve("manual/bin"));
            Files.writeString(manual.resolve("keep.txt"), "user-owned");
            Profile before = original.toBuilder().twsMajorVersion("1050").profileOnlyConfiguration(true)
                    .baseConfigPath(Path.of("")).ibcJavaPath(manual).build();
            Path options = new OfflineApplicationLayoutResolver().resolve(before).vmOptionsPath();
            String originalOptions = "# keep custom settings\r\n-Xmx768m\r\n--module-path=C:/manual/fx\r\n--add-modules=javafx.controls\r\n";
            Files.writeString(options, originalOptions);
            AppPaths paths = new AppPaths(root.resolve("data"));
            ProfileRepository repo = new ProfileRepository(paths); repo.save(before);
            AtomicInteger executions = new AtomicInteger();
            RuntimeBundleInstaller installer = new RuntimeBundleInstaller(n.downloads, (cmd, in, timeout) -> {
                executions.incrementAndGet();
                Assertions.equals("--list-modules", cmd.get(cmd.size() - 1), "only verification, never broker login");
                return new CommandResult(0, RuntimeCatalog.FX_MODULES.stream().map(m -> RuntimeCatalog.moduleName(m) + "@" + fxVersion + "\n")
                        .collect(java.util.stream.Collectors.joining()), "", false);
            });
            ManagedRuntimeService service = new ManagedRuntimeService(paths, repo, installer::install);
            List<String> progress = new ArrayList<>();
            Profile desired = before.toBuilder().managedHeadlessRuntime(true).build();
            String uri = artifact(changed, fxVersion);
            java.util.function.BooleanSupplier cancelled = () -> switch (mode) {
                case "cancel-after-404" -> n.requested().contains(URI.create(uri + ".sha256"));
                case "cancel-after-sha1" -> n.requested().contains(URI.create(uri + ".sha1"));
                case "cancel-during-jar" -> n.requested().contains(URI.create(uri));
                default -> false;
            };
            if (success) {
                Profile enabled = service.save(before, desired, (old, next) -> repo.save(next), p -> { }, progress::add, cancelled);
                Assertions.equals(1, executions.get(), "runtime validation follows all required downloads and available hash checks");
                Assertions.isTrue(enabled.managedHeadlessRuntime(), "profile committed");
                Path managed = ManagedRuntimeService.directory(options);
                String provenance = Files.readString(managed.resolve("payload/download-provenance.txt"));
                for (String module : RuntimeCatalog.FX_MODULES) {
                    boolean useSha1 = fallback.get(module);
                    String path = artifact(module, fxVersion);
                    Assertions.equals(useSha1, n.requested().contains(URI.create(path + ".sha1")), "fallback used only when needed: " + module);
                    Assertions.contains(provenance, path + (useSha1 ? ".sha1" : ".sha256"), "actual checksum source retained");
                    if (module.equals(changed) && mode.startsWith("sha1-http-")) {
                        Assertions.contains(provenance, "Publisher checksum verification SKIPPED", "absent verification is explicit");
                    } else {
                        Assertions.contains(provenance, useSha1 ? "Published SHA-1" : "Published SHA-256", "published verification algorithm named");
                    }
                }
                boolean skipped = mode.equals("jbr-404") || mode.startsWith("sha1-http-");
                Assertions.equals(skipped, progress.stream().anyMatch(s -> s.startsWith("WARNING:") && s.contains("SKIPPED")),
                        "only absent verification generates the unchecked warning");
                Assertions.contains(provenance, "Local SHA-256", "strong local audit digest is not claimed as a published hash");
                if (fallback.containsValue(true)) Assertions.isTrue(progress.stream().anyMatch(s -> s.contains("SHA-1")), "fallback is disclosed");
                Profile restored = service.save(enabled, enabled.toBuilder().managedHeadlessRuntime(false).build(),
                        (old, next) -> repo.save(next), p -> { }, s -> { }, () -> false);
                Assertions.equals(manual, restored.ibcJavaPath(), "previous override restored");
                Assertions.equals(originalOptions, Files.readString(options), "original options restored byte-for-byte");
                Assertions.isFalse(Files.exists(managed), "last-owner removal remains complete");
            } else {
                IOException error = Assertions.throwsType(IOException.class, () -> service.save(before, desired,
                        (old, next) -> repo.save(next), p -> { }, progress::add, cancelled), "invalid/missing files cannot commit");
                Assertions.contains(error.getMessage(), reason, "specific failure retained");
                Assertions.equals(0, executions.get(), "invalid or incomplete setup never executes a runtime");
                Assertions.equals(before, repo.loadAll().profiles().get(0), "profile unchanged");
                Assertions.equals(originalOptions, Files.readString(options), "options unchanged");
                Assertions.isFalse(Files.exists(ManagedRuntimeService.directory(options)), "no managed activation");
                try (var dirs = Files.list(options.getParent())) {
                    Assertions.isFalse(dirs.anyMatch(p -> p.getFileName().toString().startsWith(".ibc-runtime-stage-")), "staging cleaned");
                }
                if (mode.startsWith("sha256-") || mode.equals("redirected-404") || mode.equals("cancel-after-404")) {
                    Assertions.isFalse(n.requested().contains(URI.create(uri + ".sha1")), "no downgrade or next request after failure/cancellation");
                }
                if (mode.equals("cancel-after-sha1")) Assertions.isFalse(n.requested().contains(URI.create(uri)), "cancel before artifact download");
            }
            Assertions.isFalse(n.requested().stream().anyMatch(u -> u.getPath().endsWith(".md5")), "never fall back to MD5");
            Assertions.equals("user-owned", Files.readString(manual.resolve("keep.txt")), "manual runtime untouched");
            Assertions.isFalse(n.requested().stream().anyMatch(u -> u.getPath().endsWith("maven-metadata.xml")), "fixed pins never request catalogue metadata");
            n.assertClosed();
        } finally { TestSupport.deleteTree(root); }
    }

    private static void configure(Network n, String mode, String uri, byte[] jar) throws Exception {
        if (mode.startsWith("sha256-http-")) n.routes.get(uri + ".sha256").status = Integer.parseInt(mode.substring(12));
        if (mode.startsWith("sha1-http-")) n.routes.get(uri + ".sha1").status = Integer.parseInt(mode.substring(10));
        switch (mode) {
            case "sha256-header" -> n.routes.get(uri + ".sha256").headerFailure = true;
            case "sha256-body" -> n.routes.get(uri + ".sha256").bodyFailure = true;
            case "sha256-empty" -> n.data(uri + ".sha256", new byte[0]);
            case "sha256-short" -> n.data(uri + ".sha256", RuntimeDownloadTests.bytes("a".repeat(63)));
            case "sha256-invalid" -> n.data(uri + ".sha256", RuntimeDownloadTests.bytes("g".repeat(64)));
            case "sha256-mismatch" -> n.data(uri + ".sha256", RuntimeDownloadTests.bytes("0".repeat(64)));
            case "sha1-empty" -> n.data(uri + ".sha1", new byte[0]);
            case "sha1-short" -> n.data(uri + ".sha1", RuntimeDownloadTests.bytes("a".repeat(39)));
            case "sha1-invalid" -> n.data(uri + ".sha1", RuntimeDownloadTests.bytes("g".repeat(40)));
            case "sha1-mismatch" -> n.data(uri + ".sha1", RuntimeDownloadTests.bytes("0".repeat(40)));
            case "sha1-upper" -> n.data(uri + ".sha1", RuntimeDownloadTests.bytes(RuntimeDownloadTests.hash(jar, "SHA-1").toUpperCase(java.util.Locale.ROOT)));
            case "sha1-filename" -> n.data(uri + ".sha1", RuntimeDownloadTests.bytes(" \r\n" + RuntimeDownloadTests.hash(jar, "SHA-1")
                    + "  " + uri.substring(uri.lastIndexOf('/') + 1) + "\r\n"));
            case "jar-404" -> n.routes.get(uri).status = 404;
            case "redirected-404" -> {
                n.redirect(uri + ".sha256", 302, RuntimeCatalog.MAVEN + "different/missing.sha256");
                n.data(RuntimeCatalog.MAVEN + "different/missing.sha256", new byte[0]);
                n.routes.get(RuntimeCatalog.MAVEN + "different/missing.sha256").status = 404;
            }
            default -> { }
        }
    }
    private static String artifact(String module, String version) {
        return RuntimeCatalog.MAVEN + RuntimeCatalog.artifactId(module) + "/" + version + "/" + RuntimeCatalog.artifactId(module) + "-" + version + "-win.jar";
    }
    private void errorContext() throws Exception {
        Network n = new Network();
        String path = artifact("base", "25.0.4") + ".sha256";
        String uri = path + "?token=do-not-print";
        n.data(uri, new byte[0]); n.routes.get(uri).status = 404;
        IOException error = Assertions.throwsType(IOException.class, () -> n.downloads.text(URI.create(uri), 100), "HTTP error expected");
        Assertions.contains(error.getMessage(), path, "path distinguishes missing hash from missing jar");
        Assertions.notContains(error.getMessage(), "do-not-print", "query excluded");
        n.assertClosed();
    }
}
