package io.github.ibcmanager.workaround;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.app.ProfileCopyService;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.TargetType;
import io.github.ibcmanager.security.CommandResult;
import io.github.ibcmanager.storage.ProfileCodec;
import io.github.ibcmanager.storage.ProfileRepository;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Offline publisher/process fixtures; no download, broker login, or production filesystem is used. */
public final class WorkaroundTests implements TestSuite {
    @Override public String name() { return "Managed JBR25/JavaFX setup: ownership, consent boundaries, rollback and supply chain"; }
    @Override public List<NamedTest> tests() {
        List<NamedTest> tests = new ArrayList<>(List.of(
                new NamedTest("runtime selection pins JBR even when a newer stable build is advertised", this::catalog),
                new NamedTest("fixed coordinates are independent of unavailable release catalogue listings", this::noRelease),
                new NamedTest("JavaFX metadata stays in the released 25 family", this::fxMetadata),
                new NamedTest("release JSON decoder handles escapes and rejects ambiguity", this::json),
                new NamedTest("runtime downloads permit only HTTPS publisher paths and safe redirects", this::uris),
                new NamedTest("complete verified runtime setup resolves all seven JavaFX modules", this::installer),
                new NamedTest("checksum mismatch aborts before executable verification", this::checksum),
                new NamedTest("missing JavaFX native binary aborts setup", this::missingNative),
                new NamedTest("wrong JavaFX module version aborts setup", this::badModules),
                new NamedTest("non-Windows Java launcher aborts setup", this::badPlatform),
                new NamedTest("unavailable runtime verification is reported without blocking setup", this::runtimeFailure),
                new NamedTest("metadata network failure occurs before any application configuration write", this::networkFailure),
                new NamedTest("owned VM options preserve external edits on removal", this::externalOptions),
                new NamedTest("changed owned block prevents unsafe removal", this::tamperedOptions),
                new NamedTest("new external module options prevent ambiguous rollback", this::conflictingOptions),
                new NamedTest("VM options reject invalid UTF-8 and oversize input", this::invalidOptions),
                new NamedTest("managed runtime flag survives profile codec and equality", this::codec),
                new NamedTest("format six profiles load with automatic download disabled", this::legacyCodec),
                new NamedTest("copy uses independent identity and never duplicates password or active ownership", this::copy),
                new NamedTest("copy preserves normal manual override and creates unique name and ports", this::copyName),
                new NamedTest("ordinary profile save does not inspect or download runtimes", this::ordinary),
                new NamedTest("enable and disable restores original bytes and manual Java override", this::roundTrip),
                new NamedTest("shared installation keeps downloads until every owner disables", this::sharing),
                new NamedTest("disable preserves modified unrelated VM options", this::editedUninstall),
                new NamedTest("download failure leaves profile options and manual files intact", this::downloadFailure),
                new NamedTest("cancellation before commit leaves no installed files", this::cancel),
                new NamedTest("failed profile commit rolls setup back", this::saveFailure),
                new NamedTest("failed profile commit during removal restores the managed configuration", this::removeFailure),
                new NamedTest("active installation guard prevents downloads and writes", this::active),
                new NamedTest("late active installation guard prevents downloaded stage activation", this::lateActive),
                new NamedTest("editing options during download refuses to overwrite the edit", this::editDuringDownload),
                new NamedTest("tampered runtime payload cannot launch or be deleted", this::tamperedPayload),
                new NamedTest("unexpected files in owned directory are preserved", this::extraFile),
                new NamedTest("manually borrowed runtime blocks last-owner uninstall", this::borrowed),
                new NamedTest("profile installation cannot change until managed runtime is disabled", this::changedTarget),
                new NamedTest("new profile failure does not overwrite another runtime folder", this::foreignDirectory),
                new NamedTest("different data-directory owners share without deleting each other's runtimes", this::otherManager),
                new NamedTest("JavaFX native cache is owned and removed only after verification", this::cache),
                new NamedTest("unrecognized JavaFX native cache files are not deleted", this::badCache),
                new NamedTest("TWS setup patches tws.vmoptions without touching Gateway options", this::tws),
                new NamedTest("older application rejects the Java25 helper without changes", this::oldApplication),
                new NamedTest("incomplete managed ownership rejects startup", this::missingOwnership),
                new NamedTest("ordinary launch check does not create runtime state or lock files", this::ordinaryLaunch),
                new NamedTest("unsupported VM options filename is rejected", this::unknownLayout)));
        for (String input : List.of("", "-Xmx768m", "-Xmx768m\n", "-Xmx768m\r\n", "# header\r\n--module-path=C:/manual\r\n--add-modules=javafx.controls\r\n-Xmx768m\r\n", "\ufeff-Xmx512m\n", "-Djavafx.cachedir=C:/old\n")) {
            tests.add(new NamedTest("exact reversible VM options bytes variant " + tests.size(), () -> exactOptions(input)));
        }
        for (String bad : List.of("../escape", "/absolute", "C:/drive", "x/../escape", "x\\file", "CON", "x/nul.txt", "x/trailing.", "x/trailing ", "x/a:b", "x/question?")) {
            tests.add(new NamedTest("runtime ZIP rejects ambiguous entry " + bad, () -> badZip(bad)));
        }
        for (boolean disable : List.of(false, true)) for (boolean committed : List.of(false, true)) {
            tests.add(new NamedTest("interrupted " + (disable ? "removal" : "installation") + " recovers after profile commit=" + committed,
                    () -> interrupted(disable, committed)));
        }
        tests.add(new NamedTest("managed ownership cannot be encoded in legacy external-config format", () -> {
            Assertions.throwsType(IllegalArgumentException.class, () -> new ProfileCodec().encode(
                    Profile.builder().managedHeadlessRuntime(true).profileOnlyConfiguration(false).build()), "ownership may not silently vanish");
        }));
        tests.add(new NamedTest("a forced true headless property must not silently override the workaround", () -> {
            Assertions.throwsType(IOException.class, () -> VmOptionsPatch.apply(
                    "-Xmx768m\n-Djava.awt.headless=true\n".getBytes(StandardCharsets.UTF_8), Path.of("/runtime/fx")), "contradictory options need user review");
        }));
        tests.add(new NamedTest("archive implicit-directory casing cannot alias on Windows", this::archiveCaseAlias));
        tests.add(new NamedTest("JBR pin does not request or page the GitHub release catalogue", this::pagedCatalogue));
        tests.add(new NamedTest("non-pinned managed runtime cannot be started but can be removed and reinstalled", this::oldManagedPin));
        tests.add(new NamedTest("non-pinned shared runtime cannot be adopted by another profile", this::oldSharedPin));
        tests.add(new NamedTest("downloaded later JBR metadata is rejected before local execution", this::wrongPinnedRelease));
        tests.add(new NamedTest("wrong JBR build with the same Java version is rejected", () -> pinnedBuildMetadata(false)));
        tests.add(new NamedTest("available exact JBR build metadata permits installation", () -> pinnedBuildMetadata(true)));
        tests.add(new NamedTest("unavailable pinned archive never selects a newer or older runtime", this::missingPinnedArchive));
        tests.add(new NamedTest("copied data directories cannot borrow another installation owner", () -> {
            try (Fixture f = new Fixture()) {
                Profile enabled = f.enable();
                Assertions.throwsType(IOException.class, () -> ManagedRuntimeService.checkBeforeStart(enabled,
                        new AppPaths(f.root.resolve("different-data"))), "same UUID is not ownership across a different data directory");
            }
        }));
        tests.add(new NamedTest("profile edits after an interrupted setup prevent destructive rollback", this::interruptedProfileEdit));
        tests.add(new NamedTest("partial owned deletion resumes safely from its hash receipt", () -> deletionResume(false)));
        tests.add(new NamedTest("partial deletion preserves a changed leftover file", () -> deletionResume(true)));
        for (boolean removal : List.of(false, true)) {
            tests.add(new NamedTest("late profile-save failure keeps runtime consistent for removal=" + removal,
                    () -> lateProfileFailure(removal)));
        }
        tests.add(new NamedTest("unreadable late-save profile retains runtime evidence instead of deleting it", this::unreadableLateProfile));
        tests.add(new NamedTest("managed ownership cannot adopt its own directory as the previous manual override", this::selfBorrowedOverride));
        return tests;
    }

