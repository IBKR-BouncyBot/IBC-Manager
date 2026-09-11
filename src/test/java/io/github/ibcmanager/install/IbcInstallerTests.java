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
import java.security.NoSuchAlgorithmException;
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
    private static final String TEST_VERSION = Version.IBC_MINIMUM_SUPPORTED_VERSION;
    private static final String FUTURE_VERSION = "4.0.0";

    @Override public String name() { return "Latest official IBC resolution and transactional ZIP installation"; }

    @Override
    public List<NamedTest> tests() {
        return List.of(
                new NamedTest("latest-release endpoint and metadata select the official Windows asset",
                        this::latestMetadataCoordinates),
                new NamedTest("latest-release metadata is parsed and validated fail-closed",
                        this::latestMetadataSafety),
                new NamedTest("GitHub asset digest and size are enforced", this::metadataIntegrity),
                new NamedTest("valid official-style archive installs transactionally", this::validInstall),
                new NamedTest("one optional top-level archive directory is supported", this::topLevelDirectory),
                new NamedTest("empty destination directory can be activated", this::emptyDestination),
                new NamedTest("existing current latest installation is reused without a download", this::existingInstall),
                new NamedTest("existing older compatible installation is never silently overwritten",
                        this::existingOlderInstall),
                new NamedTest("non-empty invalid destination is never overwritten", this::nonEmptyDestination),
                new NamedTest("download failure leaves no destination or staging tree", this::downloadFailure),
                new NamedTest("cancelled installation leaves no destination or staging tree", this::cancelledInstall),
                new NamedTest("destination changes during download are preserved and rejected", this::destinationRace),
                new NamedTest("GitHub release and redirect URI policy is restrictive", this::downloadUriPolicy),
                new NamedTest("ZIP traversal and Windows-ambiguous paths are rejected", this::pathTraversal),
                new NamedTest("case-insensitive and separator-normalized duplicate ZIP paths are rejected", this::duplicatePaths),
                new NamedTest("unexpected files outside a prefixed installation are rejected", this::unexpectedSibling),
                new NamedTest("required IBC files and version are validated", this::requiredFilesAndVersion),
                new NamedTest("IBC.jar and StartIBC.bat capabilities are validated", this::programContents),
                new NamedTest("archive entry-count limit is enforced", this::entryCountLimit),
                new NamedTest("per-entry and total expansion limits are enforced", this::expansionLimits),
                new NamedTest("install result validates its security metadata", this::resultValidation));
    }

    private void latestMetadataCoordinates() throws Exception {
        Assertions.equals(URI.create("https://api.github.com/repos/IbcAlpha/IBC/releases/latest"),
                GithubLatestIbcReleaseResolver.LATEST_RELEASE_API,
                "latest-release API endpoint changed unexpectedly");
        Assertions.equals("C:\\IBC", IbcInstallerService.DEFAULT_WINDOWS_DIRECTORY.toString(),
                "default Windows installation directory changed");

        String digest = "a".repeat(64);
        IbcReleaseInfo release = GithubLatestIbcReleaseResolver.parseReleaseMetadata(
                releaseJson(TEST_VERSION, TEST_VERSION, digest, 12345, false, false));
        Assertions.equals(TEST_VERSION, release.version(), "resolved version mismatch");
        Assertions.equals(TEST_VERSION, release.tagName(), "resolved tag mismatch");
        Assertions.equals(assetName(TEST_VERSION), release.assetName(), "Windows asset name mismatch");
        Assertions.equals(assetUri(TEST_VERSION, TEST_VERSION), release.downloadUri(),
                "Windows asset URI mismatch");
        Assertions.equals(12345L, release.assetBytes(), "asset size mismatch");
        Assertions.equals(digest, release.sha256(), "asset digest mismatch");

        GithubLatestIbcReleaseResolver.validateMetadataUri(
                GithubLatestIbcReleaseResolver.LATEST_RELEASE_API);
        Assertions.throwsType(IOException.class,
                () -> GithubLatestIbcReleaseResolver.validateMetadataUri(
                        URI.create("https://api.github.com/repos/IbcAlpha/IBC/releases/3.24.2")),
                "metadata resolver must use only the latest-release endpoint");
    }

    private void latestMetadataSafety() throws Exception {
        String digest = "b".repeat(64);
        IbcReleaseInfo future = GithubLatestIbcReleaseResolver.parseReleaseMetadata(
                releaseJson("v" + FUTURE_VERSION, FUTURE_VERSION, digest, 2048, false, false));
        Assertions.equals(FUTURE_VERSION, future.version(),
                "future numeric official release must be accepted dynamically");
        Assertions.equals(assetUri("v" + FUTURE_VERSION, FUTURE_VERSION), future.downloadUri(),
                "v-prefixed tag URI was not retained");

        assertMetadataRejected(releaseJson(TEST_VERSION, TEST_VERSION, digest, 100, true, false), "draft");
        assertMetadataRejected(releaseJson(TEST_VERSION, TEST_VERSION, digest, 100, false, true), "prerelease");
        assertMetadataRejected(releaseJson("3.24.1", "3.24.1", digest, 100, false, false), "older than");
        assertMetadataRejected(releaseJson(TEST_VERSION, TEST_VERSION, "not-a-digest", 100, false, false),
                "SHA-256");
        assertMetadataRejected(releaseJson(TEST_VERSION, TEST_VERSION, digest, 0, false, false),
                "safety limit");
        assertMetadataRejected(releaseJson(TEST_VERSION, TEST_VERSION, digest, 100, false, false)
                        .replace("https://github.com/", "https://evil.example/"),
                "unexpected IBC asset download URL");
        assertMetadataRejected(releaseJson(TEST_VERSION, "9.9.9", digest, 100, false, false),
                "expected Windows asset");
        String duplicateAsset = "{\"name\":\"" + assetName(TEST_VERSION)
                + "\",\"state\":\"uploaded\",\"size\":100,"
                + "\"digest\":\"sha256:" + digest
                + "\",\"browser_download_url\":\""
                + assetUri(TEST_VERSION, TEST_VERSION) + "\"}";
        String duplicateMetadata = releaseJson(TEST_VERSION, TEST_VERSION, digest, 100, false, false)
                .replace("\"assets\":[", "\"assets\":[" + duplicateAsset + ',');
        assertMetadataRejected(duplicateMetadata, "duplicate Windows assets");

        Assertions.throwsType(IOException.class,
                () -> StrictJsonParser.parse("{\"a\":1,\"a\":2}"),
                "duplicate JSON keys must be rejected");
        Assertions.throwsType(IOException.class,
                () -> StrictJsonParser.parse("{\"unterminated\":"),
                "malformed JSON must be rejected");
        Assertions.throwsType(IbcInstallationException.class,
                () -> GithubLatestIbcReleaseResolver.validateAssetUri(
                        URI.create("https://github.com/IbcAlpha/IBC/releases/download/"
                                + TEST_VERSION + "/" + assetName(TEST_VERSION) + "?x=1"),
                        TEST_VERSION, assetName(TEST_VERSION)),
                "asset URLs with queries must be rejected");
    }

    private void metadataIntegrity() throws Exception {
        Path root = TestSupport.tempDirectory("ibc-install-metadata-integrity");
        try {
            byte[] archive = validArchive("", TEST_VERSION);
            IbcReleaseInfo wrongDigest = new IbcReleaseInfo(TEST_VERSION, TEST_VERSION,
                    assetName(TEST_VERSION), assetUri(TEST_VERSION, TEST_VERSION), archive.length, "0".repeat(64));
            IbcInstallerService service = new IbcInstallerService(new FakeResolver(wrongDigest),
                    new FakeDownloader(archive), 4096, 256L * 1024L * 1024L, 128L * 1024L * 1024L);
            Path destination = root.resolve("IBC");
            IbcInstallationException error = Assertions.throwsType(IbcInstallationException.class,
                    () -> service.install(destination, InstallProgress.none()),
                    "download whose digest differs from GitHub metadata must be rejected");
            Assertions.contains(error.getMessage(), "published by GitHub",
                    "metadata-digest mismatch message is unclear");
            Assertions.isFalse(Files.exists(destination),
                    "metadata-integrity failure must not activate a destination");
            assertNoStaging(root, destination.getFileName().toString());

            IbcReleaseInfo wrongSize = new IbcReleaseInfo(TEST_VERSION, TEST_VERSION,
                    assetName(TEST_VERSION), assetUri(TEST_VERSION, TEST_VERSION), archive.length + 1L,
                    sha256(archive));
            IbcInstallationException sizeError = Assertions.throwsType(IbcInstallationException.class,
                    () -> new IbcInstallerService(new FakeResolver(wrongSize), new FakeDownloader(archive),
                            4096, 256L * 1024L * 1024L, 128L * 1024L * 1024L)
                            .install(root.resolve("IBC-size"), InstallProgress.none()),
                    "download whose size differs from GitHub metadata must be rejected");
            Assertions.contains(sizeError.getMessage(), "size does not match GitHub release metadata",
                    "metadata-size mismatch message is unclear");

            Assertions.throwsType(IllegalArgumentException.class,
                    () -> new IbcReleaseInfo(TEST_VERSION, TEST_VERSION, assetName(TEST_VERSION),
                            assetUri(TEST_VERSION, TEST_VERSION), 1, "bad"),
                    "invalid release digest must be rejected at construction time");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void validInstall() throws Exception {
        Path root = TestSupport.tempDirectory("ibc-install-valid");
        try {
            byte[] archive = validArchive("", TEST_VERSION);
            FakeDownloader downloader = new FakeDownloader(archive);
            IbcInstallerService service = service(downloader);
            Path destination = root.resolve("IBC");
            List<String> progress = new ArrayList<>();
            IbcInstallResult result = service.install(destination,
                    (message, completed, total) -> progress.add(message));
            Assertions.isTrue(result.downloaded(), "new installation must report a download");
            Assertions.equals(destination.toAbsolutePath(), result.installationDirectory(),
                    "installation directory mismatch");
            Assertions.equals(TEST_VERSION, result.version(), "installed version mismatch");
            Assertions.equals(assetName(TEST_VERSION), result.assetName(), "asset name mismatch");
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
            IbcInstallerService service = service(new FakeDownloader(validArchive("IBCWin-" + TEST_VERSION + "/", TEST_VERSION)));
            IbcInstallResult result = service.install(destination, InstallProgress.none());
            Assertions.equals(TEST_VERSION, result.version(), "prefixed archive version mismatch");
            Assertions.fileExists(destination.resolve("config.ini"), "prefixed archive was not flattened");
            Assertions.isFalse(Files.exists(destination.resolve("IBCWin-" + TEST_VERSION)),
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
            service(new FakeDownloader(validArchive("", TEST_VERSION))).install(destination, InstallProgress.none());
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
            writeValidInstallation(destination, TEST_VERSION);
            FakeDownloader downloader = new FakeDownloader(validArchive("", TEST_VERSION));
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

    private void existingOlderInstall() throws Exception {
        Path root = TestSupport.tempDirectory("ibc-install-existing-older");
        try {
            Path destination = root.resolve("IBC");
            writeValidInstallation(destination, TEST_VERSION);
            byte[] futureArchive = validArchive("", FUTURE_VERSION);
            FakeDownloader downloader = new FakeDownloader(futureArchive);
            IbcInstallationException error = Assertions.throwsType(IbcInstallationException.class,
                    () -> service(FUTURE_VERSION, futureArchive, downloader)
                            .install(destination, InstallProgress.none()),
                    "an older compatible installation must not be overwritten automatically");
            Assertions.contains(error.getMessage(), "will not overwrite a non-empty installation",
                    "existing-version refusal is unclear");
            Assertions.equals(0, downloader.calls.get(),
                    "existing differing version must be rejected before download");
            Assertions.equals(TEST_VERSION, Files.readString(destination.resolve("version")).trim(),
                    "existing installation was modified");
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
            FakeDownloader downloader = new FakeDownloader(validArchive("", TEST_VERSION));
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
                    () -> service(TEST_VERSION, validArchive("", TEST_VERSION), failing).install(destination, InstallProgress.none()),
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
            byte[] archive = validArchive("", TEST_VERSION);
            String archiveSha256 = sha256(archive);
            IbcReleaseDownloader cancelled = (source, target, progress) -> {
                Files.write(target, archive);
                Thread.currentThread().interrupt();
                return new IbcReleaseDownloader.DownloadResult(source, archive.length, archiveSha256);
            };
            Assertions.throwsType(CancellationException.class,
                    () -> service(TEST_VERSION, archive, cancelled).install(destination, InstallProgress.none()),
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
            byte[] archive = validArchive("", TEST_VERSION);
            String archiveSha256 = sha256(archive);
            IbcReleaseDownloader racing = (source, target, progress) -> {
                Files.write(target, archive);
                Files.writeString(destination, "created during download", StandardCharsets.UTF_8);
                return new IbcReleaseDownloader.DownloadResult(source, archive.length, archiveSha256);
            };
            IbcInstallationException error = Assertions.throwsType(IbcInstallationException.class,
                    () -> service(TEST_VERSION, archive, racing).install(destination, InstallProgress.none()),
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
                "https://github.com/IbcAlpha/IBC/releases/download/3.24.2/IBCWin-3.24.2.zip"));
        HttpsIbcReleaseDownloader.validateUri(URI.create(
                "https://release-assets.githubusercontent.com/github-production-release-asset/example"));
        HttpsIbcReleaseDownloader.validateUri(URI.create(
                "https://objects.githubusercontent.com/github-production-release-asset/example"));

        for (String rejected : List.of(
                "http://github.com/IbcAlpha/IBC/releases/download/3.24.2/IBCWin-3.24.2.zip",
                "https://user@github.com/IbcAlpha/IBC/releases/download/3.24.2/IBCWin-3.24.2.zip",
                "https://github.com:444/IbcAlpha/IBC/releases/download/3.24.2/IBCWin-3.24.2.zip",
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
                List<ArchiveEntry> entries = validEntries("", TEST_VERSION);
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
            List<ArchiveEntry> entries = validEntries("", TEST_VERSION);
            entries.add(new ArchiveEntry("CONFIG.INI", "duplicate".getBytes(StandardCharsets.UTF_8)));
            IbcInstallationException error = Assertions.throwsType(IbcInstallationException.class,
                    () -> service(new FakeDownloader(zip(entries))).install(root.resolve("IBC"), InstallProgress.none()),
                    "case-insensitive duplicate must be rejected");
            Assertions.contains(error.getMessage(), "duplicate path", "duplicate error is unclear");

            List<ArchiveEntry> separatorEntries = validEntries("", TEST_VERSION);
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
            List<ArchiveEntry> entries = validEntries("payload/", TEST_VERSION);
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
        for (String missing : List.of("IBC.jar", "version", "config.ini", "LICENSE.txt", "scripts/StartIBC.bat",
                "scripts/getExtraJavaOptions.ps1")) {
            Path root = TestSupport.tempDirectory("ibc-install-missing");
            try {
                List<ArchiveEntry> entries = validEntries("", TEST_VERSION);
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
            List<ArchiveEntry> entries = validEntries("", TEST_VERSION);
            replace(entries, "version", "9.9.9".getBytes(StandardCharsets.UTF_8));
            IbcInstallationException error = Assertions.throwsType(IbcInstallationException.class,
                    () -> service(new FakeDownloader(zip(entries))).install(root.resolve("IBC"), InstallProgress.none()),
                    "wrong IBC version must fail");
            Assertions.isTrue(error.getMessage().contains("internally inconsistent")
                            || error.getMessage().contains("does not match GitHub's latest release"),
                    "wrong-version error is unclear");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void programContents() throws Exception {
        Path invalidJarRoot = TestSupport.tempDirectory("ibc-install-jar");
        try {
            List<ArchiveEntry> entries = validEntries("", TEST_VERSION);
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
            List<ArchiveEntry> entries = validEntries("", TEST_VERSION);
            replace(entries, "IBC.jar", jarWithEntry("META-INF/NOTICE", new byte[]{1}));
            IbcInstallationException error = Assertions.throwsType(IbcInstallationException.class,
                    () -> service(new FakeDownloader(zip(entries))).install(noClassesRoot.resolve("IBC"),
                            InstallProgress.none()),
                    "JAR without IBC classes must fail");
            Assertions.contains(error.getMessage(), "program class", "JAR-content error is unclear");
        } finally {
            TestSupport.deleteTree(noClassesRoot);
        }

        Path mismatchRoot = TestSupport.tempDirectory("ibc-install-version-mismatch");
        try {
            List<ArchiveEntry> entries = validEntries("", TEST_VERSION);
            Map<String, byte[]> jarEntries = new LinkedHashMap<>();
            jarEntries.put("ibcalpha/ibc/IbcTws.class", new byte[]{0});
            jarEntries.put("ibcalpha/ibc/IbcGateway.class", new byte[]{0});
            jarEntries.put("ibcalpha/ibc/CommandDispatcher.class", new byte[]{0});
            jarEntries.put("ibcalpha/ibc/RestartTask.class", new byte[]{0});
            jarEntries.put("ibcalpha/ibc/DefaultSettings.class", new byte[]{0});
            jarEntries.put("ibcalpha/ibc/IbcVersionInfo.class",
                    TestSupport.mismatchedIbcVersionInfoClassBytes());
            replace(entries, "IBC.jar", jarWithEntries(jarEntries));
            IbcInstallationException error = Assertions.throwsType(IbcInstallationException.class,
                    () -> service(new FakeDownloader(zip(entries))).install(mismatchRoot.resolve("IBC"),
                            InstallProgress.none()),
                    "mixed version file and IBC.jar must fail");
            Assertions.contains(error.getMessage(), "internally inconsistent",
                    "mixed-installation error is unclear");
        } finally {
            TestSupport.deleteTree(mismatchRoot);
        }

        Path launcherRoot = TestSupport.tempDirectory("ibc-install-launcher");
        try {
            List<ArchiveEntry> entries = validEntries("", TEST_VERSION);
            replace(entries, "scripts/StartIBC.bat", "@echo off\r\n".getBytes(StandardCharsets.UTF_8));
            IbcInstallationException error = Assertions.throwsType(IbcInstallationException.class,
                    () -> service(new FakeDownloader(zip(entries))).install(launcherRoot.resolve("IBC"),
                            InstallProgress.none()),
                    "launcher without IBC.jar must fail");
            Assertions.contains(error.getMessage(), "does not reference IBC.jar", "launcher error is unclear");
        } finally {
            TestSupport.deleteTree(launcherRoot);
        }

        Path helperRoot = TestSupport.tempDirectory("ibc-install-helper");
        try {
            List<ArchiveEntry> entries = validEntries("", TEST_VERSION);
            replace(entries, "scripts/getExtraJavaOptions.ps1",
                    "Write-Output broken\r\n".getBytes(StandardCharsets.UTF_8));
            IbcInstallationException error = Assertions.throwsType(IbcInstallationException.class,
                    () -> service(new FakeDownloader(zip(entries))).install(helperRoot.resolve("IBC"),
                            InstallProgress.none()),
                    "incompatible referenced helper must fail");
            Assertions.contains(error.getMessage(), "is not compatible with IBC Manager",
                    "helper compatibility error is unclear");
        } finally {
            TestSupport.deleteTree(helperRoot);
        }
    }

    private void entryCountLimit() throws Exception {
        Path root = TestSupport.tempDirectory("ibc-install-entry-limit");
        try {
            List<ArchiveEntry> entries = validEntries("", TEST_VERSION);
            entries.add(0, new ArchiveEntry("extra-1", new byte[0]));
            IbcInstallerService service = serviceWithLimits(TEST_VERSION, zip(entries), new FakeDownloader(zip(entries)),
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
            List<ArchiveEntry> entries = validEntries("", TEST_VERSION);
            entries.add(0, new ArchiveEntry("large.bin", new byte[256]));
            IbcInstallerService service = serviceWithLimits(TEST_VERSION, zip(entries), new FakeDownloader(zip(entries)),
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
            List<ArchiveEntry> entries = validEntries("", TEST_VERSION);
            entries.add(0, new ArchiveEntry("first.bin", new byte[96]));
            entries.add(1, new ArchiveEntry("second.bin", new byte[96]));
            IbcInstallerService service = serviceWithLimits(TEST_VERSION, zip(entries), new FakeDownloader(zip(entries)),
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
        IbcInstallResult result = new IbcInstallResult(path, "3.24.2", "asset.zip", "a".repeat(64), true);
        Assertions.equals(path.toAbsolutePath().normalize(), result.installationDirectory(),
                "result path must be normalized");
        Assertions.equals("a".repeat(64), result.sha256(), "result digest changed");
        Assertions.throwsType(IllegalArgumentException.class,
                () -> new IbcInstallResult(path, "3.24.2", "asset.zip", "bad", true),
                "invalid digest must be rejected");
        Assertions.throwsType(IllegalArgumentException.class,
                () -> new IbcInstallResult(path, " ", "asset.zip", "b".repeat(64), true),
                "blank version must be rejected");
    }


    private static IbcInstallerService service(FakeDownloader downloader) {
        return service(TEST_VERSION, downloader.archive(), downloader);
    }

    private static IbcInstallerService service(String version, byte[] expectedArchive,
            IbcReleaseDownloader downloader) {
        return new IbcInstallerService(new FakeResolver(release(version, expectedArchive)), downloader,
                4096, 256L * 1024L * 1024L, 128L * 1024L * 1024L);
    }

    private static IbcInstallerService serviceWithLimits(String version, byte[] expectedArchive,
            IbcReleaseDownloader downloader, int maxEntries, long maxExtractedBytes, long maxEntryBytes) {
        return new IbcInstallerService(new FakeResolver(release(version, expectedArchive)), downloader,
                maxEntries, maxExtractedBytes, maxEntryBytes);
    }

    private static IbcReleaseInfo release(String version, byte[] archive) {
        return new IbcReleaseInfo(version, version, assetName(version), assetUri(version, version),
                archive.length, sha256(archive));
    }

    private static String assetName(String version) {
        return "IBCWin-" + version + ".zip";
    }

    private static URI assetUri(String tag, String version) {
        return URI.create("https://github.com/IbcAlpha/IBC/releases/download/" + tag + "/" + assetName(version));
    }

    private static String releaseJson(String tag, String assetVersion, String digest, long size,
            boolean draft, boolean prerelease) {
        return "{"
                + "\"tag_name\":\"" + tag + "\","
                + "\"draft\":" + draft + ','
                + "\"prerelease\":" + prerelease + ','
                + "\"assets\":[{"
                + "\"name\":\"" + assetName(assetVersion) + "\","
                + "\"state\":\"uploaded\","
                + "\"size\":" + size + ','
                + "\"digest\":\"sha256:" + digest + "\","
                + "\"browser_download_url\":\"" + assetUri(tag, assetVersion) + "\""
                + "}]}";
    }

    private static void assertMetadataRejected(String json, String expectedMessage) throws Exception {
        Exception error;
        try {
            GithubLatestIbcReleaseResolver.parseReleaseMetadata(json);
            throw new AssertionError("invalid latest-release metadata was accepted");
        } catch (IOException | IbcInstallationException ex) {
            error = ex;
        }
        Assertions.contains(error.getMessage(), expectedMessage,
                "latest-release metadata rejection was unclear");
    }

    private static byte[] validArchive(String prefix, String version) throws IOException {
        return zip(validEntries(prefix, version));
    }

    private static List<ArchiveEntry> validEntries(String prefix, String version) throws IOException {
        List<ArchiveEntry> entries = new ArrayList<>();
        entries.add(new ArchiveEntry(prefix + "IBC.jar", validIbcJar(version)));
        entries.add(new ArchiveEntry(prefix + "version", version.getBytes(StandardCharsets.UTF_8)));
        entries.add(new ArchiveEntry(prefix + "config.ini", "IbLoginId=\n".getBytes(StandardCharsets.UTF_8)));
        entries.add(new ArchiveEntry(prefix + "LICENSE.txt", "GPL-3.0\n".getBytes(StandardCharsets.UTF_8)));
        entries.add(new ArchiveEntry(prefix + "scripts/StartIBC.bat", launcherBytes()));
        entries.add(new ArchiveEntry(prefix + "scripts/getExtraJavaOptions.ps1", helperBytes()));
        return entries;
    }

    private static byte[] launcherBytes() {
        return ("@echo off\r\n"
                + "rem IBC.jar\r\n"
                + "rem /Gateway\r\n"
                + "rem /TwsPath:\r\n"
                + "rem /TwsSettingsPath:\r\n"
                + "rem /IbcPath:\r\n"
                + "rem /Config:\r\n"
                + "rem /JavaPath:\r\n"
                + "rem /Mode:\r\n"
                + "rem /On2FATimeout:\r\n"
                + "rem Starting IBC with this command:\r\n"
                + "rem getExtraJavaOptions.ps1\r\n"
                + "rem EXTRA_JAVA_OPTIONS\r\n"
                + "rem IBCSessionId\r\n"
                + "rem IBC is paused\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] helperBytes() {
        return ("param([string]$Install4J)\r\n"
                + "$confPath = Join-Path $Install4J \"i4jparams.conf\"\r\n"
                + "$line = Select-String -Path $confPath -Pattern 'javaOptions'\r\n"
                + "Write-Output $line\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private static void writeValidInstallation(Path destination, String version) throws IOException {
        Files.createDirectories(destination.resolve("scripts"));
        Files.write(destination.resolve("IBC.jar"), validIbcJar(version));
        Files.writeString(destination.resolve("version"), version, StandardCharsets.UTF_8);
        Files.writeString(destination.resolve("config.ini"), "IbLoginId=\n", StandardCharsets.UTF_8);
        Files.writeString(destination.resolve("LICENSE.txt"), "GPL-3.0\n", StandardCharsets.UTF_8);
        Files.write(destination.resolve("scripts").resolve("StartIBC.bat"), launcherBytes());
        Files.write(destination.resolve("scripts").resolve("getExtraJavaOptions.ps1"), helperBytes());
    }

    private static byte[] validIbcJar(String version) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("ibcalpha/ibc/IbcTws.class", new byte[]{(byte) 0xCA, (byte) 0xFE});
        entries.put("ibcalpha/ibc/IbcGateway.class", new byte[]{(byte) 0xCA, (byte) 0xFE});
        entries.put("ibcalpha/ibc/CommandDispatcher.class", new byte[]{(byte) 0xCA, (byte) 0xFE});
        entries.put("ibcalpha/ibc/RestartTask.class", new byte[]{(byte) 0xCA, (byte) 0xFE});
        entries.put("ibcalpha/ibc/DefaultSettings.class", new byte[]{(byte) 0xCA, (byte) 0xFE});
        entries.put("ibcalpha/ibc/IbcVersionInfo.class", TestSupport.ibcVersionInfoClassBytes(version));
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

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError("SHA-256 unavailable", ex);
        }
    }

    private static void assertNoStaging(Path parent, String destinationName) throws IOException {
        try (var children = Files.list(parent)) {
            Assertions.isFalse(children.anyMatch(path -> path.getFileName().toString()
                            .startsWith("." + destinationName + ".install-")),
                    "staging directory was not cleaned");
        }
    }

    private record ArchiveEntry(String name, byte[] content) { }

    private static final class FakeResolver implements IbcReleaseResolver {
        private final IbcReleaseInfo release;
        private final AtomicInteger calls = new AtomicInteger();

        private FakeResolver(IbcReleaseInfo release) {
            this.release = release;
        }

        @Override
        public IbcReleaseInfo resolveLatest(InstallProgress progress) {
            calls.incrementAndGet();
            progress.update("Checking GitHub for the latest official IBC release", 0, -1);
            return release;
        }
    }

    private static final class FakeDownloader implements IbcReleaseDownloader {
        private final byte[] archive;
        private final AtomicInteger calls = new AtomicInteger();

        private FakeDownloader(byte[] archive) {
            this.archive = archive.clone();
        }

        private byte[] archive() {
            return archive.clone();
        }

        @Override
        public DownloadResult download(URI source, Path destination, InstallProgress progress) throws IOException {
            calls.incrementAndGet();
            Files.write(destination, archive);
            progress.update("Downloading IBC from GitHub", archive.length, archive.length);
            return new DownloadResult(source, archive.length, sha256(archive));
        }
    }

}
