package io.github.ibcmanager.security;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * File-system helpers for application-owned state.
 *
 * <p>The methods deliberately do not follow symbolic links. IBC Manager stores
 * credentials, runtime configuration, process identities, and profile files in
 * directories that are expected to be owned by the current user. Following a
 * substituted link in those locations could disclose or overwrite unrelated
 * files.</p>
 */
public final class SecureFileOperations {
    private SecureFileOperations() { }

    public static boolean exists(Path path) {
        return path != null && Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    public static boolean isRegularFile(Path path) {
        if (path == null) return false;
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile() && !attributes.isSymbolicLink();
        } catch (IOException | SecurityException ex) {
            return false;
        }
    }

    public static boolean isDirectory(Path path) {
        if (path == null) return false;
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isDirectory() && !attributes.isSymbolicLink();
        } catch (IOException | SecurityException ex) {
            return false;
        }
    }

    public static void requireRegularFile(Path path, String description) throws IOException {
        Objects.requireNonNull(path, "path");
        BasicFileAttributes attributes = readAttributes(path, description);
        if (attributes.isSymbolicLink()) {
            throw new IOException(description + " must not be a symbolic link: " + path);
        }
        if (!attributes.isRegularFile()) {
            throw new IOException(description + " is not a regular file: " + path);
        }
    }

    public static void requireDirectory(Path path, String description) throws IOException {
        Objects.requireNonNull(path, "path");
        BasicFileAttributes attributes = readAttributes(path, description);
        if (attributes.isSymbolicLink()) {
            throw new IOException(description + " must not be a symbolic link: " + path);
        }
        if (!attributes.isDirectory()) {
            throw new IOException(description + " is not a directory: " + path);
        }
    }

    public static void ensureDirectory(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Path absolute = directory.toAbsolutePath().normalize();
        Path root = absolute.getRoot();
        if (root == null) throw new IOException("Directory has no file-system root: " + directory);

        List<Path> chain = new ArrayList<>();
        Path current = root;
        for (Path segment : root.relativize(absolute)) {
            current = current.resolve(segment);
            chain.add(current);
        }
        for (Path candidate : chain) {
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(
                        candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink()) {
                    throw new IOException("Application directory path contains a symbolic link: " + candidate);
                }
                if (!attributes.isDirectory()) {
                    throw new IOException("Application directory path contains a non-directory: " + candidate);
                }
            } else {
                try {
                    Files.createDirectory(candidate);
                } catch (java.nio.file.FileAlreadyExistsException race) {
                    BasicFileAttributes attributes = Files.readAttributes(
                            candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                        throw new IOException("Application directory was replaced while it was being created: "
                                + candidate, race);
                    }
                }
            }
        }
    }

    public static void rejectSymbolicLink(Path path, String description) throws IOException {
        Objects.requireNonNull(path, "path");
        if (Files.isSymbolicLink(path)) {
            throw new IOException(description + " must not be a symbolic link: " + path);
        }
    }

    public static void moveDirectory(Path source, Path destination) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        requireDirectory(source, "Source directory");
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Destination already exists: " + destination);
        }
        Path parent = destination.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("Destination has no parent directory: " + destination);
        ensureDirectory(parent);
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, destination);
        }
    }

    public static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(root);
            return;
        }
        IOException[] failure = new IOException[1];
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ex) {
                    addFailure(failure, ex);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException ex) {
                addFailure(failure, ex);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException ex) {
                if (ex != null) addFailure(failure, ex);
                try {
                    Files.deleteIfExists(directory);
                } catch (IOException deleteFailure) {
                    addFailure(failure, deleteFailure);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (failure[0] != null) throw failure[0];
    }

    private static BasicFileAttributes readAttributes(Path path, String description) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (java.nio.file.NoSuchFileException ex) {
            throw new IOException(description + " does not exist: " + path, ex);
        }
    }

    private static void addFailure(IOException[] target, IOException failure) {
        if (target[0] == null) target[0] = failure;
        else target[0].addSuppressed(failure);
    }
}
