package io.github.ibcmanager.runtime;

import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.security.FilePermissionHardener;
import io.github.ibcmanager.security.SecureFileOperations;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes StartIBC's first-start executable rename phase by canonical offline
 * application directory. Running sessions are not serialized after startup.
 */
public final class ApplicationStartCoordinator implements StartCoordinator {
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();
    private final Path lockDirectory;
    private final OfflineApplicationLayoutResolver layoutResolver;

    public ApplicationStartCoordinator(Path lockDirectory, OfflineApplicationLayoutResolver layoutResolver) {
        this.lockDirectory = Objects.requireNonNull(lockDirectory, "lockDirectory")
                .toAbsolutePath().normalize();
        this.layoutResolver = Objects.requireNonNull(layoutResolver, "layoutResolver");
    }

    @Override
    public Lease acquire(Profile profile, Duration timeout) throws IOException, InterruptedException {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("startup-lock timeout must be positive");
        }
        Path programPath = canonical(layoutResolver.resolve(profile).programPath());
        FilePermissionHardener.hardenDirectory(lockDirectory);
        Path lockPath = lockDirectory.resolve(hash(programPath.toString()) + ".lock");
        if (Files.isSymbolicLink(lockPath)) {
            throw new IOException("Application-start lock must not be a symbolic link: " + lockPath);
        }
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            SecureFileOperations.requireRegularFile(lockPath, "Application-start lock");
        }

        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(programPath, ignored -> new ReentrantLock(true));
        long deadline = System.nanoTime() + timeout.toNanos();
        if (!jvmLock.tryLock(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
            throw new IOException("Timed out waiting for another profile to finish starting from " + programPath);
        }

        FileChannel channel = null;
        FileLock fileLock = null;
        try {
            channel = FileChannel.open(lockPath, java.util.Set.of(StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
            FilePermissionHardener.hardenFile(lockPath);
            while (fileLock == null) {
                try {
                    fileLock = channel.tryLock();
                } catch (OverlappingFileLockException ignored) {
                    // Another lock holder in this JVM is about to release the OS lock.
                }
                if (fileLock != null) break;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IOException("Timed out waiting for another IBC Manager process to finish starting from "
                            + programPath);
                }
                Thread.sleep(Math.min(50L, Math.max(1L, remaining / 1_000_000L)));
            }
            channel.truncate(0);
            String owner = "pid=" + ProcessHandle.current().pid() + "\nprogram=" + programPath + "\n";
            channel.write(ByteBuffer.wrap(owner.getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
            return new CoordinatedLease(programPath, channel, fileLock, jvmLock);
        } catch (IOException | InterruptedException | RuntimeException ex) {
            if (fileLock != null) try { fileLock.release(); } catch (IOException cleanup) { ex.addSuppressed(cleanup); }
            if (channel != null) try { channel.close(); } catch (IOException cleanup) { ex.addSuppressed(cleanup); }
            jvmLock.unlock();
            cleanupJvmLock(programPath, jvmLock);
            throw ex;
        }
    }

    private static Path canonical(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        SecureFileOperations.requireDirectory(normalized, "Offline TWS/Gateway program directory");
        return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static String hash(String value) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("SHA-256 is unavailable", ex);
        }
    }

    private static void cleanupJvmLock(Path key, ReentrantLock lock) {
        if (!lock.isLocked() && !lock.hasQueuedThreads()) JVM_LOCKS.remove(key, lock);
    }

    private static final class CoordinatedLease implements Lease {
        private final Path key;
        private final FileChannel channel;
        private final FileLock fileLock;
        private final ReentrantLock jvmLock;
        private boolean closed;

        private CoordinatedLease(Path key, FileChannel channel, FileLock fileLock,
                ReentrantLock jvmLock) {
            this.key = key;
            this.channel = channel;
            this.fileLock = fileLock;
            this.jvmLock = jvmLock;
        }

        @Override public boolean coordinated() { return true; }

        @Override
        public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            IOException failure = null;
            try {
                if (fileLock.isValid()) fileLock.release();
            } catch (IOException ex) {
                failure = ex;
            }
            try {
                channel.close();
            } catch (IOException ex) {
                if (failure == null) failure = ex;
                else failure.addSuppressed(ex);
            } finally {
                jvmLock.unlock();
                cleanupJvmLock(key, jvmLock);
            }
            if (failure != null) throw failure;
        }
    }
}
