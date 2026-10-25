package io.github.ibcmanager.workaround;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.runtime.IbcLogStateParser;
import io.github.ibcmanager.runtime.JavaBootstrapFailure;
import io.github.ibcmanager.runtime.OfflineApplicationLayoutResolver;
import io.github.ibcmanager.security.CommandResult;
import io.github.ibcmanager.storage.ProfileRepository;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Exact October 11 failure plus real Java module resolution using synthetic module JARs. */
public final class JsobjectDependencyTests implements TestSuite {
    private static final String VERSION = "25.0.4";
    private static final String FAILURE = "java.lang.module.FindException: Module jdk.jsobject not found, required by javafx.web";
    // Deliberately independent of the production component list: omission must fail this test.
    private static final List<String> COMPONENTS = List.of("base", "graphics", "controls", "fxml", "media", "swing", "web", "jsobject");
    @Override public String name() { return "Complete OpenJFX dependencies and real JVM bootstrap failure regression"; }
    @Override public List<NamedTest> tests() {
        List<NamedTest> tests = new ArrayList<>();
        tests.add(new NamedTest("OpenJFX WebView dependency uses the real jdk-jsobject Windows artifact", this::coordinates));
        for (String mode : List.of("complete", "hashes-unavailable", "probe-unavailable", "legacy-removal",
                "artifact-missing", "bad-hash", "empty-module", "wrong-version", "invalid-descriptor", "missing-descriptor",
                "native-boot-error", "stderr-boot-error", "other-missing-module", "cancel-before-jsobject")) {
            tests.add(new NamedTest("jdk.jsobject setup and restoration: " + mode, () -> scenario(mode)));
        }
        tests.add(new NamedTest("real JPMS resolver fails without jdk.jsobject and resolves the complete graph", this::realModuleResolution));
        tests.add(new NamedTest("bootstrap error survives a normal wrapper footer but clears on replacement", this::parserLifecycle));
        tests.add(new NamedTest("bootstrap parsing is scoped to pre-IBC failures and masks sensitive details", this::parserScope));
        return tests;
    }

    private void coordinates() {
        Assertions.equals("jdk.jsobject", RuntimeCatalog.moduleName("jsobject"), "not javafx.jsobject");
        Assertions.equals("https://repo.maven.apache.org/maven2/org/openjfx/jdk-jsobject/25.0.4/jdk-jsobject-25.0.4-win.jar",
                RuntimeCatalog.artifactUrl("jsobject", VERSION), "published group/artifact/version/classifier pattern");
        Assertions.equals("javafx.base,javafx.graphics,javafx.controls,javafx.fxml,javafx.media,javafx.swing,javafx.web,jdk.jsobject",
                RuntimeBundleInstaller.moduleList(), "all required roots, independent expected list");
        Assertions.throwsType(IllegalArgumentException.class, () -> RuntimeCatalog.artifactUrl("jsobject", "26"), "no silent major upgrade");
        Assertions.throwsType(IllegalArgumentException.class, () -> RuntimeCatalog.moduleName("../../x"), "no arbitrary artifact/path");
    }

