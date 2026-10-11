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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Actual installer/transaction paths with controlled HTTP responses and probe outcomes. */
public final class RuntimeVerificationTests implements TestSuite {
    @Override public String name() { return "Optional runtime probe: unavailable results, honest audit and concise consent"; }
    @Override public List<NamedTest> tests() {
        List<NamedTest> result = new ArrayList<>();
        for (String mode : List.of("empty-error", "launch-io", "capture-io", "denied", "timeout",
                "empty-success", "unknown-output", "both-streams", "oversized-output", "all-unavailable")) {
            result.add(new NamedTest("unavailable runtime probe continues setup and safe removal: " + mode,
                    () -> scenario(mode, true, false)));
        }
        result.add(new NamedTest("successful module report is marked verified", () -> scenario("verified", true, true)));
        result.add(new NamedTest("module report on stderr is accepted", () -> scenario("stderr-inventory", true, true)));
        for (String mode : List.of("stdout-error", "missing-module", "wrong-version", "cancel-before-probe", "cancel-after-probe",
                "cancel-on-warning", "interrupt", "thread-interrupt", "save-failure", "programming-error")) {
            result.add(new NamedTest("optional runtime probe retains failure boundary: " + mode,
                    () -> scenario(mode, false, false)));
        }
        result.add(new NamedTest("runtime consent is short and states skipped checks and shared options", this::consent));
        return result;
    }

