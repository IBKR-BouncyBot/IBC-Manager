package io.github.ibcmanager.storage;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.security.FilePermissionHardener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class ProfileRepository {
    private final AppPaths paths;
    private final ProfileCodec codec;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public ProfileRepository(AppPaths paths) {
        this(paths, new ProfileCodec());
    }

    public ProfileRepository(AppPaths paths, ProfileCodec codec) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public LoadResult loadAll() throws IOException {
        lock.writeLock().lock();
        try {
            FilePermissionHardener.hardenDirectory(paths.profiles());
            List<Profile> profiles = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            Set<UUID> loadedIds = new HashSet<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(paths.profiles())) {
                for (Path directory : stream) {
                    if (!Files.isDirectory(directory)) continue;
                    Path file = directory.resolve("profile.properties");
                    if (!Files.isRegularFile(file)) continue;
                    Path backup = directory.resolve("profile.properties.bak");
                    try {
                        Profile loaded = readValidated(file, directory);
                        if (!loadedIds.add(loaded.id())) {
                            warnings.add("Ignored duplicate profile ID " + loaded.id() + " in " + file);
                            continue;
                        }
                        profiles.add(loaded);
                    } catch (RuntimeException | IOException primaryFailure) {
                        if (!Files.isRegularFile(backup)) {
                            warnings.add("Could not load " + file + ": " + primaryFailure.getMessage());
                            continue;
                        }
                        try {
                            Profile recovered = readValidated(backup, directory);
                            if (!loadedIds.add(recovered.id())) {
                                warnings.add("Ignored duplicate recovered profile ID " + recovered.id() + " in " + backup);
                                continue;
                            }
                            profiles.add(recovered);
                            AtomicFileWriter.write(file, codec.encode(recovered).getBytes(StandardCharsets.UTF_8), false);
                            FilePermissionHardener.hardenFile(file);
                            warnings.add("Recovered profile '" + recovered.name() + "' from backup");
                        } catch (RuntimeException | IOException backupFailure) {
                            warnings.add("Could not load or recover " + file + ": " + backupFailure.getMessage());
                        }
                    }
                }
            }
            profiles.sort(Comparator.comparing(Profile::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(profile -> profile.id().toString()));
            return new LoadResult(List.copyOf(profiles), List.copyOf(warnings));
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void save(Profile profile) throws IOException {
        Objects.requireNonNull(profile, "profile");
        lock.writeLock().lock();
        try {
            Path directory = paths.profileDirectory(profile.id());
            FilePermissionHardener.hardenDirectory(directory);
            byte[] bytes = codec.encode(profile).getBytes(StandardCharsets.UTF_8);
            Path target = paths.profileFile(profile.id());
            AtomicFileWriter.write(target, bytes, true);
            FilePermissionHardener.hardenFile(target);
            Path backup = target.resolveSibling(target.getFileName() + ".bak");
            if (Files.isRegularFile(backup)) FilePermissionHardener.hardenFile(backup);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void delete(UUID profileId) throws IOException {
        Objects.requireNonNull(profileId, "profileId");
        lock.writeLock().lock();
        try {
            Path directory = paths.profileDirectory(profileId);
            if (!Files.exists(directory)) return;
            try (var walk = Files.walk(directory)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private Profile readValidated(Path file, Path directory) throws IOException {
        Profile profile = codec.decode(Files.readString(file, StandardCharsets.UTF_8));
        String directoryId = directory.getFileName().toString();
        if (!profile.id().toString().equalsIgnoreCase(directoryId)) {
            throw new IllegalArgumentException("Profile ID does not match its directory name");
        }
        return profile;
    }

    public record LoadResult(List<Profile> profiles, List<String> warnings) {
        public LoadResult {
            profiles = List.copyOf(profiles);
            warnings = List.copyOf(warnings);
        }
    }
}