    private void scenario(String mode) throws Exception {
        Path root = TestSupport.tempDirectory("jsobject-regression");
        try {
            RuntimeDownloadTests.Network network = publisher(mode);
            Profile before = TestSupport.validProfile(root.resolve("gateway"));
            TestSupport.createOfflineGatewayInstallation(before.twsPath(), "1050");
            Path manual = Files.createDirectories(root.resolve("manual/bin"));
            Files.writeString(manual.resolve("retain.txt"), "manual runtime must survive");
            before = before.toBuilder().twsMajorVersion("1050").profileOnlyConfiguration(true)
                    .baseConfigPath(Path.of("")).ibcJavaPath(manual).build();
            final Profile initial = before;
            Path options = new OfflineApplicationLayoutResolver().resolve(before).vmOptionsPath();
            String original = "# owned by user\r\n-Xmx768m\r\n--module-path=C:/manual/fx\r\n";
            Files.writeString(options, original);
            AppPaths paths = new AppPaths(root.resolve("data"));
            ProfileRepository repository = new ProfileRepository(paths); repository.save(before);
            AtomicInteger probes = new AtomicInteger();
            RuntimeBundleInstaller installer = new RuntimeBundleInstaller(network.downloads, (command, input, timeout) -> {
                probes.incrementAndGet();
                Assertions.isTrue(command.get(command.indexOf("--add-modules") + 1).contains("jdk.jsobject"), "probe includes dependency");
                if (mode.equals("probe-unavailable")) throw new IOException("Inventory process unavailable");
                if (mode.equals("native-boot-error")) return new CommandResult(1, "Error occurred during initialization of boot layer\n" + FAILURE, "", false);
                if (mode.equals("stderr-boot-error")) return new CommandResult(1, "", FAILURE, false);
                if (mode.equals("other-missing-module")) return new CommandResult(1, FAILURE.replace("jdk.jsobject", "jdk.xml.dom"), "", false);
                return new CommandResult(0, COMPONENTS.stream().map(c -> (c.equals("jsobject") ? "jdk.jsobject" : "javafx." + c)
                        + "@" + VERSION + "\n").collect(java.util.stream.Collectors.joining()), "", false);
            });
            ManagedRuntimeService service = new ManagedRuntimeService(paths, repository, installer::install);
            Path directory = ManagedRuntimeService.directory(options);
            boolean success = List.of("complete", "hashes-unavailable", "probe-unavailable", "legacy-removal").contains(mode);
            List<String> progress = new ArrayList<>();
            var cancel = new java.util.concurrent.atomic.AtomicBoolean();
            if (success) {
                Profile enabled = service.save(before, before.toBuilder().managedHeadlessRuntime(true).build(),
                        (old, next) -> repository.save(next), p -> { }, progress::add, cancel::get);
                Assertions.fileExists(directory.resolve("payload/javafx/jdk.jsobject.jar"), "eighth module installed");
                Assertions.equals(1, probes.get(), "one local resolution probe");
                Assertions.isTrue(network.requested().contains(java.net.URI.create(jsobjectUrl())), "real HTTP path requested");
                try (var jar = new java.util.jar.JarFile(directory.resolve("payload/javafx/javafx.web.jar").toFile());
                        var descriptor = jar.getInputStream(jar.getEntry("module-info.class"))) {
                    Assertions.isTrue(ModuleDescriptor.read(descriptor).requires().stream().anyMatch(q -> q.name().equals("jdk.jsobject")),
                            "fixture really declares dependency, unlike old dummy descriptor bytes");
                }
                ManagedRuntimeService.checkBeforeStart(enabled, paths);
                Assertions.contains(Files.readString(options), "jdk.jsobject", "launch resolves added module");
                if (mode.equals("hashes-unavailable")) Assertions.contains(Files.readString(directory.resolve("payload/download-provenance.txt")),
                        "Publisher checksum verification SKIPPED", "requested optional publisher-check policy retained");
                if (mode.equals("legacy-removal")) {
                    // Recreate the exact old ownership layout with seven files and their valid
                    // hashes. This modifies fixture data only, never an installed user manifest.
                    Files.delete(directory.resolve("payload/javafx/jdk.jsobject.jar"));
                    String hashes = Files.readString(directory.resolve("payload.sha256"));
                    Files.writeString(directory.resolve("payload.sha256"), hashes.lines().filter(l -> !l.endsWith("javafx/jdk.jsobject.jar"))
                            .collect(java.util.stream.Collectors.joining("\n", "", "\n")));
                    Files.write(directory.resolve("native-cache/glass.dll"), RuntimeDownloadTests.pe());
                    IOException blocked = Assertions.throwsType(IOException.class, () -> ManagedRuntimeService.checkBeforeStart(enabled, paths),
                            "old incomplete payload cannot be silently reused");
                    Assertions.contains(blocked.getMessage(), "Disable and Save", "clear repair route");
                    Assertions.throwsType(IOException.class, () -> service.save(enabled, enabled, (old, next) -> repository.save(next),
                            p -> { }, s -> { }, () -> false), "saving without deselection must not claim repair");
                }
                Profile restored = service.save(enabled, enabled.toBuilder().managedHeadlessRuntime(false).build(),
                        (old, next) -> repository.save(next), p -> { }, s -> { }, () -> false);
                Assertions.equals(manual, restored.ibcJavaPath(), "previous override restored");
                Assertions.equals(original, Files.readString(options), "options restored exactly");
                Assertions.isFalse(Files.exists(directory), "managed files including native cache removed");
            } else {
                IOException error = Assertions.throwsType(IOException.class, () -> service.save(initial,
                        initial.toBuilder().managedHeadlessRuntime(true).build(), (old, next) -> repository.save(next), p -> { },
                        message -> { if (mode.equals("cancel-before-jsobject") && message.contains(" - jsobject")) cancel.set(true); }, cancel::get),
                        "real incompatibility or missing artifact cannot commit");
                if (mode.endsWith("boot-error") || mode.equals("other-missing-module")) {
                    Assertions.contains(error.getMessage(), "cannot start", "known failure is not unavailable verification");
                    Assertions.contains(error.getMessage(), "not found", "module failure retained");
                }
                Assertions.equals(initial, repository.loadAll().profiles().get(0), "failed setup preserves profile");
                Assertions.equals(original, Files.readString(options), "failed setup preserves options");
                Assertions.isFalse(Files.exists(directory), "failed setup not activated");
            }
            Assertions.equals("manual runtime must survive", Files.readString(manual.resolve("retain.txt")), "manual files intact");
            try (var children = Files.list(options.getParent())) {
                Assertions.isFalse(children.anyMatch(p -> p.getFileName().toString().startsWith(".ibc-runtime-stage-")), "no staging leftovers");
            }
            network.assertClosed();
        } finally { TestSupport.deleteTree(root); }
    }