    private void scenario(String mode, boolean success, boolean verified) throws Exception {
        Path root = TestSupport.tempDirectory("optional-runtime-probe");
        Logger logger = Logger.getLogger(RuntimeBundleInstaller.class.getName());
        List<String> warningLog = new ArrayList<>();
        Handler collector = new Handler() {
            @Override public void publish(LogRecord record) { warningLog.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(collector);
        try {
            RuntimeDownloadTests.Network network = publisher(mode.equals("all-unavailable"));
            Profile profile = TestSupport.validProfile(root.resolve("application"));
            TestSupport.createOfflineGatewayInstallation(profile.twsPath(), "1050");
            Path manual = Files.createDirectories(root.resolve("manual/bin"));
            Files.writeString(manual.resolve("keep.txt"), "not Manager-owned");
            profile = profile.toBuilder().twsMajorVersion("1050").profileOnlyConfiguration(true)
                    .baseConfigPath(Path.of("")).ibcJavaPath(manual).build();
            Profile before = profile;
            Path options = new OfflineApplicationLayoutResolver().resolve(profile).vmOptionsPath();
            String original = "# original\r\n-Xmx768m\r\n--module-path=C:/manual/fx\r\n";
            Files.writeString(options, original);
            AppPaths paths = new AppPaths(root.resolve("data"));
            ProfileRepository profiles = new ProfileRepository(paths); profiles.save(profile);
            AtomicBoolean cancelled = new AtomicBoolean();
            AtomicInteger executed = new AtomicInteger();
            RuntimeBundleInstaller installer = new RuntimeBundleInstaller(network.downloads, (command, input, timeout) -> {
                executed.incrementAndGet();
                Assertions.equals("--list-modules", command.get(command.size() - 1), "only a local inventory probe");
                Assertions.equals(30L, timeout.getSeconds(), "probe has a bounded deadline");
                Assertions.equals("", input, "no password or other input");
                String modules = RuntimeCatalog.FX_MODULES.stream()
                        .filter(m -> !mode.equals("missing-module") || !m.equals("web"))
                        .map(m -> RuntimeCatalog.moduleName(m) + "@" + (mode.equals("wrong-version") ? "21" : "25.0.4") + "\r\n")
                        .collect(java.util.stream.Collectors.joining());
                if (mode.equals("launch-io")) throw new IOException("Cannot run program: CreateProcess error=5");
                if (mode.equals("capture-io")) throw new IOException("Could not capture command output");
                if (mode.equals("denied")) throw new SecurityException("Probe execution denied");
                if (mode.equals("interrupt")) throw new InterruptedException("interrupted by user");
                if (mode.equals("programming-error")) throw new IllegalStateException("unexpected probe implementation failure");
                if (mode.equals("cancel-after-probe")) cancelled.set(true);
                if (mode.equals("thread-interrupt")) Thread.currentThread().interrupt();
                return switch (mode) {
                    case "empty-error", "all-unavailable", "cancel-after-probe", "thread-interrupt", "cancel-on-warning", "save-failure" -> new CommandResult(1, "", "", false);
                    case "timeout" -> new CommandResult(-1, modules, "", true);
                    case "empty-success" -> new CommandResult(0, " \r\n\t", "", false);
                    case "unknown-output" -> new CommandResult(0, "Probe did not return a module inventory", "", false);
                    case "stdout-error" -> new CommandResult(1, "Error occurred during initialization of boot layer\nToken=stdout-secret\n", "", false);
                    case "both-streams" -> new CommandResult(1, "stdout diagnostic\nPassword=stdout-secret\n", "stderr diagnostic\nToken=stderr-secret\n", false);
                    case "oversized-output" -> new CommandResult(1, "Password=\"" + "hidden-secret".repeat(2000) + "\"", "", false);
                    case "stderr-inventory" -> new CommandResult(0, "", modules, false);
                    default -> new CommandResult(0, modules, "", false);
                };
            });
            ManagedRuntimeService service = new ManagedRuntimeService(paths, profiles, installer::install);
            List<String> progress = new ArrayList<>();
            java.util.function.Consumer<String> onProgress = message -> {
                progress.add(message);
                if ((mode.equals("cancel-before-probe") && message.contains("JavaFX module resolution"))
                        || (mode.equals("cancel-on-warning") && message.contains("Runtime compatibility verification SKIPPED"))) {
                    cancelled.set(true);
                }
            };
            ManagedRuntimeService.ProfileWriter writer = (previous, updated) -> {
                if (mode.equals("save-failure")) throw new IOException("simulated profile save failure");
                profiles.save(updated);
            };
            Profile desired = before.toBuilder().managedHeadlessRuntime(true).build();
            Path managed = ManagedRuntimeService.directory(options);
            if (success) {
                Profile enabled = service.save(before, desired, writer, p -> { }, onProgress, cancelled::get);
                String provenance = Files.readString(managed.resolve("payload/download-provenance.txt"));
                String outcome = verified ? "PASSED" : "SKIPPED";
                Assertions.contains(provenance, "Runtime compatibility verification " + outcome, "truthful audit verdict");
                Assertions.equals(1, executed.get(), "one bounded attempt, no probe loop");
                Assertions.equals(enabled, profiles.loadAll().profiles().get(0), "effective profile committed");
                Assertions.fileExists(enabled.ibcJavaPath().resolve("java.exe"), "downloaded Java retained");
                Assertions.contains(Files.readString(options), VmOptionsPatch.BEGIN, "managed options applied");
                ManagedRuntimeService.checkBeforeStart(enabled, paths);
                if (!verified) {
                    Assertions.isTrue(progress.stream().anyMatch(s -> s.contains("Runtime compatibility verification SKIPPED")), "skip warning reaches GUI progress");
                    Assertions.isTrue(warningLog.stream().anyMatch(s -> s.contains("Runtime compatibility verification SKIPPED")), "skip warning reaches persistent log");
                    Assertions.isFalse(progress.stream().anyMatch(s -> s.contains("Runtime compatibility checks passed")), "unavailable is never claimed passed");
                }
                if (mode.equals("empty-error")) {
                    Assertions.contains(provenance, "exit code 1", "exact screenshot branch reports its exit code");
                    Assertions.contains(provenance, "stdout: (no output)", "empty diagnostics are explicit");
                    Assertions.contains(provenance, "stderr: (no output)", "no empty error suffix");
                }
                if (mode.equals("stdout-error")) Assertions.contains(provenance, "boot layer", "stdout failure reason is retained");
                if (mode.equals("both-streams")) {
                    Assertions.contains(provenance, "stdout diagnostic", "first stream retained");
                    Assertions.contains(provenance, "stderr diagnostic", "second stream retained");
                }
                if (mode.equals("oversized-output")) Assertions.contains(provenance, "output omitted", "oversized diagnostic is omitted, not split");
                Assertions.notContains(provenance, "stdout-secret", "stdout secret masked");
                Assertions.notContains(provenance, "stderr-secret", "stderr secret masked");
                Assertions.notContains(provenance, "hidden-secret", "oversized quoted secret not leaked");
                Assertions.notContains(String.join("\n", warningLog), "stdout-secret", "log secret masked");
                if (mode.equals("all-unavailable")) {
                    Assertions.equals(9, provenance.split("Publisher checksum verification SKIPPED", -1).length - 1, "all nine missing checksums skipped");
                    Assertions.notContains(provenance, "Runtime compatibility verification PASSED", "no manufactured verification");
                }
                // Reopening or editing does not rerun a failed optional probe or lose removal ownership.
                service.save(enabled, enabled, (previous, updated) -> profiles.save(updated), p -> { }, s -> { }, () -> false);
                Assertions.equals(1, executed.get(), "saved payload reuse does not rerun unavailable verification");
                Profile restored = service.save(enabled, enabled.toBuilder().managedHeadlessRuntime(false).build(),
                        (previous, updated) -> profiles.save(updated), p -> { }, s -> { }, () -> false);
                Assertions.equals(manual, restored.ibcJavaPath(), "previous manual override restored");
                Assertions.equals(original, Files.readString(options), "original options restored");
                Assertions.isFalse(Files.exists(managed), "last-owner removal is unchanged");
            } else {
                if (mode.equals("programming-error")) {
                    Assertions.throwsType(IllegalStateException.class,
                            () -> service.save(before, desired, writer, p -> { }, onProgress, cancelled::get), "programming failure is not swallowed");
                } else {
                    IOException error = Assertions.throwsType(IOException.class,
                            () -> service.save(before, desired, writer, p -> { }, onProgress, cancelled::get), "proven incompatibility/cancellation remains blocking");
                    if (mode.equals("wrong-version") || mode.equals("missing-module")) Assertions.contains(error.getMessage(), "module could not be resolved", "actual inventory failure is not unavailable");
                }
                if (mode.equals("interrupt") || mode.equals("thread-interrupt")) Assertions.isTrue(Thread.interrupted(), "interrupt preserved until caller handles it");
                Assertions.equals(original, Files.readString(options), "failure preserves options");
                Assertions.equals(before, profiles.loadAll().profiles().get(0), "failure preserves profile");
                Assertions.isFalse(Files.exists(managed), "failed install not activated");
                Assertions.equals(mode.equals("cancel-before-probe") ? 0 : 1, executed.get(), "bounded execution even on failure");
            }
            Assertions.equals("not Manager-owned", Files.readString(manual.resolve("keep.txt")), "manual files untouched");
            try (var children = Files.list(options.getParent())) {
                Assertions.isFalse(children.anyMatch(p -> p.getFileName().toString().startsWith(".ibc-runtime-stage-")), "no abandoned stage");
            }
            network.assertClosed();
        } finally {
            Thread.interrupted();
            logger.removeHandler(collector);
            TestSupport.deleteTree(root);
        }
    }

    private static RuntimeDownloadTests.Network publisher(boolean absentHashes) throws Exception {
        RuntimeDownloadTests.Network n = new RuntimeDownloadTests.Network();
        String file = "jbr-25.0.4-windows-x64-b508.27.zip";
        String origin = "https://cache-redirector.jetbrains.com/intellij-jbr/" + file;
        String cdn = "https://d2xrhe97vsfxuc.cloudfront.net/" + file;
        n.data("https://api.github.com/repos/JetBrains/JetBrainsRuntime/releases?per_page=100&page=1",
                RuntimeDownloadTests.bytes("[{\"tag_name\":\"jbr-release-25.0.4b508.27\",\"draft\":false,\"prerelease\":false,\"body\":\"[zip](" + origin + ")\"}]"));
        n.data(RuntimeCatalog.MAVEN + "javafx-controls/maven-metadata.xml", RuntimeDownloadTests.bytes("<versions><version>25.0.4</version></versions>"));
        byte[] zip = RuntimeDownloadTests.zip(Map.of("jbr/bin/java.exe", RuntimeDownloadTests.pe(),
                "jbr/release", RuntimeDownloadTests.bytes("JAVA_VERSION=\"25.0.4\"\nIMPLEMENTOR=\"JetBrains s.r.o.\"\n")));
        n.redirect(origin, 302, cdn); n.data(cdn, zip);
        n.redirect(origin + ".checksum", 302, cdn + ".checksum");
        n.data(cdn + ".checksum", RuntimeDownloadTests.bytes(RuntimeDownloadTests.hash(zip, "SHA-512")));
        if (absentHashes) n.routes.get(cdn + ".checksum").status = 404;
        for (String module : RuntimeCatalog.FX_MODULES) {
            Map<String, byte[]> contents = new LinkedHashMap<>();
            contents.put("module-info.class", JavafxModuleFixture.descriptor(module, "25.0.4"));
            String nativeName = switch (module) { case "graphics" -> "glass.dll"; case "web" -> "jfxwebkit.dll"; case "media" -> "jfxmedia.dll"; default -> null; };
            if (nativeName != null) contents.put(nativeName, RuntimeDownloadTests.pe());
            byte[] jar = RuntimeDownloadTests.zip(contents);
            String uri = RuntimeCatalog.MAVEN + RuntimeCatalog.artifactId(module) + "/25.0.4/" + RuntimeCatalog.artifactId(module) + "-25.0.4-win.jar";
            n.data(uri, jar); n.data(uri + ".sha256", RuntimeDownloadTests.bytes(RuntimeDownloadTests.hash(jar, "SHA-256")));
            if (absentHashes) {
                n.routes.get(uri + ".sha256").status = 404;
                n.data(uri + ".sha1", new byte[0]); n.routes.get(uri + ".sha1").status = 404;
            }
        }
        return n;
    }

    private void consent() {
        String text = ManagedRuntimeService.CONSENT;
        Assertions.isTrue(text.split("\\s+").length <= 100, "enable explanation limited to 100 words");
        Assertions.isTrue(ManagedRuntimeService.REMOVE_CONSENT.split("\\s+").length <= 55, "removal explanation limited to 55 words");
        for (String required : List.of("On Save", ".vmoptions", "Stop all profiles", "Unavailable checksum or runtime checks", "warning", "not fully verified", "Manual installations")) {
            Assertions.contains(text, required, "short consent still says what will change: " + required);
        }
        Assertions.notContains(text, "1050 / ", "requested wording retained");
    }
}