    private void lateProfileFailure(boolean removal) throws Exception {
        try (Fixture f = new Fixture()) {
            Profile prior = removal ? f.enable() : f.profile;
            Profile desired = prior.toBuilder().managedHeadlessRuntime(!removal).build();
            Assertions.throwsType(IOException.class, () -> f.service.save(prior, desired, (old, next) -> {
                f.repo.save(next);
                throw new IOException("Post-rename ACL/rollback failure (fixture)");
            }, p -> { }, s -> { }, () -> false), "late write failure remains visible");
            Assertions.fileExists(f.dir.resolve("pending.properties"), "journal retained after committed-profile failure");
            Assertions.fileExists(f.dir.resolve("payload/jbr/bin/java.exe"), "runtime not deleted before journal recovery");
            Profile persisted = f.repo.loadAll().profiles().get(0);
            Assertions.equals(!removal, persisted.managedHeadlessRuntime(), "actual profile commit retained");
            f.service.recoverPending();
            if (removal) {
                Assertions.isFalse(Files.exists(f.dir), "committed removal completes on recovery");
                same(f.original, Files.readAllBytes(f.options), "old options retained after committed removal");
            } else {
                ManagedRuntimeService.checkBeforeStart(persisted, f.paths);
                Assertions.isFalse(Files.exists(f.dir.resolve("pending.properties")), "committed installation finalized");
                f.disable(persisted);
                f.assertOriginal();
            }
        }
    }

    private void unreadableLateProfile() throws Exception {
        try (Fixture f = new Fixture()) {
            Assertions.throwsType(IOException.class, () -> f.service.save(f.profile,
                    f.profile.toBuilder().managedHeadlessRuntime(true).build(), (old, next) -> {
                        Files.writeString(f.paths.profileFile(next.id()), "invalid profile bytes");
                        throw new IOException("Indeterminate commit (fixture)");
                    }, p -> { }, s -> { }, () -> false), "ambiguous profile failure reported");
            Assertions.fileExists(f.dir.resolve("pending.properties"), "indeterminate state remains recoverable");
            Assertions.fileExists(f.dir.resolve("payload/jbr/bin/java.exe"), "runtime preserved without guessing");
            Assertions.equals("invalid profile bytes", Files.readString(f.paths.profileFile(f.profile.id())), "no implicit backup recovery in commit decision");
            Assertions.contains(Files.readString(f.options), VmOptionsPatch.BEGIN, "committed runtime options remain for investigation");
        }
    }

    private void selfBorrowedOverride() throws Exception {
        try (Fixture f = new Fixture()) {
            Profile enabled = f.enable();
            Profile copy = f.profile.toBuilder().id(UUID.randomUUID()).ibcJavaPath(enabled.ibcJavaPath()).build();
            f.repo.save(copy);
            Assertions.throwsType(IOException.class, () -> f.service.save(copy,
                    copy.toBuilder().managedHeadlessRuntime(true).build(), f.writer(), p -> { }, s -> { }, () -> false),
                    "managed directory cannot become a dangling previous override");
            ManagedRuntimeService.checkBeforeStart(enabled, f.paths);
            Assertions.fileExists(enabled.ibcJavaPath().resolve("java.exe"), "existing owner untouched");
        }
    }

    private void interruptedProfileEdit() throws Exception {
        try (Fixture f = new Fixture()) {
            Profile desired = f.profile.toBuilder().managedHeadlessRuntime(true).build();
            Assertions.throwsType(SimulatedCrash.class, () -> f.service.save(f.profile, desired, (p,c) -> { throw new SimulatedCrash(); }, p -> { }, s -> { }, () -> false), "crash after VM write");
            f.repo.save(f.profile.toBuilder().name("independent edit").build());
            byte[] before = Files.readAllBytes(f.options);
            Assertions.throwsType(IOException.class, f.service::recoverPending, "unknown profile transition refused");
            same(before, Files.readAllBytes(f.options), "no rollback guessed from arbitrary profile difference");
            Assertions.fileExists(f.dir.resolve("pending.properties"), "evidence preserved");
        }
    }