    private static String jsobjectUrl() {
        return "https://repo.maven.apache.org/maven2/org/openjfx/jdk-jsobject/25.0.4/jdk-jsobject-25.0.4-win.jar";
    }
    private static RuntimeDownloadTests.Network publisher(String mode) throws Exception {
        RuntimeDownloadTests.Network network = new RuntimeDownloadTests.Network();
        String jbr = "https://cache-redirector.jetbrains.com/intellij-jbr/jbr-25.0.4-windows-x64-b508.27.zip";
        network.data("https://api.github.com/repos/JetBrains/JetBrainsRuntime/releases?per_page=100&page=1", RuntimeDownloadTests.bytes(
                "[{\"tag_name\":\"jbr-release-25.0.4b508.27\",\"draft\":false,\"prerelease\":false,\"body\":\"[zip](" + jbr + ")\"}]"));
        network.data(RuntimeCatalog.MAVEN + "javafx-controls/maven-metadata.xml", RuntimeDownloadTests.bytes("<versions><version>25.0.4</version></versions>"));
        byte[] runtime = RuntimeDownloadTests.zip(Map.of("jbr/bin/java.exe", RuntimeDownloadTests.pe(),
                "jbr/release", RuntimeDownloadTests.bytes("JAVA_VERSION=\"25.0.4\"\nIMPLEMENTOR=\"JetBrains s.r.o.\"\nIMPLEMENTOR_VERSION=\"JBR-25.0.4+1-508.27-nomod\"\nJAVA_RUNTIME_VERSION=\"25.0.4+1-b508.27\"\n")));
        network.data(jbr, runtime); network.data(jbr + ".checksum", RuntimeDownloadTests.bytes(RuntimeDownloadTests.hash(runtime, "SHA-512")));
        for (String component : COMPONENTS) {
            String artifact = component.equals("jsobject") ? "jdk-jsobject" : "javafx-" + component;
            String url = RuntimeCatalog.MAVEN + artifact + "/" + VERSION + "/" + artifact + "-" + VERSION + "-win.jar";
            Map<String, byte[]> entries = new LinkedHashMap<>();
            byte[] descriptor = JavafxModuleFixture.descriptor(component, VERSION);
            if (component.equals("jsobject")) {
                if (mode.equals("empty-module")) descriptor = JavafxModuleFixture.namedDescriptor("jdk.jsobjectEmpty", VERSION, "");
                if (mode.equals("wrong-version")) descriptor = JavafxModuleFixture.descriptor(component, "24");
                if (mode.equals("invalid-descriptor")) descriptor = new byte[] {1, 2, 3};
            }
            entries.put("module-info.class", descriptor);
            if (component.equals("jsobject") && mode.equals("missing-descriptor")) { entries.clear(); entries.put("placeholder", new byte[] {1}); }
            String dll = switch (component) { case "graphics" -> "glass.dll"; case "media" -> "jfxmedia.dll"; case "web" -> "jfxwebkit.dll"; default -> null; };
            if (dll != null) entries.put(dll, RuntimeDownloadTests.pe());
            byte[] jar = RuntimeDownloadTests.zip(entries);
            network.data(url, jar);
            network.data(url + ".sha256", RuntimeDownloadTests.bytes(RuntimeDownloadTests.hash(jar, "SHA-256")));
            network.data(url + ".sha1", RuntimeDownloadTests.bytes(RuntimeDownloadTests.hash(jar, "SHA-1")));
            if (component.equals("jsobject")) {
                if (mode.equals("artifact-missing")) network.routes.get(url).status = 404;
                if (mode.equals("bad-hash")) network.data(url + ".sha256", RuntimeDownloadTests.bytes("0".repeat(64)));
                if (mode.equals("hashes-unavailable")) {
                    network.routes.get(url + ".sha256").status = 404;
                    network.routes.get(url + ".sha1").status = 404;
                }
            }
        }
        return network;
    }

