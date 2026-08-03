package io.github.ibcmanager.app;

import io.github.ibcmanager.security.FilePermissionHardener;
import io.github.ibcmanager.security.SecureFileOperations;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SingleInstanceLock implements AutoCloseable {
    private static final Set<Path> JVM_RESERVATIONS = ConcurrentHashMap.newKeySet();

    private final Path lockPath;
    private final FileChannel channel;
    private final FileLock lock;
    private final AtomicBoolean closed = new AtomicBoolean();

    private SingleInstanceLock(Path lockPath, FileChannel channel, FileLock lock) {
        this.lockPath = lockPath;
        this.channel = channel;
        this.lock = lock;
    }

    public static SingleInstanceLock acquire(Path lockPath) throws IOException {
        Objects.requireNonNull(lockPath, "lockPath");
        Path normalized = lockPath.toAbsolutePath().normalize();
        if (!JVM_RESERVATIONS.add(normalized)) {
            throw new IOException("Another IBC Manager instance is already running");
        }
        boolean acquired = false;
        try {
            Path parent = normalized.getParent();
            if (parent != null) FilePermissionHardener.hardenDirectory(parent);
            if (Files.isSymbolicLink(normalized)) {
                throw new IOException("Single-instance lock must not be a symbolic link: " + normalized);
            }
            if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                SecureFileOperations.requireRegularFile(normalized, "Single-instance lock");
            }
            FileChannel channel = FileChannel.open(normalized,
                    java.util.Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS));
            try {
                FilePermissionHardener.hardenFile(normalized);
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    throw new IOException("Another IBC Manager instance is already using " + normalized);
                }
                channel.truncate(0);
                channel.write(java.nio.ByteBuffer.wrap(Long.toString(ProcessHandle.current().pid())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                channel.force(true);
                acquired = true;
                return new SingleInstanceLock(normalized, channel, lock);
            } catch (java.nio.channels.OverlappingFileLockException ex) {
                IOException failure = new IOException("Another IBC Manager instance is already running", ex);
                closeAfterFailure(channel, failure);
                throw failure;
            } catch (IOException | RuntimeException ex) {
                closeAfterFailure(channel, ex);
                throw ex;
            }
        } finally {
            if (!acquired) JVM_RESERVATIONS.remove(normalized);
        }
    }

    private static void closeAfterFailure(FileChannel channel, Throwable failure) {
        try {
            channel.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        IOException failure = null;
        try {
            if (lock.isValid()) lock.release();
        } catch (IOException ex) {
            failure = ex;
        }
        try {
            channel.close();
        } catch (IOException ex) {
            if (failure == null) failure = ex;
            else failure.addSuppressed(ex);
        } finally {
            JVM_RESERVATIONS.remove(lockPath);
        }
        if (failure != null) throw failure;
    }
}
