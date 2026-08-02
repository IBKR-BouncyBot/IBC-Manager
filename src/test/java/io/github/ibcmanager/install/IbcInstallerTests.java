package io.github.ibcmanager.install;

import io.github.ibcmanager.app.Version;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class IbcInstallerTests implements TestSuite {
    @Override public String name() { return "Official IBC download and transactional ZIP installation"; }

    @Override
    public List<NamedTest> tests() {
        return List.of(
                new NamedTest("release coordinates target the tested official Windows asset", this::releaseCoordinates),
                new NamedTest("official GitHub archive checksum is pinned and enforced", this::officialChecksum),
                new NamedTest("valid official-style archive installs transactionally", this::validInstall),
                new NamedTest("one optional top-level archive directory is supported", this::topLevelDirectory),
                new NamedTest("empty destination directory can be activated", this::emptyDestination),
                new NamedTest("existing valid baseline installation is reused without a download", this::existingInstall),
                new NamedTest("non-empty invalid destination is never overwritten", this::nonEmptyDestination),
                new NamedTest("download failure leaves no destination or staging tree", this::downloadFailure),
                new NamedTest("cancelled installation leaves no destination or staging tree", this::cancelledInstall),
                new NamedTest("destination changes during download are preserved and rejected", this::destinationRace),
                new NamedTest("GitHub release and redirect URI policy is restrictive", this::downloadUriPolicy),
                new NamedTest("ZIP traversal and Windows-ambiguous paths are rejected", this::pathTraversal),
                new NamedTest("case-insensitive and separator-normalized duplicate ZIP paths are rejected", this::duplicatePaths),
                new NamedTest("unexpected files outside a prefixed installation are rejected", this::unexpectedSibling),
                new NamedTest("required IBC files and version are validated", this::requiredFilesAndVersion),
                new NamedTest("IBC.jar and StartIBC.bat contents are validated", this::programContents),
                new NamedTest("archive entry-count limit is enforced", this::entryCountLimit),
                new NamedTest("per-entry and total expansion limits are enforced", this::expansionLimits),
                new NamedTest("install result validates its security metadata", this::resultValidation));
    }

    private void releaseCoordinates() {
        Assertions.equals("IBCWin-" + Version.IBC_BASELINE + ".zip",
                IbcInstallerService.releaseAssetName(), "Windows asset name mismatch");
        URI uri = IbcInstallerService.releaseAssetUri();
        Assertions.equals("https", uri.getScheme(), "IBC release must use HTTPS");
        Assertions.equals("github.com", uri.getHost(), "IBC release must originate at GitHub");
        Assertions.equals("/IbcAlpha/IBC/releases/download/" + Version.IBC_BASELINE + "/"
                        + IbcInstallerService.releaseAssetName(),
                uri.getPath(), "IBC release URL mismatch");
        Assertions.equals("C:\\IBC", IbcInstallerService.DEFAULT_WINDOWS_DIRECTORY.toString(),
                "default Windows installation directory changed");
        Assertions.equals("03a467c4636cb36cd5f6b5c4406812ee598319231bbed6b4d01a6953bf30e7c2",
                IbcInstallerService.OFFICIAL_WINDOWS_ARCHIVE_SHA256,
                "official Windows release digest changed without review");
    }

    private void officialChecksum() throws Exception {
        Path root = TestSupport.tempDirectory("ibc-install-checksum");
        try {
            byte[] archive = validArchive("");
            IbcInstallerService service = new IbcInstallerService(new FakeDownloader(archive),
                    4096, 256L * 1024L * 1024L, 128L * 1024L * 1024L, "0".repeat(64));
            Path destination = root.resolve("IBC");
            IbcInstallationException error = Assertions.throwsType(IbcInstallationException.class,
                    () -> service.install(destination, InstallProgress.none()),
                    "an archive with the wrong published checksum must be rejected");
            Assertions.contains(error.getMessage(), "published for the supported official GitHub release",
                    "checksum mismatch message is unclear");
            Assertions.isFalse(Files.exists(destination),
                    "checksum mismatch must not create the destination directory");
            assertNoStaging(root, destination.getFileName().toString());
            Assertions.throwsType(IllegalArgumentException.class,
                    () -> new IbcInstallerService(new FakeDownloader(archive), 10, 1024, 1024, "bad"),
                    "invalid pinned digests must be rejected at construction time");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void validInstall() throws Exception {
        Path root = TestSupport.tempDirectory("ibc-install-valid");
        try {
            byte[] archive = validArchive("");
            FakeDownloader downloader = new FakeDownloader(archive);
            IbcInstallerService service = service(downloader);
            Path destination = root.resolve("IBC");
            List<String> progress = new ArrayList<>();
            IbcInstallResult result = service.install(destination,
                    (message, completed, total) -> progress.add(message));
            Assertions.isTrue(result.downloaded(), "new installation must report a download");
            Assertions.equals(destination.toAbsolutePath(), result.installationDirectory(),
                    "installation directory mismatch");
            Assertions.equals(Version.IBC_BASELINE, result.version(), "installed version mismatch");
            Assertions.equals(IbcInstallerService.releaseAssetName(), result.assetName(), "asset name mismatch");
            Assertions.equals(sha256(archive), result.sha256(), "archive digest mismatch");
            Assertions.equals(1, downloader.calls.get(), "archive should be downloaded exactly once");
            Assertions.fileExists(destination.resolve("IBC.jar"), "IBC.jar missing after install");
            Assertions.fileExists(destination.resolve("scripts").resolve("StartIBC.bat"),
                    "StartIBC.bat missing after install");
            Assertions.isTrue(IbcInstallerService.isValidInstallation(destination),
                    "activated installation must pass validation");
            Assertions.isTrue(progress.stream().anyMatch(value -> value.contains("Downloading")),
                    "download progress was not reported");
            Assertions.isTrue(progress.stream().anyMatch(value -> value.contains("completed")),
                    "completion progress was not reported");
            assertNoStaging(root, destination.getFileName().toString());
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void topLevelDirectory() throws Exception {
        Path root = TestSupport.tempDirectory("ibc-install-prefix");
        try {
            Path destination = root.resolve("IBC");
            IbcInstallerService service = service(new FakeDownloader(validArchive("IBCWin-3.24.1/")));
            IbcInstallResult result = service.install(destination, InstallProgress.none());
            Assertions.equals(Version.IBC_BASELINE, result.version(), "prefixed archive version mismatch");
            Assertions.fileExists(destination.resolve("config.ini"), "prefixed archive was not flattened");
            Assertions.isFalse(Files.exists(destination.resolve("IBCWin-3.24.1")),
                    "top-level archive folder must not be retained inside the destination");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void emptyDestination() throws Exception {
        Path root = TestSupport.tempDirectory("ibc-install-empty");
        try {
            Path destination = root.resolve("IBC");
            Files.createDirectory(destination);
            service(new FakeDownloader(validArchive(""))).install(destination, InstallProgress.none());
            Assertions.isTrue(IbcInstallerService.isValidInstallation(destination),
                    "empty destination must accept validated installation");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void existingInstall() throws Exception {
        Path root = TestSupport.tempDirectory("ibc-install-existing");
        try {
            Path destination = root.resolve("IBC");
            writeValidInstallation(destination);
            FakeDownloader downloader = new FakeDownloader(validArchive(""));
            IbcInstallResult result = service(downloader).install(destination, InstallProgress.none());
            Assertions.isFalse(result.downloaded(), "existing installation must not report a download");
            Assertions.equals("existing installation", result.assetName(), "existing source label mismatch");
            Assertions.equals(0, downloader.calls.get(), "existing valid IBC must not access the network");
            Assertions.equals(sha256(Files.readAllBytes(destination.resolve("IBC.jar"))), result.sha256(),
                    "existing result must identify the installed JAR");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void nonEmptyDestination() throws Exception {
        Path root = TestSupport.tempDirectory("ibc-install-nonempty");
        try {
            Path destination = root.resolve("IBC");
            Files.createDirectories(destination);
            Path marker = destination.resolve("do-not-delete.txt");
            Files.writeString(marker, "preserve", StandardCharsets.UTF_8);
            FakeDownloader downloader = new FakeDownloader(validArchive(""));
            IbcInstallationException error = Assertions.throwsType(IbcInstallationException.class,
                    () -> service(downloader).install(destination, InstallProgress.none()),
                    "non-empty invalid destination must be rejected");
            Assertions.contains(error.getMessage(), "will not be overwritten", "overwrite refusal is unclear");
            Assertions.equals("preserve", Files.readString(marker), "existing destination content was changed");
            Assertions.equals(0, downloader.calls.get(), "destination must be rejected before download");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void downloadFailure() throws Exception {
        Path root = TestSupport.tempDirectory("ibc-install-download-fail");
        try {
            Path destination = root.resolve("IBC");
            IbcReleaseDownloader failing = (source, target, progress) -> {
                Files.writeString(target, "partial", StandardCharsets.UTF_8);
                throw new IOException("simulated network failure");
            };
            IbcInstallationException error = Assertions.throwsType(IbcInstallationException.class,
                    () -> service(failing).install(destination, InstallProgress.none()),
                    "download failure must be surfaced");
            Assertions.contains(error.getMessage(), "simulated network failure", "download cause was lost");
            Assertions.isFalse(Files.exists(destination), "failed download created an installation directory");
            assertNoStaging(root, destination.getFileName().toString());
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void cancelledInstall() throws Exception {
        Path root = TestSupport.tempDirectory("ibc-install-cancelled");
        try {
            Path destination = root.resolve("IBC");
            byte[] archive = validArchive("");
            String archiveSha256 = sha256(archive);
            IbcReleaseDownloader cancelled = (source, target, progress) -> {
                Files.write(target, archive);
                Thread.currentThread().interrupt();
                return new IbcReleaseDownloader.DownloadResult(source, archive.length, archiveSha256);
            };
            Assertions.throwsType(CancellationException.class,
                    () -> service(cancelled).install(destination, InstallProgress.none()),
                    "interrupted installation must be cancelled before extraction or activation");
            Assertions.isFalse(Files.exists(destination), "cancelled installation created a destination");
            assertNoStaging(root, destination.getFileName().toString());
        } finally {
            Thread.interrupted();
            TestSupport.deleteTree(root);
        }
    }

    private void destinationRace() throws Exception {
        Path root = TestSupport.tempDirectory("ibc-install-race");
        try {
            Path destination = root.resolve("IBC");
            byte[] archive = validArchive("");
            String archiveSha256 = sha256(archive);
            IbcReleaseDownloader racing = (source, target, progress) -> {
                Files.write(target, archive);
                Files.writeString(destination, "created during download", StandardCharsets.UTF_8);
                return new IbcReleaseDownloader.DownloadResult(source, archive.length, archiveSha256);
            };
            IbcInstallationException error = Assertions.throwsType(IbcInstallationException.class,
                    () -> service(racing).install(destination, InstallProgress.none()),
                    "destination race must be rejected");
            Assertions.contains(error.getMessage(), "changed while installation was in progress",
                    "destination-race error is unclear");
            Assertions.equals("created during download", Files.readString(destination),
                    "concurrently created destination file must not be deleted");
            assertNoStaging(root, destination.getFileName().toString());
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void downloadUriPolicy() throws Exception {
        HttpsIbcReleaseDownloader.validateUri(URI.create(
                "https://github.com/IbcAlpha/IBC/releases/download/3.24.1/IBCWin-3.24.1.zip"));
        HttpsIbcReleaseDownloader.validateUri(URI.create(
                "https://release-assets.githubusercontent.com/github-production-release-asset/example"));
        HttpsIbcReleaseDownloader.validateUri(URI.create(
                "https://objects.githubusercontent.com/github-production-release-asset/example"));

        for (String rejected : List.of(
                "http://github.com/IbcAlpha/IBC/releases/download/3.24.1/IBCWin-3.24.1.zip",
                "https://user@github.com/IbcAlpha/IBC/releases/download/3.24.1/IBCWin-3.24.1.zip",
                "https://github.com:444/IbcAlpha/IBC/releases/download/3.24.1/IBCWin-3.24.1.zip",
                "https://github.com.evil.example/asset.zip",
                "https://evilgithub.com/asset.zip",
                "https://example.com/asset.zip")) {
            IOException error = Assertions.throwsType(IOException.class,
                    () -> HttpsIbcReleaseDownloader.validateUri(URI.create(rejected)),
                    "untrusted release URI must be rejected: " + rejected);
            Assertions.isTrue(!error.getMessage().isBlank(), "URI rejection must explain the failure");
        }
    }

    private void pathTraversal() throws Exception {
        for (String malicious : List.of(
                "../escape.txt", "..\\escape.txt", "/absolute.txt", "C:\\absolute.txt",
                "folder//file.txt", "folder/./file.txt", "folder/../file.txt",
                "CON", "aux.txt", "trailing.", "trailing ", "bad?.txt")) {
            Path root = TestSupport.tempDirectory("ibc-install-traversal");
            try {
                List<ArchiveEntry> entries = validEntries("");
                entries.add(0, new ArchiveEntry(malicious, "bad".getBytes(StandardCharsets.UTF_8)));
                Path destination = root.resolve("IBC");
                IbcInstallationException error = Assertions.throwsType(IbcInstallationException.class,
                        () -> service(new FakeDownloader(zip(entries))).install(destination, InstallProgress.none()),
                        "malicious path must be rejected: " + malicious);
                Assertions.isTrue(error.getMessage().contains("path") || error.getMessage().contains("archive"),
                        "malicious path error should identify archive path validation");
                Assertions.isFalse(Files.exists(root.resolve("escape.txt")),
                        "traversal wrote outside staging: " + malicious);
                Assertions.isFalse(Files.exists(destination), "traversal activated a destination");
                assertNoStaging(root, destination.getFileName().toString());
            } finally {
                TestSupport.deleteTree(root);
            }
        }
    }

    private void duplicatePaths() throws Exception {
        Path root = TestSupport.tempDirectory("ibc-install-duplicates");
        try {
            List<ArchiveEntry> entries = validEntries("");
            entries.add(new ArchiveEntry("CONFIG.INI", "duplicate".getBytes(StandardCharsets.UTF_8)));
            IbcInstallationException error = Assertions.throwsType(IbcInstallationException.class,
                    () -> service(new FakeDownloader(zip(entries))).install(root.resolve("IBC"), InstallProgress.none()),
                    "case-insensitive duplicate must be rejected");
            Assertions.contains(error.getMessage(), "duplicate path", "duplicate error is unclear");

            List<ArchiveEntry> separatorEntries = validEntries("");
            separatorEntries.add(0, new ArchiveEntry("folder/file.txt", new byte[]{1}));
            separatorEntries.add(1, new ArchiveEntry("folder\\file.txt", new byte[]{2}));
            IbcInstallationException separatorError = Assertions.throwsType(IbcInstallationException.class,
                    () -> service(new FakeDownloader(zip(separatorEntries))).install(
                            root.resolve("IBC-separators"), InstallProgress.none()),
                    "separator-normalized duplicate must be rejected");
            Assertions.contains(separatorError.getMessage(), "duplicate path",
                    "separator-normalized duplicate error is unclear");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void unexpectedSibling() throws Exception {
        Path root = TestSupport.tempDirectory("ibc-install-sibling");
        try {
            List<ArchiveEntry> entries = validEntries("payload/");
            entries.add(new ArchiveEntry("outside.txt", "unexpected".getBytes(StandardCharsets.UTF_8)));
            IbcInstallationException error = Assertions.throwsType(IbcInstallationException.class,
                    () -> service(new FakeDownloader(zip(entries))).install(root.resolve("IBC"), InstallProgress.none()),
                    "unexpected sibling must be rejected");
            Assertions.contains(error.getMessage(), "unexpected files", "sibling error is unclear");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void requiredFilesAndVersion() throws Exception {
        for (String missing : List.of("IBC.jar", "version", "config.ini", "LICENSE.txt", "scripts/StartIBC.bat")) {
            Path root = TestSupport.tempDirectory("ibc-install-missing");
            try {
                List<ArchiveEntry> entries = validEntries("");
                entries.removeIf(entry -> entry.name().equals(missing));
                IbcInstallationException error = Assertions.throwsType(IbcInstallationException.class,
                        () -> service(new FakeDownloader(zip(entries))).install(root.resolve("IBC"), InstallProgress.none()),
                        "missing required file must fail: " + missing);
                Assertions.isTrue(error.getMessage().contains("missing")
                                || error.getMessage().contains("exactly one IBC.jar"),
                        "missing-file error is unclear for " + missing);
            } finally {
                TestSupport.deleteTree(root);
            }
        }

        Path root = TestSupport.tempDirectory("ibc-install-version");
        try {
            List<ArchiveEntry> entries = validEntries("");
            replace(entries, "version", "9.9.9".getBytes(StandardCharsets.UTF_8));
            IbcInstallationException error = Assertions.throwsType(IbcInstallationException.class,
                    () -> service(new FakeDownloader(zip(entries))).install(root.resolve("IBC"), InstallProgress.none()),
                    "wrong IBC version must fail");
            Assertions.contains(error.getMessage(), "Expected IBC " + Version.IBC_BASELINE,
                    "wrong-version error is unclear");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void programContents() throws Exception {
        Path invalidJarRoot = TestSupport.tempDirectory("ibc-install-jar");
        try {
            List<ArchiveEntry> entries = validEntries("");
            replace(entries, "IBC.jar", "not a jar".getBytes(StandardCharsets.UTF_8));
            Assertions.throwsType(IbcInstallationException.class,
                    () -> service(new FakeDownloader(zip(entries))).install(invalidJarRoot.resolve("IBC"),
                            InstallProgress.none()),
                    "invalid IBC.jar must fail");
        } finally {
            TestSupport.deleteTree(invalidJarRoot);
        }

        Path noClassesRoot = TestSupport.tempDirectory("ibc-install-no-class");
        try {
            List<ArchiveEntry> entries = validEntries("");
            replace(entries, "IBC.jar", jarWithEntry("META-INF/NOTICE", new byte[]{1}));
            IbcInstallationException error = Assertions.throwsType(IbcInstallationException.class,
                    () -> service(new FakeDownloader(zip(entries))).install(noClassesRoot.resolve("IBC"),
                            InstallProgress.none()),
                    "JAR without IBC classes must fail");
            Assertions.contains(error.getMessage(), "program classes", "JAR-content error is unclear");
        } finally {
            TestSupport.deleteTree(noClassesRoot);
        }

        Path launcherRoot = TestSupport.tempDirectory("ibc-install-launcher");
        try {
            List<ArchiveEntry> entries = validEntries("");
            replace(entries, "scripts/StartIBC.bat", "@echo off\r\n".getBytes(StandardCharsets.UTF_8));
            IbcInstallationException error = Assertions.throwsType(IbcInstallationException.class,
                    () -> service(new FakeDownloader(zip(entries))).install(launcherRoot.resolve("IBC"),
                            InstallProgress.none()),
                    "launcher without IBC.jar must fail");
            Assertions.contains(error.getMessage(), "does not reference IBC.jar", "launcher error is unclear");
        } finally {
            TestSupport.deleteTree(launcherRoot);
        }
    }

    private void entryCountLimit() throws Exception {
        Path root = TestSupport.tempDirectory("ibc-install-entry-limit");
        try {
            List<ArchiveEntry> entries = validEntries("");
            entries.add(0, new ArchiveEntry("extra-1", new byte[0]));
            IbcInstallerService service = new IbcInstallerService(new FakeDownloader(zip(entries)),
                    entries.size() - 1, 1024 * 1024, 1024 * 1024);
            IbcInstallationException error = Assertions.throwsType(IbcInstallationException.class,
                    () -> service.install(root.resolve("IBC"), InstallProgress.none()),
                    "entry-count limit must fail");
            Assertions.contains(error.getMessage(), "too many entries", "entry-count error is unclear");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void expansionLimits() throws Exception {
        Path entryRoot = TestSupport.tempDirectory("ibc-install-entry-bytes");
        try {
            List<ArchiveEntry> entries = validEntries("");
            entries.add(0, new ArchiveEntry("large.bin", new byte[256]));
            IbcInstallerService service = new IbcInstallerService(new FakeDownloader(zip(entries)),
                    100, 4096, 128);
            IbcInstallationException error = Assertions.throwsType(IbcInstallationException.class,
                    () -> service.install(entryRoot.resolve("IBC"), InstallProgress.none()),
                    "per-entry limit must fail");
            Assertions.contains(error.getMessage(), "safety limit", "entry-size error is unclear");
        } finally {
            TestSupport.deleteTree(entryRoot);
        }

        Path totalRoot = TestSupport.tempDirectory("ibc-install-total-bytes");
        try {
            List<ArchiveEntry> entries = validEntries("");
            entries.add(0, new ArchiveEntry("first.bin", new byte[96]));
            entries.add(1, new ArchiveEntry("second.bin", new byte[96]));
            IbcInstallerService service = new IbcInstallerService(new FakeDownloader(zip(entries)),
                    100, 150, 1024);
            IbcInstallationException error = Assertions.throwsType(IbcInstallationException.class,
                    () -> service.install(totalRoot.resolve("IBC"), InstallProgress.none()),
                    "total extraction limit must fail");
            Assertions.contains(error.getMessage(), "extraction safety limit", "total-size error is unclear");
        } finally {
            TestSupport.deleteTree(totalRoot);
        }
    }

    private void resultValidation() {
        Path path = Path.of("relative");
        IbcInstallResult result = new IbcInstallResult(path, "3.24.1", "asset.zip", "a".repeat(64), true);
        Assertions.equals(path.toAbsolutePath().normalize(), result.installationDirectory(),
                "result path must be normalized");
        Assertions.equals("a".repeat(64), result.sha256(), "result digest changed");
        Assertions.throwsType(IllegalArgumentException.class,
                () -> new IbcInstallResult(path, "3.24.1", "asset.zip", "bad", true),
                "invalid digest must be rejected");
        Assertions.throwsType(IllegalArgumentException.class,
                () -> new IbcInstallResult(path, " ", "asset.zip", "b".repeat(64), true),
                "blank version must be rejected");
    }

    private static IbcInstallerService service(IbcReleaseDownloader downloader) {
        return new IbcInstallerService(downloader, 4096, 256L * 1024L * 1024L, 128L * 1024L * 1024L);
    }

    private static byte[] validArchive(String prefix) throws IOException {
        return zip(validEntries(prefix));
    }

    private static List<ArchiveEntry> validEntries(String prefix) throws IOException {
        List<ArchiveEntry> entries = new ArrayList<>();
        entries.add(new ArchiveEntry(prefix + "IBC.jar", validIbcJar()));
        entries.add(new ArchiveEntry(prefix + "version", Version.IBC_BASELINE.getBytes(StandardCharsets.UTF_8)));
        entries.add(new ArchiveEntry(prefix + "config.ini", "IbLoginId=\n".getBytes(StandardCharsets.UTF_8)));
        entries.add(new ArchiveEntry(prefix + "LICENSE.txt", "GPL-3.0\n".getBytes(StandardCharsets.UTF_8)));
        entries.add(new ArchiveEntry(prefix + "scripts/StartIBC.bat",
                "@echo off\r\njava -cp \"IBC.jar\" ibcalpha.ibc.IbcTws\r\n".getBytes(StandardCharsets.UTF_8)));
        return entries;
    }

    private static void writeValidInstallation(Path destination) throws IOException {
        Files.createDirectories(destination.resolve("scripts"));
        Files.write(destination.resolve("IBC.jar"), validIbcJar());
        Files.writeString(destination.resolve("version"), Version.IBC_BASELINE, StandardCharsets.UTF_8);
        Files.writeString(destination.resolve("config.ini"), "IbLoginId=\n", StandardCharsets.UTF_8);
        Files.writeString(destination.resolve("LICENSE.txt"), "GPL-3.0\n", StandardCharsets.UTF_8);
        Files.writeString(destination.resolve("scripts").resolve("StartIBC.bat"),
                "@echo off\r\njava -cp \"IBC.jar\" ibcalpha.ibc.IbcTws\r\n", StandardCharsets.UTF_8);
    }

    private static byte[] validIbcJar() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("ibcalpha/ibc/IbcTws.class", new byte[]{(byte) 0xCA, (byte) 0xFE});
        entries.put("ibcalpha/ibc/IbcGateway.class", new byte[]{(byte) 0xCA, (byte) 0xFE});
        return jarWithEntries(entries);
    }

    private static byte[] jarWithEntry(String name, byte[] content) throws IOException {
        return jarWithEntries(Map.of(name, content));
    }

    private static byte[] jarWithEntries(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] zip(List<ArchiveEntry> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (ArchiveEntry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.content());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static void replace(List<ArchiveEntry> entries, String name, byte[] content) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).name().equals(name)) {
                entries.set(index, new ArchiveEntry(name, content));
                return;
            }
        }
        throw new AssertionError("Fixture entry not found: " + name);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void assertNoStaging(Path parent, String destinationName) throws IOException {
        try (var children = Files.list(parent)) {
            Assertions.isFalse(children.anyMatch(path -> path.getFileName().toString()
                            .startsWith("." + destinationName + ".install-")),
                    "staging directory was not cleaned");
        }
    }

    private record ArchiveEntry(String name, byte[] content) { }

    private static final class FakeDownloader implements IbcReleaseDownloader {
        private final byte[] archive;
        private final AtomicInteger calls = new AtomicInteger();

        private FakeDownloader(byte[] archive) {
            this.archive = archive.clone();
        }

        @Override
        public DownloadResult download(URI source, Path destination, InstallProgress progress) throws IOException {
            calls.incrementAndGet();
            Files.write(destination, archive);
            progress.update("Downloading IBC from GitHub", archive.length, archive.length);
            try {
                return new DownloadResult(source, archive.length, sha256(archive));
            } catch (Exception ex) {
                throw new IOException("Could not hash fixture", ex);
            }
        }
    }
}
