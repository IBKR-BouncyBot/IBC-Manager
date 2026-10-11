package io.github.ibcmanager.workaround;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.runtime.OfflineApplicationLayoutResolver;
import io.github.ibcmanager.security.BoundedFileReader;
import io.github.ibcmanager.security.CredentialStoreException;
import io.github.ibcmanager.security.FilePermissionHardener;
import io.github.ibcmanager.security.SecureFileOperations;
import io.github.ibcmanager.storage.AtomicFileWriter;
import io.github.ibcmanager.storage.ProfileCodec;
import io.github.ibcmanager.storage.ProfileRepository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Per-installation, reference-counted and journalled JBR/JavaFX setup. No system Java is replaced. */
public final class ManagedRuntimeService {
    public static final String CONSENT = "Install JBR 25.0.4-b508.27 and JavaFX for RDP startup?\n\n"
            + "On Save, Manager downloads this pinned JBR build and JavaFX 25, backs up and updates this installation's "
            + ".vmoptions, and selects JBR. Stop all profiles using it first.\n\n"
            + "Unavailable checksum or runtime checks are skipped with a warning; files are then not fully verified. "
            + "Detected corruption and unsafe downloads still stop setup.\n\n"
            + "Disabling restores previous settings and removes Manager-owned files when no profile uses them. "
            + "Manual installations are kept.\n\nContinue?";
    public static final String REMOVE_CONSENT = "Remove the managed workaround on Save?\n\n"
            + "Restore previous Java settings and remove Manager-owned files not used by other profiles. "
            + "Manual installations are kept.\n\nStop all profiles using this installation first. "
            + "The original RDP issue may return.\n\nContinue?";
    private static final String STATE = "state.properties";
    private static final String PENDING = "pending.properties";
    private static final String DELETION = "deletion.properties";
    private static final String ORIGINAL = "original.vmoptions";
    private static final String INSTALLED = "installed.vmoptions";
    private static final String HASHES = "payload.sha256";
    private final AppPaths paths;
    private final ProfileRepository profiles;
    private final PayloadInstaller installer;

    @FunctionalInterface public interface PayloadInstaller {
        RuntimeBundleInstaller.Installed install(Path stage, Consumer<String> progress, BooleanSupplier cancelled) throws IOException;
    }
    @FunctionalInterface public interface ProfileWriter { void save(Profile previous, Profile updated) throws IOException, CredentialStoreException; }
    @FunctionalInterface public interface Guard { void check(Profile profile) throws IOException; }

    public ManagedRuntimeService(AppPaths paths, ProfileRepository profiles) { this(paths, profiles, new RuntimeBundleInstaller()::install); }
    public ManagedRuntimeService(AppPaths paths, ProfileRepository profiles, PayloadInstaller installer) {
        this.paths = paths; this.profiles = profiles; this.installer = installer;
    }

    /** Runs under the GUI's configuration lease; download/verify precede edits and profile commit. */
    public synchronized Profile save(Profile previous, Profile desired, ProfileWriter writer, Guard guard,
            Consumer<String> progress, BooleanSupplier cancelled) throws IOException, CredentialStoreException {
        if (previous != null && previous.managedHeadlessRuntime()
                && (!previous.twsPath().equals(desired.twsPath()) || !previous.twsMajorVersion().equals(desired.twsMajorVersion())
                || previous.targetType() != desired.targetType())) {
            throw new IOException("Disable and save the managed runtime before changing the application/version/root");
        }
        boolean wasEnabled = previous != null && previous.managedHeadlessRuntime();
        if (!wasEnabled && !desired.managedHeadlessRuntime()) {
            Path ordinaryOptions = null;
            try { ordinaryOptions = new OfflineApplicationLayoutResolver().resolve(desired).vmOptionsPath(); }
            catch (IOException unconfigured) { /* Ordinary validation owns missing installations. */ }
            if (ordinaryOptions != null) {
                Path dir = directory(ordinaryOptions);
                if (SecureFileOperations.exists(dir.resolve(PENDING)) || SecureFileOperations.exists(dir.resolve(DELETION))) {
                    throw new IOException("Reopen Manager to finish interrupted runtime setup/removal before editing this profile");
                }
            }
            writer.save(previous, desired); return desired;
        }
        guard.check(desired);
        var coordinator = new io.github.ibcmanager.runtime.ApplicationStartCoordinator(
                Path.of(System.getProperty("java.io.tmpdir"), "IBCManager", "application-start-locks"),
                new OfflineApplicationLayoutResolver());
        try (var startLease = coordinator.acquire(desired, java.time.Duration.ofSeconds(30))) {
            if (!startLease.coordinated()) throw new IOException("Shared installation lock is unavailable");
            return saveCoordinated(previous, desired, writer, guard, progress, cancelled, wasEnabled);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new IOException("Runtime installation interrupted", failure);
        }
    }

