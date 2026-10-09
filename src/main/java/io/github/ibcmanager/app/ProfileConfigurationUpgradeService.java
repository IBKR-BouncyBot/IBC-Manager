package io.github.ibcmanager.app;

import io.github.ibcmanager.config.IbcConfigDocument;
import io.github.ibcmanager.config.ManagedConfigService;
import io.github.ibcmanager.config.ProfileConfiguration;
import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.TargetType;
import io.github.ibcmanager.runtime.ProcessIdentityStore;
import io.github.ibcmanager.security.BoundedFileReader;
import io.github.ibcmanager.security.CredentialStore;
import io.github.ibcmanager.security.CredentialStoreException;
import io.github.ibcmanager.security.FilePermissionHardener;
import io.github.ibcmanager.security.SecureFileOperations;
import io.github.ibcmanager.storage.AtomicFileWriter;
import io.github.ibcmanager.storage.ProfileCodec;
import io.github.ibcmanager.storage.ProfileRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

/** One-time, journalled transition from INI precedence to profile-owned configuration. */
public final class ProfileConfigurationUpgradeService {
    private static final String MARKER = "upgrade-profile-config.pending";
    private static final int MAX_CREDENTIAL_BYTES = 1024 * 1024;
    private final AppPaths paths;
    private final CredentialStore credentials;
    private final ManagedConfigService configs;
    private final ProfileRepository repository;

    public ProfileConfigurationUpgradeService(AppPaths paths, CredentialStore credentials) {
        this.paths = paths;
        this.credentials = credentials;
        configs = new ManagedConfigService(paths);
        repository = new ProfileRepository(paths);
    }

    public void migrate() throws IOException {
        FilePermissionHardener.hardenDirectory(paths.profiles());
        try (var directories = Files.newDirectoryStream(paths.profiles())) {
            for (Path directory : directories) {
                if (!SecureFileOperations.isDirectory(directory)) continue;
                UUID id;
                try { id = UUID.fromString(directory.getFileName().toString()); }
                catch (IllegalArgumentException ignored) { continue; }
                if (SecureFileOperations.exists(marker(id))) {
                    requireStopped(id);
                    restore(id);
                }
            }
        }
        for (Profile profile : repository.loadAll().profiles()) {
            if (!profile.profileOnlyConfiguration()) migrate(profile);
        }
    }