    private void realModuleResolution() throws Exception {
        Path root = TestSupport.tempDirectory("real-module-graph");
        try {
            // Use the real JPMS resolver with target-system descriptors. A Java 21 host
            // hashes its bundled jdk.jsobject into java.base, unlike the upgradable
            // module in Java 24+. Wrapping system references intentionally excludes
            // those HOST image hashes; this tests dependency closure, not execution
            // of JavaFX 25 bytecode on Java 21 or native Windows operation.
            java.lang.module.ModuleFinder host = java.lang.module.ModuleFinder.ofSystem();
            java.lang.module.ModuleFinder target = new java.lang.module.ModuleFinder() {
                @Override public java.util.Optional<java.lang.module.ModuleReference> find(String name) {
                    if (name.equals("jdk.jsobject")) return java.util.Optional.empty();
                    return host.find(name).map(JsobjectDependencyTests::descriptorReference);
                }
                @Override public java.util.Set<java.lang.module.ModuleReference> findAll() {
                    return host.findAll().stream().filter(m -> !m.descriptor().name().equals("jdk.jsobject"))
                            .map(JsobjectDependencyTests::descriptorReference).collect(java.util.stream.Collectors.toSet());
                }
            };
            Path modules = Files.createDirectory(root.resolve("modules"));
            for (String component : COMPONENTS) {
                if (component.equals("jsobject")) continue;
                Files.write(modules.resolve(RuntimeCatalog.moduleName(component) + ".jar"),
                        RuntimeDownloadTests.zip(Map.of("module-info.class", JavafxModuleFixture.descriptor(component, VERSION))));
            }
            java.util.Set<String> roots = java.util.Set.copyOf(COMPONENTS.stream().filter(c -> !c.equals("jsobject"))
                    .map(RuntimeCatalog::moduleName).toList());
            java.lang.module.FindException missing = Assertions.throwsType(java.lang.module.FindException.class,
                    () -> java.lang.module.Configuration.empty().resolve(
                            java.lang.module.ModuleFinder.compose(java.lang.module.ModuleFinder.of(modules), target),
                            java.lang.module.ModuleFinder.of(), roots), "seven-module graph must fail through real JPMS");
            Assertions.equals("Module jdk.jsobject not found, required by javafx.web", missing.getMessage(), "exact dependency failure");
            Files.write(modules.resolve("jdk.jsobject.jar"), RuntimeDownloadTests.zip(Map.of("module-info.class", JavafxModuleFixture.descriptor("jsobject", VERSION))));
            java.lang.module.Configuration fixed = java.lang.module.Configuration.empty().resolve(
                    java.lang.module.ModuleFinder.compose(java.lang.module.ModuleFinder.of(modules), target),
                    java.lang.module.ModuleFinder.of(), roots);
            for (String component : COMPONENTS) Assertions.isTrue(fixed.findModule(RuntimeCatalog.moduleName(component)).isPresent(),
                    "all eight modules resolve, including the transitive dependency: " + component);
        } finally { TestSupport.deleteTree(root); }
    }

    private static java.lang.module.ModuleReference descriptorReference(java.lang.module.ModuleReference original) {
        return new java.lang.module.ModuleReference(original.descriptor(), original.location().orElse(null)) {
            @Override public java.lang.module.ModuleReader open() throws IOException { return original.open(); }
        };
    }

    private void parserLifecycle() {
        IbcLogStateParser parser = new IbcLogStateParser();
        parser.accept("Starting IBC with this command:");
        parser.accept("Error occurred during initialization of boot layer");
        parser.accept(FAILURE);
        for (String line : List.of("Program has exited", "Error level is 1", "Check for restart", "Normal exit", "Gateway finished at 18:56:27,74")) parser.accept(line);
        Assertions.isTrue(parser.errorExitConfirmed(), "bootstrap failure survives generic normal footer");
        Assertions.equals(FAILURE, parser.bootstrapFailure().orElseThrow(), "precise reason preserved");
        Assertions.notEquals(io.github.ibcmanager.model.RuntimeState.STOPPED, parser.latest().orElseThrow().state(), "not a normal shutdown");
        parser.accept("Starting IBC with this command:");
        Assertions.isTrue(parser.bootstrapFailure().isEmpty(), "new child clears old failure");
        Assertions.isFalse(parser.errorExitConfirmed(), "new generation is assessed separately");
        parser.accept("IBC: Starting Gateway"); parser.accept("Login has completed");
        Assertions.isTrue(parser.loginCompleted(), "later success remains recognized");
    }

    private void parserScope() {
        IbcLogStateParser parser = new IbcLogStateParser();
        parser.accept(FAILURE);
        Assertions.isFalse(parser.errorExitConfirmed(), "unscoped old log does not poison state");
        parser.accept("Starting IBC with this command:"); parser.accept("IBC: Starting Gateway");
        parser.accept(FAILURE);
        Assertions.isFalse(parser.errorExitConfirmed(), "module names in application logs are not pre-main bootstrap errors");
        String masked = JavaBootstrapFailure.message(FAILURE + " Password=private-probe-secret").orElseThrow();
        Assertions.notContains(masked, "private-probe-secret", "probe details redacted");
        Assertions.isTrue(JavaBootstrapFailure.message("WARNING: " + FAILURE).isEmpty(), "no broad error substring rule");
    }
}
