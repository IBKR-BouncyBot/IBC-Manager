package io.github.ibcmanager.app;

import io.github.ibcmanager.config.ManagedConfigService;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.TargetType;
import io.github.ibcmanager.runtime.ProcessIdentityStore;
import io.github.ibcmanager.security.BoundedFileReader;
import io.github.ibcmanager.security.FilePermissionHardener;
import io.github.ibcmanager.security.SecureFileOperations;
import io.github.ibcmanager.storage.AtomicFileWriter;
import io.github.ibcmanager.storage.ProfileCodec;
import io.github.ibcmanager.storage.ProfileRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Upgrade the existing default/custom Manager data directory without changing credential IDs. */
public final class LegacyUpgradeService {
    private final AppPaths paths;
    private final ProfileCodec codec = new ProfileCodec();

    public LegacyUpgradeService(AppPaths paths) { this.paths = paths; }

    public List<String> migrate() throws IOException {
        recoverInterrupted();
        ProfileRepository repository = new ProfileRepository(paths);
        ManagedConfigService configs = new ManagedConfigService(paths);
        List<String> messages = new ArrayList<>();
        for (Profile profile : repository.loadAll().profiles()) {
            Path file = paths.profileFile(profile.id());
            String original = BoundedFileReader.readString(file, StandardCharsets.UTF_8,
                    ProfileCodec.MAX_ENCODED_BYTES, "Legacy profile");
            if (codec.sourceFormatVersion(original) >= ProfileCodec.INTEGRATED_LEGACY_FORMAT) continue;
            if (new ProcessIdentityStore(paths).reattach(profile.id()).isPresent()) {
                throw new IOException("Stop the running 1.x profile and exit the old Manager before upgrading: "
                        + profile.name());
            }
            Path backup = paths.profileDirectory(profile.id()).resolve("upgrade-2.0.0-backup");
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                // A rollback backup is never overwritten by a later upgrade attempt.
                backup = paths.profileDirectory(profile.id()).resolve("upgrade-2.0.0-backup-" + UUID.randomUUID());
            }
            FilePermissionHardener.hardenDirectory(backup);
            write(backup.resolve("profile.properties"), original.getBytes(StandardCharsets.UTF_8));
            Path config = paths.profileConfig(profile.id());
            boolean configPresent = SecureFileOperations.exists(config);
            if (configPresent) write(backup.resolve("config.ini"),
                    BoundedFileReader.readBytes(config, ManagedConfigService.MAX_CONFIG_BYTES, "Legacy configuration"));
            Path marker = marker(profile.id());
            write(marker, (backup.getFileName() + "\n" + configPresent + "\n").getBytes(StandardCharsets.UTF_8));
            try {
                Profile updated = profile;
                if (profile.targetType() == TargetType.TWS) {
                    updated = profile.toBuilder().enabled(false).autoStart(false).build();
                } else {
                    updated = configs.synchronizeEditableProfile(profile);
                    configs.saveManagedConfig(updated, configs.loadManagedConfig(updated));
                }
                repository.save(updated);
                Files.delete(marker);
                messages.add("Upgraded " + profile.name() + "; retained profile ID and encrypted credential association");
            } catch (IOException | RuntimeException failure) {
                try { restore(profile.id(), marker); }
                catch (IOException rollback) { failure.addSuppressed(rollback); }
                throw new IOException("Could not upgrade profile " + profile.name()
                        + "; the original backup is retained at " + backup, failure);
            }
        }
        return List.copyOf(messages);
    }

    private Path marker(UUID id) { return paths.profileDirectory(id).resolve("upgrade-2.0.0.pending"); }

    private void recoverInterrupted() throws IOException {
        FilePermissionHardener.hardenDirectory(paths.profiles());
        try (var directories = Files.newDirectoryStream(paths.profiles())) {
            for (Path directory : directories) {
                if (!SecureFileOperations.isDirectory(directory)) continue;
                UUID id;
                try { id = UUID.fromString(directory.getFileName().toString()); }
                catch (IllegalArgumentException ignored) { continue; }
                if (SecureFileOperations.exists(marker(id))) {
                    if (new ProcessIdentityStore(paths).reattach(id).isPresent()) {
                        throw new IOException("Stop the managed profile before recovering an interrupted upgrade: " + id);
                    }
                    restore(id, marker(id));
                }
            }
        }
    }

    private void restore(UUID id, Path marker) throws IOException {
        String[] fields = BoundedFileReader.readString(marker, StandardCharsets.UTF_8, 4096,
                "Upgrade transaction").split("\n");
        if (fields.length != 2 || !fields[0].matches("upgrade-2\\.0\\.0-backup(?:-[a-f0-9-]{36})?")
                || !(fields[1].equals("true") || fields[1].equals("false"))) {
            throw new IOException("Invalid upgrade transaction; automatic recovery refused");
        }
        Path backup = paths.profileDirectory(id).resolve(fields[0]);
        SecureFileOperations.requireDirectory(backup, "Upgrade backup");
        byte[] original = BoundedFileReader.readBytes(backup.resolve("profile.properties"),
                ProfileCodec.MAX_ENCODED_BYTES, "Upgrade profile backup");
        Profile profile = codec.decode(new String(original, StandardCharsets.UTF_8));
        if (!profile.id().equals(id)) throw new IOException("Upgrade backup profile identity mismatch");
        if (fields[1].equals("true")) {
            write(paths.profileConfig(id), BoundedFileReader.readBytes(backup.resolve("config.ini"),
                    ManagedConfigService.MAX_CONFIG_BYTES, "Upgrade configuration backup"));
        } else {
            Path config = paths.profileConfig(id);
            if (SecureFileOperations.exists(config)) {
                SecureFileOperations.requireRegularFile(config, "Created upgrade configuration");
                Files.delete(config);
            }
        }
        write(paths.profileFile(id), original);
        Files.delete(marker);
    }

    private static void write(Path path, byte[] bytes) throws IOException {
        FilePermissionHardener.hardenDirectory(path.getParent());
        AtomicFileWriter.write(path, bytes, false);
        FilePermissionHardener.hardenFile(path);
    }
}