    private void migrate(Profile profile) throws IOException {
        UUID id = profile.id();
        requireStopped(id);
        Path backup = paths.profileDirectory(id).resolve("upgrade-profile-config-backup-" + UUID.randomUUID());
        FilePermissionHardener.hardenDirectory(backup);
        copy(paths.profileFile(id), backup.resolve("profile.properties"), ProfileCodec.MAX_ENCODED_BYTES);
        boolean configPresent = SecureFileOperations.exists(paths.profileConfig(id));
        boolean credentialPresent = SecureFileOperations.exists(paths.credentialFile(id));
        if (configPresent) copy(paths.profileConfig(id), backup.resolve("config.ini"), ManagedConfigService.MAX_CONFIG_BYTES);
        if (credentialPresent) copy(paths.credentialFile(id), backup.resolve("credential.dpapi"), MAX_CREDENTIAL_BYTES);
        write(marker(id), (backup.getFileName() + "\n" + configPresent + "\n" + credentialPresent + "\n")
                .getBytes(StandardCharsets.UTF_8));
        char[] password = null;
        boolean importedCredential = false;
        try {
            Profile updated;
            if (profile.targetType() != TargetType.GATEWAY) {
                updated = profile.toBuilder().profileOnlyConfiguration(true).enabled(false).autoStart(false)
                        .baseConfigPath(Path.of(""))
                        .credentialMode(profile.credentialMode() == CredentialMode.EXISTING_CONFIG
                                ? CredentialMode.MANUAL : profile.credentialMode()).build();
            } else {
                IbcConfigDocument effective = configs.loadRuntimeBase(profile).canonicalizedCopy();
                if (profile.credentialMode() == CredentialMode.EXISTING_CONFIG) {
                    ManagedConfigService.applyProfile(effective, profile);
                } else {
                    ManagedConfigService.applyProfileControlled(effective, profile);
                }
                io.github.ibcmanager.config.IbcCompatibilityPolicy.applySafeRuntimeDefaults(effective);
                String legacyErrors = java.util.stream.Stream.concat(
                                new io.github.ibcmanager.config.ConfigValueValidator().validateRuntimeConfig(effective).stream(),
                                io.github.ibcmanager.config.IbcCompatibilityPolicy.validateForProfile(profile, effective.activeSettings()).stream())
                        .filter(issue -> issue.severity() == io.github.ibcmanager.model.Severity.ERROR)
                        .map(issue -> issue.field() + ": " + issue.message())
                        .collect(java.util.stream.Collectors.joining("; "));
                if (!legacyErrors.isEmpty()) throw new IOException("Legacy configuration is invalid: " + legacyErrors);
                CredentialMode mode = profile.credentialMode();
                if (mode == CredentialMode.EXISTING_CONFIG) {
                    password = effective.get("IbPassword").orElse("").trim().toCharArray();
                    mode = password.length == 0 ? CredentialMode.MANUAL : CredentialMode.ENCRYPTED;
                    if (mode == CredentialMode.ENCRYPTED && !credentials.isAvailable()) {
                        throw new IOException("Import of the legacy password requires Windows credential storage");
                    }
                }
                updated = ProfileConfiguration.importLegacy(profile, effective, mode);
                ProfileConfiguration.document(updated); // validate before writing credentials/profile
                if (password != null && password.length > 0) {
                    credentials.save(id, password);
                    importedCredential = true;
                }
                configs.refreshManagedSettings(updated);
            }
            repository.save(updated);
            // The final profile and the generated projection form one committed transaction.
            Files.delete(marker(id));
        } catch (IOException | CredentialStoreException | RuntimeException failure) {
            if (importedCredential && !credentialPresent) {
                try { credentials.delete(id); }
                catch (CredentialStoreException rollback) { failure.addSuppressed(rollback); }
            }
            try { restore(id); }
            catch (IOException rollback) { failure.addSuppressed(rollback); }
            throw new IOException("Could not import the complete configuration for profile " + profile.name()
                    + ". Original files and backup are retained; no external source was changed.", failure);
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    private void requireStopped(UUID id) throws IOException {
        if (new ProcessIdentityStore(paths).reattach(id).isPresent()) {
            throw new IOException("Stop this profile in the old Manager before importing its configuration: " + id);
        }
    }

    private Path marker(UUID id) { return paths.profileDirectory(id).resolve(MARKER); }

    private void restore(UUID id) throws IOException {
        String[] fields = BoundedFileReader.readString(marker(id), StandardCharsets.UTF_8, 4096,
                "Profile configuration import journal").split("\n");
        if (fields.length != 3 || !fields[0].matches("upgrade-profile-config-backup-[a-f0-9-]{36}")
                || !booleanText(fields[1]) || !booleanText(fields[2])) {
            throw new IOException("Invalid configuration import journal; recovery refused");
        }
        Path backup = paths.profileDirectory(id).resolve(fields[0]);
        SecureFileOperations.requireDirectory(backup, "Configuration import backup");
        byte[] original = BoundedFileReader.readBytes(backup.resolve("profile.properties"),
                ProfileCodec.MAX_ENCODED_BYTES, "Original profile backup");
        Profile restored = new ProfileCodec().decode(new String(original, StandardCharsets.UTF_8));
        if (!restored.id().equals(id)) throw new IOException("Profile import backup identity mismatch");
        restoreFile(paths.profileConfig(id), backup.resolve("config.ini"), Boolean.parseBoolean(fields[1]),
                ManagedConfigService.MAX_CONFIG_BYTES);
        restoreFile(paths.credentialFile(id), backup.resolve("credential.dpapi"), Boolean.parseBoolean(fields[2]),
                MAX_CREDENTIAL_BYTES);
        write(paths.profileFile(id), original);
        Files.delete(marker(id));
    }

    private static boolean booleanText(String value) { return value.equals("true") || value.equals("false"); }

    private static void restoreFile(Path target, Path source, boolean existed, int limit) throws IOException {
        if (existed) copy(source, target, limit);
        else if (SecureFileOperations.exists(target)) {
            SecureFileOperations.requireRegularFile(target, "New migration file");
            Files.delete(target);
        }
    }

    private static void copy(Path source, Path target, int limit) throws IOException {
        byte[] bytes = BoundedFileReader.readBytes(source, limit, "Configuration migration input");
        try { write(target, bytes); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }

    private static void write(Path target, byte[] bytes) throws IOException {
        FilePermissionHardener.hardenDirectory(target.getParent());
        AtomicFileWriter.write(target, bytes, false);
        FilePermissionHardener.hardenFile(target);
    }
}
