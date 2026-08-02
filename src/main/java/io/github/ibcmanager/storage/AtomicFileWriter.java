package io.github.ibcmanager.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

public final class AtomicFileWriter {
    private AtomicFileWriter() {
    }

    public static void write(Path target, byte[] content, boolean keepBackup) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(content, "content");
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) throw new IOException("Target has no parent directory: " + target);
        Files.createDirectories(parent);

        Path temp = parent.resolve("." + absolute.getFileName() + "." + UUID.randomUUID() + ".tmp");
        Path backup = parent.resolve(absolute.getFileName() + ".bak");
        boolean completed = false;
        try {
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }

            if (keepBackup && Files.isRegularFile(absolute)) {
                Path backupTemp = parent.resolve("." + absolute.getFileName() + ".bak." + UUID.randomUUID() + ".tmp");
                try {
                    Files.copy(absolute, backupTemp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    moveReplacing(backupTemp, backup);
                } finally {
                    Files.deleteIfExists(backupTemp);
                }
            }

            moveReplacing(temp, absolute);
            forceDirectory(parent);
            completed = true;
        } finally {
            if (!completed) Files.deleteIfExists(temp);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
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
