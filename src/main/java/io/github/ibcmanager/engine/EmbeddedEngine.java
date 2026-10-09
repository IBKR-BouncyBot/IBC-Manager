package io.github.ibcmanager.engine;

import io.github.ibcmanager.app.Version;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.security.FilePermissionHardener;
import io.github.ibcmanager.security.SecureFileOperations;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** The only runtime engine source is the payload compiled into this Manager release. */
public final class EmbeddedEngine {
    private static final String RESOURCE = "/integrated-engine/";
    private static final int MAX_BUNDLE_BYTES = 8 * 1024 * 1024;
    private static final Set<String> REQUIRED = Set.of("IBC.jar", "version", "engine-version",
            "config.ini", "LICENSE.txt", "scripts/StartIBC.bat", "scripts/getExtraJavaOptions.ps1");

    private EmbeddedEngine() { }

    /** No external installation path is accepted, including from imported 1.x profiles. */
    public static Path ensureFor(Profile profile) throws IOException {
        if (profile.twsSettingsPath().toString().isBlank()) {
            throw new IOException("A Gateway settings directory is required for the integrated engine");
        }
        return ensureUnder(profile.twsSettingsPath());
    }

    /** Extract below the validated Gateway settings tree, avoiding unquoted CMD user-home paths. */
    public static synchronized Path ensureUnder(Path settingsDirectory) throws IOException {
        Bundle bundle = readBundle();
        Path parent = settingsDirectory.toAbsolutePath().normalize().resolve(".ibc-manager-engine");
        FilePermissionHardener.hardenDirectory(parent);
        Path lockPath = parent.resolve("install.lock");
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            SecureFileOperations.requireRegularFile(lockPath, "Integrated engine lock");
        }
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                FileLock lock = channel.lock()) {
            if (!lock.isValid()) throw new IOException("Could not lock integrated engine installation");
            FilePermissionHardener.hardenFile(lockPath);
            Path target = parent.resolve(Version.ENGINE_VERSION + "-" + bundle.digest().substring(0, 16));
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                verifyDirectory(target, bundle);
                return target;
            }
            Path stage = Files.createTempDirectory(parent, ".install-");
            try {
                FilePermissionHardener.hardenDirectory(stage);
                for (Map.Entry<String, byte[]> file : bundle.files().entrySet()) {
                    Path output = stage.resolve(file.getKey());
                    FilePermissionHardener.hardenDirectory(output.getParent());
                    Files.write(output, file.getValue(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    FilePermissionHardener.hardenFile(output);
                }
                verifyDirectory(stage, bundle);
                try { Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException ex) { Files.move(stage, target); }
                verifyDirectory(target, bundle);
                return target;
            } finally {
                if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) SecureFileOperations.deleteTree(stage);
            }
        }
    }

    public static void verifyBundledPayload() throws IOException { readBundle(); }

    private static Bundle readBundle() throws IOException {
        byte[] manifest = readResource("SHA256SUMS.txt", 64 * 1024);
        Map<String, String> expected = new LinkedHashMap<>();
        for (String line : new String(manifest, StandardCharsets.US_ASCII).split("\\n")) {
            if (line.isBlank()) continue;
            if (line.length() < 67 || !line.substring(0, 64).matches("[a-f0-9]{64}")
                    || !line.substring(64, 66).equals("  ")) {
                throw new IOException("Invalid integrated engine checksum manifest");
            }
            String name = line.substring(66);
            if (!REQUIRED.contains(name) || expected.put(name, line.substring(0, 64)) != null) {
                throw new IOException("Unexpected or duplicate engine manifest entry");
            }
        }
        if (!expected.keySet().equals(REQUIRED)) throw new IOException("Incomplete integrated engine manifest");
        byte[] payload = readResource("payload.zip", MAX_BUNDLE_BYTES);
        Map<String, byte[]> files = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(payload), StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName();
                if (!REQUIRED.contains(name) || entry.isDirectory() || files.containsKey(name)) {
                    throw new IOException("Unexpected or duplicate integrated engine ZIP entry");
                }
                byte[] bytes = zip.readNBytes(MAX_BUNDLE_BYTES + 1);
                total += bytes.length;
                if (total > MAX_BUNDLE_BYTES || bytes.length == 0 || !digest(bytes).equals(expected.get(name))) {
                    throw new IOException("Integrated engine checksum or size verification failed: " + name);
                }
                files.put(name, bytes);
            }
        }
        if (!files.keySet().equals(REQUIRED)) throw new IOException("Incomplete integrated engine payload");
        if (!new String(files.get("engine-version"), StandardCharsets.US_ASCII).trim().equals(Version.ENGINE_VERSION)) {
            throw new IOException("Integrated engine revision mismatch");
        }
        return new Bundle(files, digest(manifest));
    }

    private static byte[] readResource(String name, int max) throws IOException {
        try (InputStream input = EmbeddedEngine.class.getResourceAsStream(RESOURCE + name)) {
            if (input == null) throw new IOException("The Manager package is missing its integrated engine: " + name);
            byte[] bytes = input.readNBytes(max + 1);
            if (bytes.length == 0 || bytes.length > max) throw new IOException("Invalid engine resource size");
            return bytes;
        }
    }

    private static void verifyDirectory(Path root, Bundle bundle) throws IOException {
        SecureFileOperations.requireDirectory(root, "Integrated engine directory");
        Set<String> actual = new HashSet<>();
        try (var paths = Files.walk(root)) {
            for (Path item : paths.toList()) {
                if (Files.isSymbolicLink(item)) throw new IOException("Symbolic integrated engine path is forbidden");
                if (Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS)) continue;
                SecureFileOperations.requireRegularFile(item, "Integrated engine file");
                String name = root.relativize(item).toString().replace('\\', '/');
                byte[] expected = bundle.files().get(name);
                if (expected == null || Files.size(item) != expected.length) {
                    throw new IOException("Integrated engine cache was modified; stop profiles and remove this cache: " + root);
                }
                try (InputStream input = Files.newInputStream(item, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Arrays.equals(input.readNBytes(expected.length + 1), expected)) {
                        throw new IOException("Integrated engine file differs from the bundled engine: " + name);
                    }
                }
                actual.add(name);
            }
        }
        if (!actual.equals(REQUIRED)) throw new IOException("Incomplete integrated engine cache: " + root);
    }

    private static String digest(byte[] bytes) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IOException("SHA-256 unavailable", ex); }
    }

    private record Bundle(Map<String, byte[]> files, String digest) { }
}