    private void deletionResume(boolean corrupt) throws Exception {
        try (Fixture f = new Fixture()) {
            Profile enabled = f.enable();
            // Model an interrupted removal after profile/options commit and after some files
            // have been deleted. This is not a claim of testing native Windows file locking.
            java.util.Properties receipt = new java.util.Properties();
            receipt.setProperty("format", "1"); receipt.setProperty("directory", f.dir.toString());
            try (var stream = Files.walk(f.dir)) {
                for (Path file : stream.toList()) {
                    if (file.equals(f.dir)) continue;
                    String key = f.dir.relativize(file).toString().replace('\\', '/');
                    if (Files.isDirectory(file)) receipt.setProperty("dir." + key, "true");
                    else receipt.setProperty("file." + key, RuntimeBundleInstaller.hash(file, "SHA-256"));
                }
            }
            try (var file = Files.newOutputStream(f.dir.resolve("deletion.properties"))) { receipt.store(file, "fixture"); }
            f.repo.save(enabled.toBuilder().managedHeadlessRuntime(false).ibcJavaPath(f.manual).build());
            Files.write(f.options, f.original);
            Files.delete(f.dir.resolve("payload/jbr/bin/java.exe"));
            if (corrupt) Files.writeString(f.dir.resolve("state.properties"), "changed externally");
            if (corrupt) {
                Assertions.throwsType(IOException.class, f.service::recoverPending, "changed leftover file retained");
                Assertions.equals("changed externally", Files.readString(f.dir.resolve("state.properties")), "user bytes untouched");
            } else {
                f.service.recoverPending(); Assertions.isFalse(Files.exists(f.dir), "remaining owned files removed");
                same(f.original, Files.readAllBytes(f.options), "original options remain restored");
            }
        }
    }

