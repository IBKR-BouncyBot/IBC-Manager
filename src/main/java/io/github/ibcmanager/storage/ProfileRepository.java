package io.github.ibcmanager.storage;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.security.BoundedFileReader;
import io.github.ibcmanager.security.FilePermissionHardener;
import io.github.ibcmanager.security.SecureFileOperations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
                    if (!SecureFileOperations.isDirectory(directory)) {
                        if (Files.isSymbolicLink(directory)) {
                            warnings.add("Ignored symbolic profile directory " + directory);
                        }
                        continue;
                    }
                    Path file = directory.resolve("profile.properties");
                    if (!SecureFileOperations.isRegularFile(file)) continue;
                    Path backup = directory.resolve("profile.properties.bak");
                    try {
                        Profile loaded = readValidated(file, directory);
                        if (!loadedIds.add(loaded.id())) {
                            warnings.add("Ignored duplicate profile ID " + loaded.id() + " in " + file);
                            continue;
                        }
                        profiles.add(loaded);
                    } catch (RuntimeException | IOException primaryFailure) {
                        if (!SecureFileOperations.isRegularFile(backup)) {
                            warnings.add("Could not load " + file + ": " + primaryFailure.getMessage());
                            continue;
                        }
                        try {
                            String recoveredText = readText(backup);
                            Profile recovered = validate(recoveredText, directory);
                            if (!loadedIds.add(recovered.id())) {
                                warnings.add("Ignored duplicate recovered profile ID " + recovered.id() + " in " + backup);
                                continue;
                            }
                            profiles.add(recovered);
                            // Preserve the validated backup bytes exactly. Re-encoding a legacy
                            // profile here would erase the source format before Managed Config
                            // can complete its one-time compatibility migration.
                            AtomicFileWriter.write(file, recoveredText.getBytes(StandardCharsets.UTF_8), false);
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
            if (bytes.length > ProfileCodec.MAX_ENCODED_BYTES) {
                throw new IOException("Profile data exceeds the " + ProfileCodec.MAX_ENCODED_BYTES + " byte safety limit");
            }
            Path target = paths.profileFile(profile.id());
            AtomicFileWriter.write(target, bytes, true);
            FilePermissionHardener.hardenFile(target);
            Path backup = target.resolveSibling(target.getFileName() + ".bak");
            if (SecureFileOperations.isRegularFile(backup)) FilePermissionHardener.hardenFile(backup);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void delete(UUID profileId) throws IOException {
        Objects.requireNonNull(profileId, "profileId");
        lock.writeLock().lock();
        try {
            SecureFileOperations.deleteTree(paths.profileDirectory(profileId));
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean stageDelete(UUID profileId, Path stagedDirectory) throws IOException {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(stagedDirectory, "stagedDirectory");
        lock.writeLock().lock();
        try {
            Path source = paths.profileDirectory(profileId);
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return false;
            SecureFileOperations.moveDirectory(source, stagedDirectory);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void restoreStagedDelete(UUID profileId, Path stagedDirectory) throws IOException {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(stagedDirectory, "stagedDirectory");
        lock.writeLock().lock();
        try {
            Path destination = paths.profileDirectory(profileId);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Cannot restore a staged profile because the destination exists: " + destination);
            }
            SecureFileOperations.moveDirectory(stagedDirectory, destination);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private Profile readValidated(Path file, Path directory) throws IOException {
        return validate(readText(file), directory);
    }

    private static String readText(Path file) throws IOException {
        return BoundedFileReader.readString(file, StandardCharsets.UTF_8,
                ProfileCodec.MAX_ENCODED_BYTES, "Profile file");
    }

    private Profile validate(String text, Path directory) {
        Profile profile = codec.decode(text);
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
