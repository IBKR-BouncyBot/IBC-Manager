package io.github.ibcmanager.install;

import io.github.ibcmanager.app.OperatingSystem;
import io.github.ibcmanager.security.BoundedFileReader;
import io.github.ibcmanager.security.SecureFileOperations;
import io.github.ibcmanager.storage.AtomicFileWriter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class IbcInstallerService {
    public static final Path DEFAULT_WINDOWS_DIRECTORY = Path.of("C:\\IBC");
    private static final int DEFAULT_MAX_ENTRIES = 4096;
    private static final long DEFAULT_MAX_EXTRACTED_BYTES = 256L * 1024L * 1024L;
    private static final long DEFAULT_MAX_ENTRY_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_VERSION_BYTES = 4096;
    private static final String ACTIVATION_MARKER = ".ibc-manager-install-owner";
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private final IbcReleaseResolver releaseResolver;
    private final IbcReleaseDownloader downloader;
    private final int maxEntries;
    private final long maxExtractedBytes;
    private final long maxEntryBytes;

    public IbcInstallerService() {
        this(new GithubLatestIbcReleaseResolver(), new HttpsIbcReleaseDownloader(),
                DEFAULT_MAX_ENTRIES, DEFAULT_MAX_EXTRACTED_BYTES, DEFAULT_MAX_ENTRY_BYTES);
    }

    IbcInstallerService(IbcReleaseResolver releaseResolver, IbcReleaseDownloader downloader,
            int maxEntries, long maxExtractedBytes, long maxEntryBytes) {
        this.releaseResolver = Objects.requireNonNull(releaseResolver, "releaseResolver");
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        if (maxEntries < 1 || maxExtractedBytes < 1 || maxEntryBytes < 1) {
            throw new IllegalArgumentException("Installer safety limits must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxExtractedBytes = maxExtractedBytes;
        this.maxEntryBytes = maxEntryBytes;
    }

    public boolean isAvailable() {
        return OperatingSystem.current() == OperatingSystem.WINDOWS;
    }

    public IbcInstallResult installDefault(InstallProgress progress) throws IbcInstallationException {
        return install(DEFAULT_WINDOWS_DIRECTORY, progress);
    }

    public IbcInstallResult install(Path requestedDestination, InstallProgress progress)
            throws IbcInstallationException {
        Path destination = Objects.requireNonNull(requestedDestination, "requestedDestination")
                .toAbsolutePath().normalize();
        InstallProgress listener = progress == null ? InstallProgress.none() : progress;
        try {
            checkCancelled();
            IbcInstallationValidator.InstallationInfo existing = inspectExistingInstallation(destination);
            boolean restoreEmptyDestination = false;
            if (existing == null) restoreEmptyDestination = rejectNonEmptyDestination(destination);

            IbcReleaseInfo release = releaseResolver.resolveLatest(listener);
            checkCancelled();
            if (existing != null) {
                if (IbcVersion.parse(existing.version()).semanticallyEquals(IbcVersion.parse(release.version()))) {
                    return new IbcInstallResult(destination, existing.version(),
                            "existing installation", sha256(destination.resolve("IBC.jar")), false);
                }
                throw new IbcInstallationException("The IBC destination contains version " + existing.version()
                        + ", but GitHub's latest official release is " + release.version() + ". IBC Manager will "
                        + "not overwrite a non-empty installation. Rename or remove the existing IBC folder, then "
                        + "run the installation again.");
            }

            Path parent = destination.getParent();
            if (parent == null) throw new IbcInstallationException("IBC installation path has no parent directory.");
            SecureFileOperations.ensureDirectory(parent);

            Path archive = Files.createTempFile("ibc-manager-ibc-", ".zip");
            Path staging = parent.resolve("." + destination.getFileName() + ".install-" + UUID.randomUUID());
            String activationToken = UUID.randomUUID().toString();
            boolean activated = false;
            try {
                listener.update("Downloading official IBC " + release.version(), 0, release.assetBytes());
                IbcReleaseDownloader.DownloadResult download =
                        downloader.download(release.downloadUri(), archive, listener);
                checkCancelled();
                validateDownload(archive, download, release);
                listener.update("Validating and extracting IBC " + release.version(),
                        download.bytes(), download.bytes());
                Files.createDirectory(staging);
                extractSafely(archive, staging);
                checkCancelled();
                Path extractedRoot = locateInstallationRoot(staging);
                IbcInstallationValidator.InstallationInfo extracted = validateInstallation(extractedRoot);
                requireReleaseVersion(extracted.version(), release.version());
                ensureOnlyInstallationTree(staging, extractedRoot);
                Path marker = extractedRoot.resolve(ACTIVATION_MARKER);
                AtomicFileWriter.write(marker, activationToken.getBytes(StandardCharsets.US_ASCII), false);
                checkCancelled();
                listener.update("Installing validated IBC files", download.bytes(), download.bytes());
                activate(extractedRoot, staging, destination);
                activated = true;
                try {
                    validateActivationMarker(destination, activationToken);
                    IbcInstallationValidator.InstallationInfo installed = validateInstallation(destination);
                    requireReleaseVersion(installed.version(), release.version());
                    Files.delete(destination.resolve(ACTIVATION_MARKER));
                } catch (IOException | IbcInstallationException | RuntimeException validationFailure) {
                    rollbackActivatedDestination(destination, activationToken, restoreEmptyDestination, validationFailure);
                    activated = false;
                    throw validationFailure;
                }
                listener.update("IBC installation completed", download.bytes(), download.bytes());
                return new IbcInstallResult(destination, readVersion(destination), release.assetName(),
                        download.sha256(), true);
            } finally {
                cleanupQuietly(staging);
                try { Files.deleteIfExists(archive); }
                catch (IOException ignored) { }
                if (!activated && restoreEmptyDestination
                        && !Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    try { SecureFileOperations.ensureDirectory(destination); }
                    catch (IOException ignored) { }
                }
            }
        } catch (IbcInstallationException ex) {
            throw ex;
        } catch (CancellationException ex) {
            throw ex;
        } catch (AccessDeniedException ex) {
            throw new IbcInstallationException("Windows denied access to " + destination
                    + ". Close programs using the folder or run IBC Manager as administrator, then try again.", ex);
        } catch (IOException | SecurityException ex) {
            throw new IbcInstallationException("Could not install IBC in " + destination + ": "
                    + safeMessage(ex), ex);
        }
    }

    public static boolean isValidInstallation(Path directory) {
        if (directory == null) return false;
        try {
            validateInstallation(directory.toAbsolutePath().normalize());
            return true;
        } catch (IOException | IbcInstallationException | SecurityException ex) {
            return false;
        }
    }

    private static IbcInstallationValidator.InstallationInfo inspectExistingInstallation(Path destination)
            throws IOException {
        if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.isSymbolicLink(destination) || !Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            return validateInstallation(destination);
        } catch (IOException | IbcInstallationException | SecurityException ex) {
            return null;
        }
    }

    private static void requireReleaseVersion(String installedVersion, String releaseVersion)
            throws IbcInstallationException {
        if (!IbcVersion.parse(installedVersion).semanticallyEquals(IbcVersion.parse(releaseVersion))) {
            throw new IbcInstallationException("The downloaded archive reports IBC " + installedVersion
                    + " but GitHub identified the latest release as " + releaseVersion + '.');
        }
    }

    private static void validateDownload(Path archive, IbcReleaseDownloader.DownloadResult download,
            IbcReleaseInfo release) throws IOException, IbcInstallationException {
        HttpsIbcReleaseDownloader.validateUri(download.finalUri());
        long actualBytes = Files.size(archive);
        if (actualBytes != download.bytes()) {
            throw new IbcInstallationException("Downloaded IBC archive size changed during transfer.");
        }
        if (actualBytes != release.assetBytes()) {
            throw new IbcInstallationException("Downloaded IBC archive size does not match GitHub release metadata.");
        }
        String actualSha256 = sha256(archive);
        if (!actualSha256.equals(download.sha256())) {
            throw new IbcInstallationException("Downloaded IBC archive SHA-256 changed during transfer.");
        }
        if (!actualSha256.equals(release.sha256())) {
            throw new IbcInstallationException("Downloaded IBC archive does not match the SHA-256 digest "
                    + "published by GitHub for the latest release asset.");
        }
    }

    private void extractSafely(Path archive, Path destination) throws IOException, IbcInstallationException {
        Set<String> names = new HashSet<>();
        long totalBytes = 0;
        int entries = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream input = new ZipInputStream(
                Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS), StandardCharsets.UTF_8)) {
            while (true) {
                checkCancelled();
                ZipEntry entry = input.getNextEntry();
                if (entry == null) break;
                entries++;
                if (entries > maxEntries) {
                    throw new IbcInstallationException("IBC archive contains too many entries.");
                }
                String normalizedName = normalizeEntryName(entry.getName());
                Path relative = Path.of(normalizedName).normalize();
                String comparisonKey = relative.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (!names.add(comparisonKey)) {
                    throw new IbcInstallationException("IBC archive contains a duplicate path: " + normalizedName);
                }
                Path output = destination.resolve(relative).normalize();
                if (!output.startsWith(destination)) {
                    throw new IbcInstallationException("IBC archive contains a path outside the installation folder.");
                }
                if (entry.isDirectory() || normalizedName.endsWith("/")) {
                    SecureFileOperations.ensureDirectory(output);
                    input.closeEntry();
                    continue;
                }
                long declared = entry.getSize();
                if (declared > maxEntryBytes) {
                    throw new IbcInstallationException("IBC archive entry is larger than the safety limit: "
                            + normalizedName);
                }
                Path parent = output.getParent();
                if (parent != null) SecureFileOperations.ensureDirectory(parent);
                long entryBytes = 0;
                try (var stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    while (true) {
                        checkCancelled();
                        int count = input.read(buffer);
                        if (count < 0) break;
                        entryBytes += count;
                        totalBytes += count;
                        if (entryBytes > maxEntryBytes || totalBytes > maxExtractedBytes) {
                            throw new IbcInstallationException("IBC archive exceeds the extraction safety limit.");
                        }
                        stream.write(buffer, 0, count);
                    }
                }
                input.closeEntry();
            }
        }
        if (entries == 0) throw new IbcInstallationException("The downloaded IBC archive is empty.");
    }

    private static String normalizeEntryName(String original) throws IbcInstallationException {
        if (original == null || original.isBlank() || original.indexOf('\0') >= 0) {
            throw new IbcInstallationException("IBC archive contains an invalid empty path.");
        }
        String value = original.replace('\\', '/');
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
            throw new IbcInstallationException("IBC archive contains an absolute path: " + original);
        }
        String[] segments = value.split("/", -1);
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            boolean trailingDirectoryMarker = index == segments.length - 1 && segment.isEmpty();
            if (trailingDirectoryMarker) continue;
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IbcInstallationException("IBC archive contains a traversal or ambiguous path: " + original);
            }
            validateWindowsSegment(segment, original);
        }
        return value;
    }

    private static void validateWindowsSegment(String segment, String original) throws IbcInstallationException {
        if (segment.endsWith(".") || segment.endsWith(" ")) {
            throw new IbcInstallationException("IBC archive contains a Windows-ambiguous path: " + original);
        }
        for (int index = 0; index < segment.length(); index++) {
            char character = segment.charAt(index);
            if (character < 0x20 || "<>:\"|?*".indexOf(character) >= 0) {
                throw new IbcInstallationException("IBC archive contains an unsupported Windows path: " + original);
            }
        }
        String base = segment;
        int dot = base.indexOf('.');
        if (dot >= 0) base = base.substring(0, dot);
        if (WINDOWS_RESERVED_NAMES.contains(base.toUpperCase(Locale.ROOT))) {
            throw new IbcInstallationException("IBC archive contains a reserved Windows path: " + original);
        }
    }

    private static Path locateInstallationRoot(Path extractionRoot) throws IOException, IbcInstallationException {
        List<Path> jars = new ArrayList<>();
        try (var walk = Files.walk(extractionRoot)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                if (path.getFileName().toString().equalsIgnoreCase("IBC.jar")) jars.add(path);
            }
        }
        if (jars.size() != 1) {
            throw new IbcInstallationException("Expected exactly one IBC.jar in the official archive; found "
                    + jars.size() + '.');
        }
        return jars.get(0).getParent();
    }

    private static void ensureOnlyInstallationTree(Path extractionRoot, Path installationRoot)
            throws IOException, IbcInstallationException {
        if (extractionRoot.equals(installationRoot)) return;
        try (var walk = Files.walk(extractionRoot)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                if (!file.startsWith(installationRoot)) {
                    throw new IbcInstallationException(
                            "IBC archive contains unexpected files outside its installation tree.");
                }
            }
        }
    }

    private static IbcInstallationValidator.InstallationInfo validateInstallation(Path root)
            throws IOException, IbcInstallationException {
        return new IbcInstallationValidator().validate(root);
    }

    private static String readVersion(Path root) throws IOException, IbcInstallationException {
        String value = BoundedFileReader.readString(root.resolve("version"), StandardCharsets.UTF_8,
                MAX_VERSION_BYTES, "IBC version file").trim();
        if (value.startsWith("\uFEFF")) value = value.substring(1).trim();
        if (value.isEmpty() || value.length() > 32) {
            throw new IbcInstallationException("IBC version file is invalid.");
        }
        return IbcVersion.parse(value).text();
    }

    private static boolean rejectNonEmptyDestination(Path destination) throws IOException, IbcInstallationException {
        if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.isSymbolicLink(destination)) {
            throw new IbcInstallationException("The IBC destination must not be a symbolic link: " + destination);
        }
        if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IbcInstallationException("The IBC destination exists but is not a directory: " + destination);
        }
        try (var children = Files.list(destination)) {
            if (children.findAny().isPresent()) {
                throw new IbcInstallationException("The IBC destination is not empty and will not be overwritten: "
                        + destination);
            }
        }
        return true;
    }

    private static void activate(Path extractedRoot, Path stagingRoot, Path destination) throws IOException {
        checkCancelled();
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(destination)
                    || !Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("IBC destination changed while installation was in progress.");
            }
            try (var children = Files.list(destination)) {
                if (children.findAny().isPresent()) {
                    throw new IOException("IBC destination changed while installation was in progress.");
                }
            }
            Files.delete(destination);
        }
        try {
            Files.move(extractedRoot, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(extractedRoot, destination);
        } catch (DirectoryNotEmptyException | FileAlreadyExistsException ex) {
            throw new IOException("IBC destination changed while installation was in progress.", ex);
        }
        if (!extractedRoot.equals(stagingRoot)) SecureFileOperations.deleteTree(stagingRoot);
    }

    private static void validateActivationMarker(Path destination, String expected)
            throws IOException, IbcInstallationException {
        Path marker = destination.resolve(ACTIVATION_MARKER);
        String actual = BoundedFileReader.readString(marker, StandardCharsets.US_ASCII, 128,
                "IBC installation activation marker").trim();
        if (!expected.equals(actual)) {
            throw new IbcInstallationException("IBC installation activation marker is invalid.");
        }
    }

    private static void rollbackActivatedDestination(Path destination, String token,
            boolean restoreEmptyDestination, Throwable primary) {
        try {
            Path marker = destination.resolve(ACTIVATION_MARKER);
            String actual = BoundedFileReader.readString(marker, StandardCharsets.US_ASCII, 128,
                    "IBC installation activation marker").trim();
            if (!token.equals(actual)) {
                throw new IOException("Refusing to roll back an IBC destination not owned by this installation");
            }
            SecureFileOperations.deleteTree(destination);
            if (restoreEmptyDestination) SecureFileOperations.ensureDirectory(destination);
        } catch (IOException rollbackFailure) {
            primary.addSuppressed(rollbackFailure);
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
        SecureFileOperations.requireRegularFile(file, "File being hashed");
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            while (true) {
                int count = input.read(buffer);
                if (count < 0) break;
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void cleanupQuietly(Path root) {
        try {
            SecureFileOperations.deleteTree(root);
        } catch (IOException ignored) {
            // Best effort for a staging directory that was never made active.
        }
    }


    private static String safeMessage(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("IBC installation was cancelled");
        }
    }
}