    private Profile saveCoordinated(Profile previous, Profile desired, ProfileWriter writer, Guard guard,
            Consumer<String> progress, BooleanSupplier cancelled, boolean wasEnabled) throws IOException, CredentialStoreException {
        guard.check(desired);
        var layout = new OfflineApplicationLayoutResolver().resolve(desired);
        Path options = layout.vmOptionsPath();
        if (desired.managedHeadlessRuntime() && !desired.twsMajorVersion().matches("[0-9]{3,5}")) throw new IOException("Select a valid offline application version first");
        if (desired.managedHeadlessRuntime() && Integer.parseInt(desired.twsMajorVersion()) < 1048) throw new IOException("The managed Java 25 workaround is for 1048/1050 or newer installations");
        Path directory = directory(options);
        if (!wasEnabled && !desired.ibcJavaPath().toString().isBlank()
                && desired.ibcJavaPath().toAbsolutePath().normalize().startsWith(directory)) {
            throw new IOException("Clear the manually borrowed Manager runtime override before enabling managed ownership");
        }
        try (Lease lease = lock(options)) {
            if (!lease.valid()) throw new IOException("Could not lock runtime setup");
            recover(directory, options, guard);
            if (wasEnabled && desired.managedHeadlessRuntime()) {
                Properties state = readState(directory, options);
                requireOwner(state, desired);
                requirePinned(state);
                verifyPayload(directory);
                requireCompleteModules(directory);
                if (!desired.ibcJavaPath().equals(directory.resolve("payload/jbr/bin"))) throw new IOException("Managed Java override cannot be edited until the workaround is disabled");
                requireBlock(directory, options);
                writer.save(previous, desired);
                return desired;
            }
            byte[] beforeOptions = read(options);
            byte[] oldState = SecureFileOperations.exists(directory.resolve(STATE)) ? read(directory.resolve(STATE)) : new byte[0];
            boolean created = false;
            Properties state;
            if (oldState.length == 0) {
                if (!desired.managedHeadlessRuntime()) throw new IOException("Managed runtime ownership record is missing; automatic removal refused");
                if (SecureFileOperations.exists(directory)) throw new IOException("Unrecognized runtime folder exists; it was not overwritten: " + directory);
                Path stage = Files.createTempDirectory(layout.programPath(), ".ibc-runtime-stage-");
                try {
                    FilePermissionHardener.hardenDirectory(stage);
                    Path payload = stage.resolve("payload"); Files.createDirectory(payload);
                    Files.createDirectory(stage.resolve("native-cache"));
                    RuntimeBundleInstaller.Installed version = installer.install(payload, progress, cancelled);
                    if (!RuntimeCatalog.JBR_VERSION.equals(version.jbrVersion())) {
                        throw new IOException("Installer did not provide pinned JBR " + RuntimeCatalog.JBR_VERSION);
                    }
                    write(stage.resolve(HASHES), payloadManifest(payload));
                    write(stage.resolve(ORIGINAL), beforeOptions);
                    write(stage.resolve(INSTALLED), VmOptionsPatch.apply(beforeOptions, directory.resolve("payload/javafx")));
                    state = new Properties();
                    state.setProperty("format", "1"); state.setProperty("options", options.toString());
                    state.setProperty("jbr", version.jbrVersion()); state.setProperty("javafx", version.javafxVersion());
                    write(stage.resolve(STATE), encode(state));
                    guard.check(desired);
                    if (cancelled.getAsBoolean()) throw new IOException("Runtime setup cancelled before any options were changed");
                    if (!Arrays.equals(beforeOptions, read(options))) throw new IOException("VM options changed while downloading; setup refused");
                    SecureFileOperations.moveDirectory(stage, directory); created = true;
                } finally { if (SecureFileOperations.exists(stage)) SecureFileOperations.deleteTree(stage); }
            } else {
                state = readState(directory, options);
                verifyPayload(directory); validateOwnedTree(directory);
                if (owners(state) == 0 && Arrays.equals(beforeOptions, read(directory.resolve(ORIGINAL)))) {
                    // Interrupted installation before journalling/profile save: reuse only the verified orphan.
                    created = true; oldState = new byte[0];
                } else requireBlock(directory, options);
            }
            String key = owner(desired.id());
            Profile effective;
            byte[] afterOptions;
            Properties next = copy(state);
            if (desired.managedHeadlessRuntime()) {
                requirePinned(state);
                requireCompleteModules(directory);
                if (state.containsKey(key)) {
                    if (created) safeDelete(directory);
                    throw new IOException("This profile already owns a managed runtime; reload its saved configuration");
                }
                next.setProperty(key, Base64.getEncoder().encodeToString(desired.ibcJavaPath().toString().getBytes(StandardCharsets.UTF_8)));
                effective = desired.toBuilder().ibcJavaPath(directory.resolve("payload/jbr/bin")).build();
                afterOptions = created ? read(directory.resolve(INSTALLED)) : beforeOptions;
            } else {
                requireOwner(state, desired);
                Path previousJava;
                try { previousJava = Path.of(new String(Base64.getDecoder().decode(state.getProperty(key)), StandardCharsets.UTF_8)); }
                catch (IllegalArgumentException ex) { throw new IOException("Invalid previous runtime record", ex); }
                if (!previousJava.toString().isBlank() && previousJava.toAbsolutePath().normalize().startsWith(directory)) {
                    throw new IOException("Previous override references the managed runtime itself; automatic removal refused");
                }
                next.remove(key);
                effective = desired.toBuilder().ibcJavaPath(previousJava).build();
                afterOptions = owners(next) == 0 ? VmOptionsPatch.remove(read(directory.resolve(ORIGINAL)), read(directory.resolve(INSTALLED)), beforeOptions) : beforeOptions;
            }
            boolean removeDirectory = owners(next) == 0;
            if (removeDirectory) {
                for (Profile other : profiles.loadAll().profiles()) {
                    if (!other.id().equals(desired.id()) && !other.ibcJavaPath().toString().isBlank()
                            && other.ibcJavaPath().toAbsolutePath().normalize().startsWith(directory)) {
                        throw new IOException("Another profile still references the managed Java directory: " + other.name());
                    }
                }
            }
            Properties transaction = new Properties();
            transaction.setProperty("format", "1"); transaction.setProperty("ownerRoot", paths.root().toString());
            transaction.setProperty("previousProfile", previous == null ? "" : Base64.getEncoder().encodeToString(new ProfileCodec().encode(previous).getBytes(StandardCharsets.UTF_8)));
            transaction.setProperty("expectedProfile", Base64.getEncoder().encodeToString(new ProfileCodec().encode(effective).getBytes(StandardCharsets.UTF_8)));
            transaction.setProperty("previousState", Base64.getEncoder().encodeToString(oldState));
            transaction.setProperty("nextState", Base64.getEncoder().encodeToString(encode(next)));
            transaction.setProperty("beforeOptions", Base64.getEncoder().encodeToString(beforeOptions));
            transaction.setProperty("afterOptions", Base64.getEncoder().encodeToString(afterOptions));
            transaction.setProperty("remove", Boolean.toString(removeDirectory));
            boolean profileSaved = false;
            try {
                guard.check(desired);
                if (cancelled.getAsBoolean()) throw new IOException("Runtime setup cancelled before commit");
                if (!Arrays.equals(beforeOptions, read(options))) throw new IOException("VM options changed; setup refused");
                progress.accept("Committing runtime/profile changes...");
                write(directory.resolve(PENDING), encode(transaction));
                // Journal is durable before either externally visible write.
                if (!Arrays.equals(beforeOptions, afterOptions)) AtomicFileWriter.write(options, afterOptions, false);
                writer.save(previous, effective);
                profileSaved = true;
                finish(directory, options, transaction, true);
                progress.accept(removeDirectory ? "Managed runtime removed; previous options restored." : "Profile runtime configuration saved.");
                return effective;
            } catch (IOException | CredentialStoreException | RuntimeException failure) {
                try {
                    if (!profileSaved) {
                        if (SecureFileOperations.exists(directory.resolve(PENDING))) {
                            // A profile writer can fail AFTER its atomic rename (for example ACL
                            // finalization or an unsuccessful rollback). Never delete a runtime
                            // still referenced by the committed profile merely because save threw.
                            Profile stored = readStoredProfile(effective.id());
                            if (effective.equals(stored)) profileSaved = true;
                            else if (java.util.Objects.equals(previous, stored)) finish(directory, options, transaction, false);
                            else throw new IOException("Profile state is uncertain after failed Save; runtime journal retained for review");
                        } else if (created) safeDelete(directory);
                    }
                } catch (IOException rollback) { failure.addSuppressed(rollback); }
                if (profileSaved) throw new IOException("Profile was saved, but runtime finalization needs recovery. Keep its runtime files; reopen Manager before starting.", failure);
                throw failure;
            }
        }
    }