    private void archiveCaseAlias() throws Exception {
        Path root = TestSupport.tempDirectory("case-alias");
        try {
            Path archive = root.resolve("input.zip");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                for (String name : List.of("JBR/lib/one", "jbr/lib/two")) {
                    zip.putNextEntry(new ZipEntry(name)); zip.write(1); zip.closeEntry();
                }
            }
            Path dest = Files.createDirectory(root.resolve("out"));
            Assertions.throwsType(IOException.class, () -> RuntimeBundleInstaller.extract(archive, dest), "case aliases rejected");
        } finally { TestSupport.deleteTree(root); }
    }

    private void pagedCatalogue() throws Exception {
        RuntimeDownloads source = new RuntimeDownloads() {
            @Override public String text(URI uri, int max) {
                if (uri.toString().contains("maven-metadata")) return "<metadata><version>25.0.4</version></metadata>";
                throw new AssertionError("JBR pin must not query a moving catalogue: " + uri);
            }
            @Override public void file(URI uri, Path p, long limit, BooleanSupplier cancelled) { throw new AssertionError("catalogue must not download binaries"); }
        };
        Assertions.equals("25.0.4-b508.27", new RuntimeCatalog(source).resolve().jbrVersion(), "JBR stays pinned without a catalogue");
    }

    private void oldManagedPin() throws Exception {
        try (Fixture f = new Fixture()) {
            Profile enabled = f.enable();
            Path state = f.dir.resolve("state.properties");
            Files.writeString(state, Files.readString(state).replace("25.0.4-b508.27", "25.0.4.1-b635.70"));
            byte[] options = Files.readAllBytes(f.options);
            IOException error = Assertions.throwsType(IOException.class, () -> ManagedRuntimeService.checkBeforeStart(enabled, f.paths), "pin enforced on cached installation");
            Assertions.contains(error.getMessage(), "Disable and Save", "actionable migration");
            Assertions.throwsType(IOException.class, () -> f.service.save(enabled, enabled, f.writer(), p -> { }, x -> { }, () -> false), "save cannot quietly reuse new build");
            same(options, Files.readAllBytes(f.options), "no configuration edit on rejected reuse");
            Assertions.fileExists(enabled.ibcJavaPath().resolve("java.exe"), "no arbitrary deletion");
            f.disable(enabled); f.assertOriginal();
            Profile repaired = f.enable(); ManagedRuntimeService.checkBeforeStart(repaired, f.paths);
            Assertions.equals(2, f.downloads.get(), "explicit reinstall uses installer, not stale cache");
            Assertions.contains(Files.readString(f.dir.resolve("state.properties")), "25.0.4-b508.27", "pin recorded");
        }
    }
    private void oldSharedPin() throws Exception {
        try (Fixture f = new Fixture()) {
            Profile enabled = f.enable();
            Path state = f.dir.resolve("state.properties");
            Files.writeString(state, Files.readString(state).replace("25.0.4-b508.27", "25.0.4.1-b635.70"));
            Profile copy = f.profile.toBuilder().id(UUID.randomUUID()).name("Another owner").managedHeadlessRuntime(true).build();
            Assertions.throwsType(IOException.class, () -> f.service.save(null, copy, f.writer(), p -> { }, x -> { }, () -> false), "cannot acquire unsupported cached runtime");
            Assertions.fileExists(enabled.ibcJavaPath().resolve("java.exe"), "existing owner protected");
            Assertions.equals(1, f.downloads.get(), "no unrelated network or implicit runtime replacement");
        }
    }
    private void wrongPinnedRelease() throws Exception {
        try (InstallFixture f = new InstallFixture()) {
            f.downloads.archive = zip(Map.of("jbr/bin/java.exe", pe(), "jbr/release", bytes("JAVA_VERSION=\"25.0.4.1\"\nIMPLEMENTOR=\"JetBrains\"\n")));
            IOException error = Assertions.throwsType(IOException.class, f::run, "server returning wrong version cannot defeat pin");
            Assertions.contains(error.getMessage(), "pinned", "diagnosis names policy");
            Assertions.equals(0, f.executions.get(), "wrong runtime not executed");
        }
    }
    private void pinnedBuildMetadata(boolean correct) throws Exception {
        try (InstallFixture f = new InstallFixture()) {
            String build = correct ? "508.27" : "635.70";
            f.downloads.archive = zip(Map.of("jbr/bin/java.exe", pe(), "jbr/release", bytes(
                    "JAVA_VERSION=\"25.0.4\"\nIMPLEMENTOR=\"JetBrains\"\nIMPLEMENTOR_VERSION=\"JBR-25.0.4+1-b" + build + "\"\n")));
            if (correct) {
                f.run(); Assertions.equals(1, f.executions.get(), "exact pinned build may be checked");
            } else {
                Assertions.throwsType(IOException.class, f::run, "same Java version is not the same JBR build");
                Assertions.equals(0, f.executions.get(), "wrong build not executed");
            }
        }
    }

    private void missingPinnedArchive() throws Exception {
        try (InstallFixture f = new InstallFixture()) {
            f.downloads.archiveUnavailable = true;
            Assertions.throwsType(IOException.class, f::run, "no available pinned archive");
            Assertions.equals(0, f.executions.get(), "nothing launched");
            Assertions.isTrue(f.downloads.files.stream().allMatch(uri -> uri.equals(RuntimeCatalog.JBR_ZIP)), "no fallback to another runtime");
            Assertions.isTrue(!f.downloads.files.isEmpty(), "actual archive download attempted");
        }
    }

    private void catalog() throws Exception {
        FakeDownloads downloads = new FakeDownloads();
        downloads.releases = "[" + release("25.0.3", "99.1", false, false) + ","
                + release("25.0.4", "329.128", false, false) + "," + release("25.0.4", "508.27", false, false) + ","
                + release("25.0.4.1", "635.70", false, false) + "," + release("25.0.8", "600.1", true, false) + ","
                + release("26.0.4", "900.1", false, false) + "]";
        var selected = new RuntimeCatalog(downloads).resolve();
        Assertions.equals("25.0.4-b508.27", selected.jbrVersion(), "exact compatibility build");
        Assertions.equals("25.0.4", selected.javafxVersion(), "newest stable 25 FX");
        Assertions.contains(selected.jbrZip().toString(), "windows-x64-b508.27.zip", "release architecture/flavour");
    }
    private void noRelease() throws Exception {
        for (String json : List.of("[]", "{}", "[{\"tag_name\":\"jbr-release-25.0.4b508.27\",\"draft\":false,\"prerelease\":false,\"body\":\"no checksum\"}]")) {
            FakeDownloads downloads = new FakeDownloads(); downloads.releases = json;
            Assertions.equals(RuntimeCatalog.JBR_ZIP, new RuntimeCatalog(downloads).resolve().jbrZip(), "fixed published ZIP is independent of catalogue");
        }
    }
    private void fxMetadata() throws Exception {
        Assertions.equals("25.0.12", RuntimeCatalog.latestJavafxVersion("<versions><version>25.0.4</version><version>25.0.12</version><version>26</version><version>25.1-ea+1</version></versions>"), "numeric ordering");
        for (String xml : List.of("<version>26</version>", "<!DOCTYPE foo><version>25</version>", "<version>25-ea</version>")) {
            Assertions.throwsType(IOException.class, () -> RuntimeCatalog.latestJavafxVersion(xml), "untrusted or incompatible metadata");
        }
    }
    private void json() throws Exception {
        Assertions.equals("x\ny", ((Map<?, ?>)Json.read("{\"v\":\"x\\ny\"}")).get("v"), "escaped string");
        for (String bad : List.of("{\"x\":1,\"x\":2}", "[true,]", "{} trailing", "{\"x\":\"\\q\"}", "[".repeat(34) + "]".repeat(34))) {
            Assertions.throwsType(IOException.class, () -> Json.read(bad), "malformed JSON rejected");
        }
    }
    private void uris() throws Exception {
        RuntimeDownloads.requireAllowed(URI.create(RuntimeCatalog.MAVEN + "javafx-base/25/javafx-base-25-win.jar"));
        RuntimeDownloads.requireAllowed(URI.create("https://cache-redirector.jetbrains.com/intellij-jbr/test.zip"));
        for (String uri : List.of("http://cache-redirector.jetbrains.com/intellij-jbr/a", "https://evil.invalid/a", "https://api.github.com/repos/evil/evil/releases", "https://user@repo.maven.apache.org/maven2/org/openjfx/a", "https://repo.maven.apache.org:8443/maven2/org/openjfx/a", "https://repo.maven.apache.org/maven2/org/openjfx/%2e%2e/a", "file:///etc/passwd")) {
            Assertions.throwsType(IOException.class, () -> RuntimeDownloads.requireAllowed(URI.create(uri)), "publisher boundary");
        }
    }
    private void installer() throws Exception {
        try (InstallFixture f = new InstallFixture()) {
            var result = f.run();
            Assertions.equals("25.0.4-b508.27", result.jbrVersion(), "build recorded");
            Assertions.equals(1, f.executions.get(), "only verified launcher executed once");
            Assertions.fileExists(f.root.resolve("stage/jbr/bin/java.exe"), "runtime extracted");
            for (String module : RuntimeCatalog.FX_MODULES) Assertions.fileExists(f.root.resolve("stage/javafx/" + RuntimeCatalog.moduleName(module) + ".jar"), "module retained");
        }
    }
    private void checksum() throws Exception {
        try (InstallFixture f = new InstallFixture()) {
            f.downloads.badChecksum = true;
            Assertions.throwsType(IOException.class, f::run, "checksum refusal");
            Assertions.equals(0, f.executions.get(), "unverified runtime never executed");
        }
    }
    private void missingNative() throws Exception {
        try (InstallFixture f = new InstallFixture()) {
            f.downloads.modules.put("graphics", zip(Map.of("module-info.class", JavafxModuleFixture.descriptor("graphics", "25.0.4"))));
            Assertions.throwsType(IOException.class, f::run, "missing native binary");
            Assertions.equals(0, f.executions.get(), "failure before process");
        }
    }
    private void badModules() throws Exception {
        try (InstallFixture f = new InstallFixture()) { f.output = "javafx.base@21\n"; Assertions.throwsType(IOException.class, f::run, "wrong FX family"); }
    }
    private void badPlatform() throws Exception {
        try (InstallFixture f = new InstallFixture()) {
            f.downloads.archive = zip(Map.of("jbr/bin/java.exe", new byte[100], "jbr/release", bytes("JAVA_VERSION=\"25.0.4\"\nIMPLEMENTOR=\"JetBrains\"")));
            Assertions.throwsType(IOException.class, f::run, "wrong executable type"); Assertions.equals(0, f.executions.get(), "no execution");
        }
    }
    private void runtimeFailure() throws Exception {
        try (InstallFixture f = new InstallFixture()) {
            f.exit = 2; f.run();
            Assertions.contains(Files.readString(f.root.resolve("stage/download-provenance.txt")),
                    "Runtime compatibility verification SKIPPED", "failed probe remains an honest warning");
        }
    }
    private void networkFailure() throws Exception {
        try (InstallFixture f = new InstallFixture()) { f.downloads.fail = true; Assertions.throwsType(IOException.class, f::run, "network failure"); Assertions.equals(0, f.executions.get(), "no execution"); }
    }
    private void exactOptions(String input) throws Exception {
        byte[] original = bytes(input); byte[] installed = VmOptionsPatch.apply(original, Path.of("C:/runtime/javafx"));
        same(original, VmOptionsPatch.remove(original, installed, installed), "round trip exact bytes");
        Assertions.contains(VmOptionsPatch.decode(installed), "--module-path=\"", "quoted path");
        Assertions.contains(VmOptionsPatch.decode(installed), "-Djavafx.cachedir=", "native cache belongs to managed installation");
        Assertions.notContains(VmOptionsPatch.decode(installed), "headless=false", "no undocumented override");
    }
    private void externalOptions() throws Exception {
        byte[] original = bytes("-Xmx768m\n--module-path=C:/manual\n--add-modules=javafx.web\n");
        byte[] installed = VmOptionsPatch.apply(original, Path.of("C:/runtime/javafx"));
        byte[] current = bytes(VmOptionsPatch.decode(installed).replace("-Xmx768m", "-Xmx2048m") + "-Duser.option=value\n");
        String result = VmOptionsPatch.decode(VmOptionsPatch.remove(original, installed, current));
        Assertions.contains(result, "-Xmx2048m", "external memory setting retained");
        Assertions.contains(result, "--module-path=C:/manual", "manual module path restored");
        Assertions.contains(result, "-Duser.option=value", "external option retained");
        Assertions.notContains(result, VmOptionsPatch.BEGIN, "owned marker gone");
    }
    private void tamperedOptions() throws Exception {
        byte[] original = bytes("-Xmx768m\n"); byte[] installed = VmOptionsPatch.apply(original, Path.of("C:/runtime/javafx"));
        Assertions.throwsType(IOException.class, () -> VmOptionsPatch.remove(original, installed, bytes(VmOptionsPatch.decode(installed).replace("javafx.controls", "wrong.controls"))), "tampered block not removed");
    }
    private void conflictingOptions() throws Exception {
        byte[] original = bytes("-Xmx768m\n"); byte[] installed = VmOptionsPatch.apply(original, Path.of("C:/runtime/javafx"));
        Assertions.throwsType(IOException.class, () -> VmOptionsPatch.remove(original, installed, bytes(VmOptionsPatch.decode(installed) + "--module-path=C:/external\n")), "new module flags require review");
    }
    private void invalidOptions() {
        Assertions.throwsType(IOException.class, () -> VmOptionsPatch.decode(new byte[] {(byte)0xff}), "invalid encoding");
        Assertions.throwsType(IOException.class, () -> VmOptionsPatch.decode(new byte[1024 * 1024 + 1]), "bounded input");
    }
    private void codec() {
        Profile p = Profile.builder().profileOnlyConfiguration(true).managedHeadlessRuntime(true).build();
        var codec = new ProfileCodec();
        Assertions.equals(p, codec.decode(codec.encode(p)), "flag roundtrip");
        Assertions.notEquals(p, p.toBuilder().managedHeadlessRuntime(false).build(), "flag participates in identity checks");
        Assertions.contains(codec.encode(p), "formatVersion=7", "format change");
    }
    private void legacyCodec() {
        var codec = new ProfileCodec(); Profile p = Profile.builder().profileOnlyConfiguration(true).build();
        String text = codec.encode(p).replace("formatVersion=7", "formatVersion=6").replace("managedHeadlessRuntime=false\n", "");
        Assertions.equals(p, codec.decode(text), "six remains compatible");
        Assertions.isFalse(codec.decode(text).managedHeadlessRuntime(), "no surprise runtime download");
    }
    private void copy() {
        Profile source = Profile.builder().profileOnlyConfiguration(true).managedHeadlessRuntime(true).enabled(true).autoStart(true)
                .credentialMode(io.github.ibcmanager.model.CredentialMode.ENCRYPTED).setting("SecondFactorDevice", "IB Key")
                .ibcJavaPath(Path.of("C:/owned/jbr/bin")).build();
        Profile copy = ProfileCopyService.copy(source, List.of(source));
        Assertions.notEquals(source.id(), copy.id(), "independent identity");
        Assertions.isFalse(copy.enabled(), "copy cannot run until reviewed"); Assertions.isFalse(copy.autoStart(), "no automatic second login");
        Assertions.isFalse(copy.managedHeadlessRuntime(), "no unregistered shared ownership");
        Assertions.equals(io.github.ibcmanager.model.CredentialMode.MANUAL, copy.credentialMode(), "no duplicate credential association");
        Assertions.equals(Path.of(""), copy.ibcJavaPath(), "cannot borrow an owned runtime");
        Assertions.equals(source.settings(), copy.settings(), "configuration is copied");
        Assertions.isTrue(source.managedHeadlessRuntime(), "source not changed");
    }
    private void copyName() {
        Profile source = Profile.builder().name("Test").ibcJavaPath(Path.of("C:/manual/bin")).build();
        Profile first = ProfileCopyService.copy(source, List.of(source));
        Profile second = ProfileCopyService.copy(source, List.of(source, first));
        Assertions.notEquals(first.name(), second.name(), "unique name"); Assertions.notEquals(first.apiPort(), second.apiPort(), "unused port");
        Assertions.equals(source.ibcJavaPath(), second.ibcJavaPath(), "manual override retained");
    }
    private void ordinary() throws Exception {
        try (Fixture f = new Fixture()) {
            f.service.save(f.profile, f.profile, f.writer(), p -> { throw new AssertionError("no guard needed"); }, s -> { }, () -> false);
            Assertions.equals(0, f.downloads.get(), "no downloads for ordinary save");
        }
    }
    private void roundTrip() throws Exception {
        try (Fixture f = new Fixture()) {
            Profile enabled = f.enable(); ManagedRuntimeService.checkBeforeStart(enabled, f.paths);
            Assertions.equals(f.dir.resolve("payload/jbr/bin"), enabled.ibcJavaPath(), "managed override selected");
            Profile restored = f.disable(enabled);
            Assertions.equals(f.profile.ibcJavaPath(), restored.ibcJavaPath(), "manual override restored");
            same(f.original, Files.readAllBytes(f.options), "original vmoptions restored exactly");
            Assertions.isFalse(Files.exists(f.dir), "all owned downloads/backups removed");
            Assertions.fileExists(f.manual.resolve("keep.txt"), "manual runtime not deleted");
        }
    }
    private void sharing() throws Exception {
        try (Fixture f = new Fixture()) {
            Profile first = f.enable();
            Profile second = f.profile.toBuilder().id(UUID.randomUUID()).name("Second").build(); f.repo.save(second);
            second = f.service.save(second, second.toBuilder().managedHeadlessRuntime(true).build(), f.writer(), p -> { }, s -> { }, () -> false);
            Assertions.equals(1, f.downloads.get(), "existing owned install reused");
            f.disable(first); Assertions.directoryExists(f.dir, "second owner still needs runtime");
            ManagedRuntimeService.checkBeforeStart(second, f.paths);
            f.disable(second); Assertions.isFalse(Files.exists(f.dir), "last owner removes install");
            same(f.original, Files.readAllBytes(f.options), "restored after last owner");
        }
    }
    private void editedUninstall() throws Exception {
        try (Fixture f = new Fixture()) {
            Profile enabled = f.enable(); Files.writeString(f.options, Files.readString(f.options).replace("-Xmx768m", "-Xmx2g") + "-Dcustom=present\r\n");
            f.disable(enabled); Assertions.contains(Files.readString(f.options), "-Xmx2g", "new memory size preserved"); Assertions.contains(Files.readString(f.options), "-Dcustom=present", "custom property preserved");
        }
    }
    private void downloadFailure() throws Exception {
        try (Fixture f = new Fixture()) { f.failDownload = true; Assertions.throwsType(IOException.class, f::enable, "failure"); f.assertOriginal(); }
    }
    private void cancel() throws Exception {
        try (Fixture f = new Fixture()) {
            Assertions.throwsType(IOException.class, () -> f.service.save(f.profile, f.profile.toBuilder().managedHeadlessRuntime(true).build(), f.writer(), p -> { }, s -> { }, () -> true), "cancelled");
            f.assertOriginal();
        }
    }
    private void saveFailure() throws Exception {
        try (Fixture f = new Fixture()) {
            Assertions.throwsType(IOException.class, () -> f.service.save(f.profile, f.profile.toBuilder().managedHeadlessRuntime(true).build(), (p,c) -> { throw new IOException("save fixture"); }, p -> { }, s -> { }, () -> false), "save rollback");
            f.assertOriginal();
        }
    }
    private void removeFailure() throws Exception {
        try (Fixture f = new Fixture()) {
            Profile enabled = f.enable(); byte[] current = Files.readAllBytes(f.options);
            Assertions.throwsType(IOException.class, () -> f.service.save(enabled, enabled.toBuilder().managedHeadlessRuntime(false).build(), (p,c) -> { throw new IOException("save fixture"); }, p -> { }, s -> { }, () -> false), "removal rollback");
            same(current, Files.readAllBytes(f.options), "managed options restored"); ManagedRuntimeService.checkBeforeStart(enabled, f.paths);
        }
    }
    private void active() throws Exception {
        try (Fixture f = new Fixture()) {
            Assertions.throwsType(IOException.class, () -> f.service.save(f.profile, f.profile.toBuilder().managedHeadlessRuntime(true).build(), f.writer(), p -> { throw new IOException("active"); }, s -> { }, () -> false), "active installation rejected");
            Assertions.equals(0, f.downloads.get(), "no download"); f.assertOriginal();
        }
    }
    private void lateActive() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger count = new AtomicInteger();
            Assertions.throwsType(IOException.class, () -> f.service.save(f.profile, f.profile.toBuilder().managedHeadlessRuntime(true).build(), f.writer(), p -> { if (count.incrementAndGet() >= 3) throw new IOException("late active"); }, s -> { }, () -> false), "late active guard"); f.assertOriginal();
        }
    }
    private void editDuringDownload() throws Exception {
        try (Fixture f = new Fixture()) {
            f.editOnDownload = true; Assertions.throwsType(IOException.class, f::enable, "concurrent edit refused");
            Assertions.equals("-Xmx4096m\n", Files.readString(f.options), "concurrent edit not lost"); Assertions.isFalse(Files.exists(f.dir), "no activated runtime");
        }
    }
    private void tamperedPayload() throws Exception {
        try (Fixture f = new Fixture()) {
            Profile enabled = f.enable(); Files.writeString(f.dir.resolve("payload/jbr/bin/java.exe"), "changed");
            Assertions.throwsType(IOException.class, () -> ManagedRuntimeService.checkBeforeStart(enabled, f.paths), "changed launcher rejected");
            Assertions.throwsType(IOException.class, () -> f.disable(enabled), "changed files preserved for review");
            Assertions.equals("changed", Files.readString(enabled.ibcJavaPath().resolve("java.exe")), "no deletion");
        }
    }
    private void extraFile() throws Exception {
        try (Fixture f = new Fixture()) {
            Profile enabled = f.enable(); Files.writeString(f.dir.resolve("user-file.txt"), "preserve"); byte[] current = Files.readAllBytes(f.options);
            Assertions.throwsType(IOException.class, () -> f.disable(enabled), "unowned file"); same(current, Files.readAllBytes(f.options), "no partial removal"); Assertions.fileExists(f.dir.resolve("user-file.txt"), "file preserved");
        }
    }
    private void borrowed() throws Exception {
        try (Fixture f = new Fixture()) {
            Profile enabled = f.enable(); f.repo.save(f.profile.toBuilder().id(UUID.randomUUID()).ibcJavaPath(enabled.ibcJavaPath()).build());
            Assertions.throwsType(IOException.class, () -> f.disable(enabled), "unregistered borrower prevents deletion");
        }
    }
    private void changedTarget() throws Exception {
        try (Fixture f = new Fixture()) {
            Profile enabled = f.enable();
            Assertions.throwsType(IOException.class, () -> f.service.save(enabled, enabled.toBuilder().targetType(TargetType.TWS).build(), f.writer(), p -> { }, s -> { }, () -> false), "disable before changing installation");
        }
    }
    private void foreignDirectory() throws Exception {
        try (Fixture f = new Fixture()) {
            Files.createDirectory(f.dir); Files.writeString(f.dir.resolve("manual.txt"), "preserve");
            Assertions.throwsType(IOException.class, f::enable, "foreign directory not overwritten"); Assertions.fileExists(f.dir.resolve("manual.txt"), "manual content retained");
        }
    }
    private void otherManager() throws Exception {
        try (Fixture f = new Fixture()) {
            Profile first = f.enable();
            AppPaths paths = new AppPaths(f.root.resolve("other-data")); ProfileRepository repository = new ProfileRepository(paths);
            Profile second = f.profile.toBuilder().id(UUID.randomUUID()).build(); repository.save(second);
            ManagedRuntimeService service = new ManagedRuntimeService(paths, repository, (s,p,c) -> { throw new AssertionError("reuse expected"); });
            second = service.save(second, second.toBuilder().managedHeadlessRuntime(true).build(), (p,c) -> repository.save(c), p -> { }, s -> { }, () -> false);
            f.disable(first); Assertions.directoryExists(f.dir, "foreign owner retained");
            service.save(second, second.toBuilder().managedHeadlessRuntime(false).build(), (p,c) -> repository.save(c), p -> { }, s -> { }, () -> false);
            Assertions.isFalse(Files.exists(f.dir), "last foreign owner releases");
        }
    }
    private void cache() throws Exception {
        try (Fixture f = new Fixture()) {
            Profile enabled = f.enable(); Files.write(f.dir.resolve("native-cache/glass.dll"), pe()); Files.write(f.dir.resolve("native-cache/.lock"), new byte[0]);
            f.disable(enabled); Assertions.isFalse(Files.exists(f.dir), "owned verified cache removed");
        }
    }
    private void badCache() throws Exception {
        try (Fixture f = new Fixture()) {
            Profile enabled = f.enable(); Files.writeString(f.dir.resolve("native-cache/keep.txt"), "unknown");
            Assertions.throwsType(IOException.class, () -> f.disable(enabled), "unknown cache preserved"); Assertions.fileExists(f.dir.resolve("native-cache/keep.txt"), "not deleted");
        }
    }
    private void tws() throws Exception {
        try (Fixture f = new Fixture(TargetType.TWS)) {
            Profile enabled = f.enable(); Assertions.equals("tws.vmoptions", f.options.getFileName().toString(), "TWS path");
            Assertions.contains(Files.readString(f.options), VmOptionsPatch.BEGIN, "TWS block installed"); f.disable(enabled); f.assertOriginal();
        }
    }
    private void oldApplication() throws Exception {
        try (Fixture f = new Fixture()) {
            Profile old = f.profile.toBuilder().twsMajorVersion("1045").build();
            Assertions.throwsType(IOException.class, () -> f.service.save(old, old.toBuilder().managedHeadlessRuntime(true).build(), f.writer(), p -> { }, s -> { }, () -> false), "not for older runtime"); Assertions.equals(0, f.downloads.get(), "no download");
        }
    }
    private void missingOwnership() throws Exception {
        try (Fixture f = new Fixture()) {
            Profile enabled = f.enable(); Profile other = enabled.toBuilder().id(UUID.randomUUID()).build();
            Assertions.throwsType(IOException.class, () -> ManagedRuntimeService.checkBeforeStart(other, f.paths), "unregistered managed profile rejected");
        }
    }
    private void ordinaryLaunch() throws Exception {
        try (Fixture f = new Fixture()) {
            ManagedRuntimeService.checkBeforeStart(f.profile, f.paths);
            Assertions.isFalse(Files.exists(f.dir), "ordinary startup makes no runtime directory");
            Assertions.isFalse(Files.exists(f.options.resolveSibling("." + f.options.getFileName() + ".ibc-runtime.lock")), "no write into normal installation");
        }
    }
    private void unknownLayout() { Assertions.throwsType(IllegalArgumentException.class, () -> ManagedRuntimeService.directory(Path.of("C:/manual/other.vmoptions")), "filename constrained"); }
    private void interrupted(boolean disable, boolean committed) throws Exception {
        try (Fixture f = new Fixture()) {
            Profile before = disable ? f.enable() : f.profile;
            Profile desired = before.toBuilder().managedHeadlessRuntime(!disable).build();
            Assertions.throwsType(SimulatedCrash.class, () -> f.service.save(before, desired, (p,c) -> { if (committed) f.repo.save(c); throw new SimulatedCrash(); }, p -> { }, s -> { }, () -> false), "interrupt between options and profile completion");
            Assertions.fileExists(f.dir.resolve("pending.properties"), "journal durable");
            f.service.recoverPending();
            Profile current = f.repo.loadAll().profiles().get(0);
            boolean enabled = disable != committed;
            Assertions.equals(enabled, current.managedHeadlessRuntime(), "recovery follows saved profile");
            if (enabled) { ManagedRuntimeService.checkBeforeStart(current, f.paths); Assertions.isFalse(Files.exists(f.dir.resolve("pending.properties")), "committed journal cleared"); }
            else { Assertions.isFalse(Files.exists(f.dir), "unused owned payload removed"); same(f.original, Files.readAllBytes(f.options), "unmanaged options restored"); }
        }
    }
    private void badZip(String entry) throws Exception {
        Path root = TestSupport.tempDirectory("bad-runtime-zip");
        try {
            Path file = root.resolve("a.zip"); Files.write(file, zip(Map.of(entry, bytes("data")))); Path dest = Files.createDirectory(root.resolve("out"));
            Assertions.throwsType(IOException.class, () -> RuntimeBundleInstaller.extract(file, dest), "unsafe path rejected");
        } finally { TestSupport.deleteTree(root); }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void same(byte[] a, byte[] b, String message) { Assertions.isTrue(Arrays.equals(a, b), message); }
    private static byte[] pe() {
        byte[] data = new byte[80]; ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        data[0] = 'M'; data[1] = 'Z'; b.putInt(60, 64); b.putInt(64, 0x4550); b.putShort(68, (short)0x8664); return data;
    }
    private static byte[] zip(Map<String, byte[]> contents) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : contents.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        return bytes.toByteArray();
    }
    private static String hash(byte[] bytes, String algorithm) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes)); }
    private static String release(String version, String build, boolean draft, boolean prerelease) {
        String url = "https://cache-redirector.jetbrains.com/intellij-jbr/jbr-" + version + "-windows-x64-b" + build + ".zip";
        return "{\"tag_name\":\"jbr-release-" + version + "b" + build + "\",\"draft\":" + draft + ",\"prerelease\":" + prerelease + ",\"body\":\"[zip](" + url + ") [sha512](" + url + ".checksum)\"}";
    }
    @SuppressWarnings("serial")
    private static final class SimulatedCrash extends Error { }
    private static final class FakeDownloads implements RuntimeDownloads {
        String releases = "[" + release("25.0.4", "508.27", false, false) + "]";
        boolean fail; boolean badChecksum; boolean archiveUnavailable;
        final List<URI> files = new ArrayList<>();
        byte[] archive;
        final Map<String, byte[]> modules = new LinkedHashMap<>();
        FakeDownloads() {
            try {
                archive = zip(Map.of("jbr/bin/java.exe", pe(), "jbr/release", bytes("JAVA_VERSION=\"25.0.4\"\nIMPLEMENTOR=\"JetBrains s.r.o.\"\n")));
                for (String module : RuntimeCatalog.FX_MODULES) {
                    Map<String, byte[]> entries = new LinkedHashMap<>(); entries.put("module-info.class", JavafxModuleFixture.descriptor(module, "25.0.4"));
                    String lib = switch (module) { case "graphics" -> "glass.dll"; case "media" -> "jfxmedia.dll"; case "web" -> "jfxwebkit.dll"; default -> null; };
                    if (lib != null) entries.put(lib, pe()); modules.put(module, zip(entries));
                }
            } catch (IOException failure) { throw new IllegalStateException(failure); }
        }
        private byte[] content(URI uri) throws IOException {
            if (fail) throw new IOException("Publisher unavailable (fixture)");
            String text = uri.toString();
            if (text.endsWith(".zip")) return archive;
            for (String module : RuntimeCatalog.FX_MODULES) if (text.contains("/" + RuntimeCatalog.artifactId(module) + "/")) return modules.get(module);
            throw new IOException("Unexpected fixture request: " + text);
        }
        @Override public String text(URI uri, int max) throws IOException {
            if (fail) throw new IOException("Publisher unavailable (fixture)");
            if (uri.getHost().equals("api.github.com")) return releases;
            if (uri.getPath().endsWith("maven-metadata.xml")) return "<versions><version>26.0.2</version><version>25.0.3</version><version>25.0.4</version><version>25.0.5-ea</version></versions>";
            String suffix = uri.toString().endsWith(".checksum") ? ".checksum" : ".sha256";
            try { return badChecksum ? "0".repeat(suffix.equals(".checksum") ? 128 : 64) : hash(content(URI.create(uri.toString().substring(0, uri.toString().length() - suffix.length()))), suffix.equals(".checksum") ? "SHA-512" : "SHA-256"); }
            catch (Exception failure) { throw new IOException(failure); }
        }
        @Override public void file(URI uri, Path target, long max, BooleanSupplier cancelled) throws IOException {
            if (cancelled.getAsBoolean()) throw new IOException("Cancelled fixture download");
            files.add(uri);
            if (archiveUnavailable && uri.equals(RuntimeCatalog.JBR_ZIP)) throw new IOException("Pinned archive unavailable (fixture)");
            Files.write(target, content(uri));
        }
    }
    private static final class InstallFixture implements AutoCloseable {
        final Path root = TestSupport.tempDirectory("bundle"); final FakeDownloads downloads = new FakeDownloads();
        final AtomicInteger executions = new AtomicInteger(); int exit; String output = RuntimeCatalog.FX_MODULES.stream().map(m -> RuntimeCatalog.moduleName(m) + "@25.0.4\n").collect(java.util.stream.Collectors.joining());
        InstallFixture() throws IOException { }
        RuntimeBundleInstaller.Installed run() throws IOException {
            Path stage = Files.createDirectory(root.resolve("stage"));
            return new RuntimeBundleInstaller(downloads, (command, input, timeout) -> {
                executions.incrementAndGet(); Assertions.contains(command.get(0), "jbr", "only downloaded java selected");
                Assertions.equals("--list-modules", command.get(command.size()-1), "no Gateway or account started during verification");
                return new CommandResult(exit, output, "", false);
            }).install(stage, s -> { }, () -> false);
        }
        @Override public void close() throws IOException { TestSupport.deleteTree(root); }
    }
    private static final class Fixture implements AutoCloseable {
        final Path root = TestSupport.tempDirectory("managed-runtime"); final AppPaths paths = new AppPaths(root.resolve("data"));
        final ProfileRepository repo = new ProfileRepository(paths); final AtomicInteger downloads = new AtomicInteger();
        final Path manual = Files.createDirectories(root.resolve("manual")); final Profile profile; final Path options; final Path dir;
        final byte[] original = bytes("# original\r\n-Xmx768m\r\n--module-path=C:/manual/lib\r\n--add-modules=javafx.controls\r\n");
        final ManagedRuntimeService service; boolean failDownload; boolean editOnDownload;
        Fixture() throws IOException { this(TargetType.GATEWAY); }
        Fixture(TargetType target) throws IOException {
            Profile base = TestSupport.validProfile(root.resolve("application"));
            if (target == TargetType.TWS) TestSupport.createOfflineTwsInstallation(base.twsPath(), "1050");
            else TestSupport.createOfflineGatewayInstallation(base.twsPath(), "1050");
            profile = base.toBuilder().targetType(target).twsMajorVersion("1050").profileOnlyConfiguration(true).baseConfigPath(Path.of(""))
                    .ibcJavaPath(manual).build();
            repo.save(profile); Files.writeString(manual.resolve("keep.txt"), "manual files are not owned");
            options = new io.github.ibcmanager.runtime.OfflineApplicationLayoutResolver().resolve(profile).vmOptionsPath();
            Files.write(options, original); dir = ManagedRuntimeService.directory(options);
            service = new ManagedRuntimeService(paths, repo, (stage, progress, cancelled) -> {
                downloads.incrementAndGet();
                if (failDownload) throw new IOException("download fixture");
                Files.createDirectories(stage.resolve("jbr/bin")); Files.write(stage.resolve("jbr/bin/java.exe"), pe());
                Files.createDirectory(stage.resolve("javafx"));
                FakeDownloads provider = new FakeDownloads();
                for (String module : RuntimeCatalog.FX_MODULES) Files.write(stage.resolve("javafx/" + RuntimeCatalog.moduleName(module) + ".jar"), provider.modules.get(module));
                if (editOnDownload) Files.writeString(options, "-Xmx4096m\n");
                return new RuntimeBundleInstaller.Installed("25.0.4-b508.27", "25.0.4");
            });
        }
        ManagedRuntimeService.ProfileWriter writer() { return (previous, desired) -> repo.save(desired); }
        Profile enable() throws Exception { return service.save(profile, profile.toBuilder().managedHeadlessRuntime(true).build(), writer(), p -> { }, s -> { }, () -> false); }
        Profile disable(Profile enabled) throws Exception { return service.save(enabled, enabled.toBuilder().managedHeadlessRuntime(false).build(), writer(), p -> { }, s -> { }, () -> false); }
        void assertOriginal() throws IOException {
            same(original, Files.readAllBytes(options), "original options untouched"); Assertions.isFalse(Files.exists(dir), "no activated managed directory");
            Assertions.equals(profile, repo.loadAll().profiles().get(0), "profile unchanged"); Assertions.fileExists(manual.resolve("keep.txt"), "manual file preserved");
        }
        @Override public void close() throws IOException { TestSupport.deleteTree(root); }
    }
}
