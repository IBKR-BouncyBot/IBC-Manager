package io.github.ibcmanager.storage;

import io.github.ibcmanager.security.SecureFileOperations;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

/** Writes an application-owned file through a same-directory atomic replacement. */
public final class AtomicFileWriter {
    private AtomicFileWriter() { }

    public static void write(Path target, byte[] content, boolean keepBackup) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(content, "content");
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) throw new IOException("Target has no parent directory: " + target);
        SecureFileOperations.ensureDirectory(parent);
        rejectUnsafeExistingTarget(absolute, "Target");

        Path temp = parent.resolve("." + absolute.getFileName() + "." + UUID.randomUUID() + ".tmp");
        Path backup = parent.resolve(absolute.getFileName() + ".bak");
        boolean completed = false;
        try {
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }

            if (keepBackup && Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
                SecureFileOperations.requireRegularFile(absolute, "File being backed up");
                rejectUnsafeExistingTarget(backup, "Backup target");
                Path backupTemp = parent.resolve("." + absolute.getFileName() + ".bak."
                        + UUID.randomUUID() + ".tmp");
                try {
                    Files.copy(absolute, backupTemp, StandardCopyOption.COPY_ATTRIBUTES);
                    SecureFileOperations.requireRegularFile(backupTemp, "Temporary backup");
                    moveReplacing(backupTemp, backup);
                } finally {
                    Files.deleteIfExists(backupTemp);
                }
            }

            rejectUnsafeExistingTarget(absolute, "Target");
            moveReplacing(temp, absolute);
            forceDirectory(parent);
            completed = true;
        } finally {
            if (!completed) Files.deleteIfExists(temp);
        }
    }

    private static void rejectUnsafeExistingTarget(Path path, String description) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        SecureFileOperations.requireRegularFile(path, description);
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        if (Files.isSymbolicLink(target)) {
            throw new IOException("Refusing to replace a symbolic link: " + target);
        }
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Directory fsync is not supported on every platform or file system.
        }
    }
}