    /** Read the exact current profile without silently recovering a backup during a commit decision. */
    private Profile readStoredProfile(UUID id) throws IOException {
        Path file = paths.profileFile(id);
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            Profile stored = new ProfileCodec().decode(BoundedFileReader.readString(file,
                    StandardCharsets.UTF_8, ProfileCodec.MAX_ENCODED_BYTES, "Runtime transaction profile"));
            if (!id.equals(stored.id())) throw new IOException("Runtime transaction profile identity mismatch");
            return stored;
        } catch (IllegalArgumentException error) { throw new IOException("Runtime transaction profile is unreadable", error); }
    }

    /** Finish or undo interrupted work based on the atomically saved profile, never on a guessed phase. */
    public synchronized void recoverPending() throws IOException {
        for (Profile profile : profiles.loadAll().profiles()) {
            Path options;
            try { options = new OfflineApplicationLayoutResolver().resolve(profile).vmOptionsPath(); }
            catch (IOException failure) {
                if (profile.managedHeadlessRuntime()) throw failure;
                continue; // Normal validation reports an unconfigured ordinary installation.
            }
            Path dir = directory(options);
            if (!SecureFileOperations.exists(dir.resolve(PENDING)) && !SecureFileOperations.exists(dir.resolve(DELETION))) continue;
            try (Lease lease = lock(options)) {
                if (!lease.valid()) throw new IOException("Runtime setup is busy");
                if (SecureFileOperations.exists(dir.resolve(DELETION)) && !SecureFileOperations.exists(dir.resolve(PENDING))) {
                    if (profile.managedHeadlessRuntime() || new io.github.ibcmanager.runtime.ProcessIdentityStore(paths).reattach(profile.id()).isPresent()) {
                        throw new IOException("Stop the session before completing managed runtime removal");
                    }
                }
                recover(dir, options, expected -> {
                    for (Profile candidate : profiles.loadAll().profiles()) {
                        try {
                            if (new OfflineApplicationLayoutResolver().resolve(candidate).vmOptionsPath().equals(options)
                                    && new io.github.ibcmanager.runtime.ProcessIdentityStore(paths).reattach(candidate.id()).isPresent()) {
                                throw new IOException("Stop all sessions using the installation before runtime recovery: " + candidate.name());
                            }
                        } catch (IOException error) {
                            if (candidate.id().equals(expected.id()) || new io.github.ibcmanager.runtime.ProcessIdentityStore(paths).reattach(candidate.id()).isPresent()) throw error;
                        }
                    }
                });
            }
        }
    }
    private void recover(Path directory, Path options, Guard guard) throws IOException {
        if (!SecureFileOperations.exists(directory.resolve(PENDING))) {
            if (SecureFileOperations.exists(directory.resolve(DELETION))) {
                if (VmOptionsPatch.decode(read(options)).contains(VmOptionsPatch.BEGIN)) throw new IOException("Runtime removal is incomplete but VM options still reference it");
                safeDelete(directory);
            }
            return;
        }
        Properties tx = properties(read(directory.resolve(PENDING)));
        if (!"1".equals(tx.getProperty("format")) || !paths.root().toString().equals(tx.getProperty("ownerRoot"))) throw new IOException("Pending runtime setup belongs to another data directory; automatic recovery refused");
        Profile expected;
        try { expected = new ProfileCodec().decode(new String(Base64.getDecoder().decode(tx.getProperty("expectedProfile")), StandardCharsets.UTF_8)); }
        catch (RuntimeException ex) { throw new IOException("Invalid runtime setup journal", ex); }
        if (!new OfflineApplicationLayoutResolver().resolve(expected).vmOptionsPath().equals(options)) throw new IOException("Runtime journal installation mismatch");
        guard.check(expected);
        Profile current = readStoredProfile(expected.id());
        boolean committed = expected.equals(current);
        if (!committed) {
            Profile before;
            try {
                String encoded = tx.getProperty("previousProfile");
                if (encoded == null) throw new IOException("Incomplete runtime setup journal");
                before = encoded.isEmpty() ? null : new ProfileCodec().decode(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
            } catch (RuntimeException error) { throw new IOException("Invalid previous profile in runtime journal", error); }
            if (!java.util.Objects.equals(before, current)) throw new IOException("Profile changed after interrupted runtime setup; automatic rollback refused");
        }
        finish(directory, options, tx, committed);
    }
    private static void finish(Path directory, Path options, Properties tx, boolean committed) throws IOException {
        try {
            byte[] before = Base64.getDecoder().decode(tx.getProperty("beforeOptions"));
            byte[] after = Base64.getDecoder().decode(tx.getProperty("afterOptions"));
            byte[] current = read(options);
            if (!Arrays.equals(current, before) && !Arrays.equals(current, after)) throw new IOException("VM options changed during interrupted setup; no rollback or deletion was performed");
            byte[] wanted = committed ? after : before;
            if (!Arrays.equals(current, wanted)) AtomicFileWriter.write(options, wanted, false);
            byte[] state = Base64.getDecoder().decode(tx.getProperty(committed ? "nextState" : "previousState"));
            boolean remove = committed ? "true".equals(tx.getProperty("remove")) : state.length == 0;
            if (remove) safeDelete(directory);
            else { write(directory.resolve(STATE), state); Files.deleteIfExists(directory.resolve(PENDING)); }
        } catch (IllegalArgumentException | NullPointerException ex) { throw new IOException("Invalid runtime transaction; no arbitrary paths were used", ex); }
    }

    public static void checkBeforeStart(Profile profile, AppPaths paths) throws IOException {
        Path options = new OfflineApplicationLayoutResolver().resolve(profile).vmOptionsPath();
        Path dir = directory(options);
        if (!profile.managedHeadlessRuntime() && !SecureFileOperations.exists(dir)) return;
        try (Lease lease = lock(options)) {
            if (!lease.valid()) throw new IOException("Runtime setup is in progress");
            if (SecureFileOperations.exists(dir.resolve(PENDING)) || SecureFileOperations.exists(dir.resolve(DELETION))) throw new IOException("An interrupted runtime setup/removal must be recovered before launch");
            if (profile.managedHeadlessRuntime()) {
                Properties state = readState(dir, options);
                if (!state.containsKey(owner(paths, profile.id()))) {
                    throw new IOException("This profile has no managed runtime ownership record");
                }
                requirePinned(state);
                requireBlock(dir, options); verifyPayload(dir); requireCompleteModules(dir); verifyNativeCache(dir);
                if (!profile.ibcJavaPath().equals(dir.resolve("payload/jbr/bin"))) throw new IOException("Managed Java runtime path mismatch");
                SecureFileOperations.requireRegularFile(profile.ibcJavaPath().resolve("java.exe"), "Managed Java launcher");
            }
        }
    }
    public static Path directory(Path options) {
        String name = options.getFileName().toString().replace(".vmoptions", "");
        if (!name.equals("ibgateway") && !name.equals("tws")) throw new IllegalArgumentException("Unsupported options filename");
        return options.getParent().resolve(".ibc-manager-jbr25-" + name);
    }
    private static void requirePinned(Properties state) throws IOException {
        if (!RuntimeCatalog.JBR_VERSION.equals(state.getProperty("jbr"))) {
            throw new IOException("Managed JBR must be " + RuntimeCatalog.JBR_VERSION
                    + ". Disable and Save the workaround on all profiles sharing this installation, then enable it again. "
                    + "Existing runtime files were not changed.");
        }
    }
    private static void requireBlock(Path directory, Path options) throws IOException {
        // Removal is also a strict test of ownership-block integrity; do not actually modify it.
        VmOptionsPatch.remove(read(directory.resolve(ORIGINAL)), read(directory.resolve(INSTALLED)), read(options));
    }
    private static Properties readState(Path directory, Path options) throws IOException {
        SecureFileOperations.requireDirectory(directory, "Managed runtime directory");
        Properties state = properties(read(directory.resolve(STATE)));
        if (!"1".equals(state.getProperty("format")) || !options.toString().equals(state.getProperty("options"))) throw new IOException("Managed runtime ownership record mismatch");
        return state;
    }
    private void requireOwner(Properties state, Profile profile) throws IOException {
        if (!state.containsKey(owner(profile.id()))) throw new IOException("This profile does not own the managed runtime; it cannot remove it");
    }
    private String owner(UUID id) { return owner(paths, id); }
    private static String owner(AppPaths paths, UUID id) {
        try { return "owner." + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(paths.root().toString().getBytes(StandardCharsets.UTF_8))).substring(0, 32) + "." + id; }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static int owners(Properties state) { return (int) state.stringPropertyNames().stream().filter(k -> k.startsWith("owner.")).count(); }
    private static Properties copy(Properties state) { Properties result = new Properties(); result.putAll(state); return result; }
    private static Properties properties(byte[] bytes) throws IOException { Properties p = new Properties(); p.load(new ByteArrayInputStream(bytes)); return p; }
    private static byte[] encode(Properties properties) {
        StringBuilder result = new StringBuilder();
        properties.stringPropertyNames().stream().sorted().forEach(key -> result.append(escape(key)).append('=').append(escape(properties.getProperty(key))).append('\n'));
        return result.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
    private static String escape(String text) {
        StringBuilder result = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c < 33 || c > 126) result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int)c));
            else { if ("\\=:#!".indexOf(c) >= 0) result.append('\\'); result.append(c); }
        }
        return result.toString();
    }
    private static byte[] read(Path file) throws IOException { return BoundedFileReader.readBytes(file, 8 * 1024 * 1024, "Managed runtime metadata/options"); }
    private static void write(Path file, byte[] bytes) throws IOException { AtomicFileWriter.write(file, bytes, false); FilePermissionHardener.hardenFile(file); }
    private static byte[] payloadManifest(Path root) throws IOException {
        SecureFileOperations.requireDirectory(root, "Runtime payload");
        StringBuilder result = new StringBuilder();
        try (var stream = Files.walk(root)) {
            for (Path file : stream.sorted().toList()) {
                if (Files.isSymbolicLink(file)) throw new IOException("Runtime payload must not contain symbolic links");
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) result.append(RuntimeBundleInstaller.hash(file, "SHA-256")).append("  ").append(root.relativize(file).toString().replace('\\', '/')).append('\n');
                else SecureFileOperations.requireDirectory(file, "Runtime payload directory");
            }
        }
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void requireCompleteModules(Path directory) throws IOException {
        for (String module : RuntimeCatalog.FX_MODULES) {
            String file = RuntimeCatalog.moduleName(module) + ".jar";
            if (!SecureFileOperations.isRegularFile(directory.resolve("payload/javafx").resolve(file))) {
                throw new IOException("Managed JavaFX installation is missing " + file
                        + ". Stop profiles using this installation. Disable and Save the workaround on all its profiles, "
                        + "then enable and Save it again to install the complete runtime. Do not edit payload hashes.");
            }
        }
    }

    private static void verifyPayload(Path dir) throws IOException {
        if (!Arrays.equals(read(dir.resolve(HASHES)), payloadManifest(dir.resolve("payload")))) throw new IOException("Managed runtime was changed; it will not be executed or deleted automatically");
    }
    /** Keep a bounded hash receipt until deletion finishes, including across locked-file failures. */
    private static void safeDelete(Path dir) throws IOException {
        Properties receipt;
        if (!SecureFileOperations.exists(dir.resolve(DELETION))) {
            verifyPayload(dir); validateOwnedTree(dir);
            receipt = new Properties();
            receipt.setProperty("format", "1"); receipt.setProperty("directory", dir.toString());
            try (var stream = Files.walk(dir)) {
                for (Path file : stream.toList()) {
                    if (file.equals(dir)) continue;
                    String name = dir.relativize(file).toString().replace('\\', '/');
                    if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) receipt.setProperty("dir." + name, "true");
                    else receipt.setProperty("file." + name, RuntimeBundleInstaller.hash(file, "SHA-256"));
                }
            }
            write(dir.resolve(DELETION), encode(receipt));
        } else receipt = properties(read(dir.resolve(DELETION)));
        if (!"1".equals(receipt.getProperty("format")) || !dir.toString().equals(receipt.getProperty("directory"))) {
            throw new IOException("Managed runtime deletion receipt is invalid; no files removed");
        }
        java.util.Set<Path> allowedDirs = new java.util.HashSet<>(); allowedDirs.add(dir);
        for (String key : receipt.stringPropertyNames()) {
            if (!key.startsWith("file.") && !key.startsWith("dir.")) continue;
            boolean isDirectory = key.startsWith("dir.");
            String name = key.substring(isDirectory ? 4 : 5);
            Path file = dir.resolve(name).normalize();
            if (name.contains("\\") || Path.of(name).isAbsolute() || name.contains("..") || !file.startsWith(dir)
                    || !(isDirectory ? "true".equals(receipt.getProperty(key)) : receipt.getProperty(key).matches("[0-9a-f]{64}"))) throw new IOException("Unsafe runtime deletion receipt");
            if (isDirectory) allowedDirs.add(file);
            for (Path parent = file.getParent(); parent != null && parent.startsWith(dir); parent = parent.getParent()) allowedDirs.add(parent);
        }
        java.util.List<Path> remaining;
        try (var stream = Files.walk(dir)) { remaining = stream.sorted(java.util.Comparator.reverseOrder()).toList(); }
        for (Path file : remaining) {
            if (Files.isSymbolicLink(file)) throw new IOException("Runtime removal refuses symbolic paths");
            if (file.equals(dir.resolve(DELETION))) continue;
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                if (!allowedDirs.contains(file)) throw new IOException("Unexpected directory during runtime removal: " + file.getFileName());
            } else {
                String expected = receipt.getProperty("file." + dir.relativize(file).toString().replace('\\', '/'));
                if (expected == null || !expected.equals(RuntimeBundleInstaller.hash(file, "SHA-256"))) throw new IOException("Changed file during runtime removal; preserved: " + file.getFileName());
            }
        }
        // Keep the journal and receipt to the end. If a payload/DLL is locked, the next
        // startup verifies the remaining subset against the receipt before retrying.
        for (Path file : remaining) {
            if (!file.equals(dir.resolve(PENDING)) && !file.equals(dir.resolve(DELETION))
                    && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) Files.delete(file);
        }
        for (Path path : remaining) if (!path.equals(dir) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.delete(path);
        Files.deleteIfExists(dir.resolve(PENDING));
        Files.delete(dir.resolve(DELETION));
        Files.delete(dir);
    }
    private static void validateOwnedTree(Path dir) throws IOException {
        verifyNativeCache(dir);
        try (var children = Files.list(dir)) {
            for (Path child : children.toList()) {
                if (!List.of("payload", "native-cache", STATE, PENDING, DELETION, ORIGINAL, INSTALLED, HASHES).contains(child.getFileName().toString()) || Files.isSymbolicLink(child)) throw new IOException("Unexpected file in managed runtime folder; automatic removal refused");
            }
        }
    }
    private static void verifyNativeCache(Path dir) throws IOException {
        Path cache = dir.resolve("native-cache");
        if (!SecureFileOperations.exists(cache)) return;
        SecureFileOperations.requireDirectory(cache, "Managed JavaFX native cache");
        try (var files = Files.list(cache)) {
            for (Path file : files.toList()) {
                SecureFileOperations.requireRegularFile(file, "Managed JavaFX native cache file");
                String name = file.getFileName().toString();
                if (name.equals(".lock") && Files.size(file) == 0) continue;
                if (!name.matches("[A-Za-z0-9_.-]+\\.dll") || Files.size(file) > 512L * 1024 * 1024) {
                    throw new IOException("Unexpected JavaFX cache file; no automatic deletion: " + name);
                }
                boolean matches = false;
                for (String module : RuntimeCatalog.FX_MODULES) {
                    // jdk.jsobject is Java-only and did not exist in legacy seven-JAR
                    // installations. Their known native cache must remain removable.
                    if (module.equals("jsobject")) continue;
                    Path jar = dir.resolve("payload/javafx/" + RuntimeCatalog.moduleName(module) + ".jar");
                    try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
                        var entry = zip.getEntry(name);
                        if (entry != null) {
                            try (var input = zip.getInputStream(entry)) {
                                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                                byte[] buffer = new byte[65536]; long count = 0; int size;
                                while ((size = input.read(buffer)) >= 0) {
                                    count += size;
                                    if (count > 512L * 1024 * 1024) throw new IOException("JavaFX native cache exceeds size limit");
                                    digest.update(buffer, 0, size);
                                }
                                matches |= HexFormat.of().formatHex(digest.digest()).equals(RuntimeBundleInstaller.hash(file, "SHA-256"));
                            } catch (NoSuchAlgorithmException failure) { throw new IOException(failure); }
                        }
                    }
                }
                if (!matches) throw new IOException("Modified or unowned JavaFX cache file; no automatic deletion: " + name);
            }
        }
    }
    private static Lease lock(Path options) throws IOException {
        Path file = options.resolveSibling("." + options.getFileName() + ".ibc-runtime.lock");
        SecureFileOperations.ensureDirectory(file.getParent());
        if (SecureFileOperations.exists(file)) SecureFileOperations.requireRegularFile(file, "Runtime installation lock");
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Another Manager is changing this installation's runtime");
            return new Lease(channel, lock);
        } catch (IOException | RuntimeException ex) { channel.close(); throw new IOException("Runtime installation is busy", ex); }
    }
    private record Lease(FileChannel channel, FileLock lock) implements AutoCloseable {
        boolean valid() { return lock.isValid(); }
        @Override public void close() throws IOException { try { lock.close(); } finally { channel.close(); } }
    }
}
