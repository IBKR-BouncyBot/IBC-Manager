package io.github.ibcmanager.config;

import io.github.ibcmanager.security.BoundedFileReader;
import io.github.ibcmanager.security.SecureFileOperations;
import io.github.ibcmanager.storage.AtomicFileWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

public final class RuntimeConfigLease implements AutoCloseable {
    private final Path path;
    private boolean closed;

    public RuntimeConfigLease(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    public Path path() { return path; }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            closed = true;
            return;
        }

        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path);
            closed = true;
            throw new IOException("Removed an unsafe symbolic runtime-configuration link: " + path);
        }

        IOException scrubFailure = null;
        if (SecureFileOperations.isRegularFile(path)) {
            try {
                byte[] bytes = BoundedFileReader.readBytes(path,
                        ManagedConfigService.MAX_CONFIG_BYTES, "Runtime IBC configuration");
                try {
                    IbcConfigDocument document = IbcConfigDocument.parseBytes(bytes);
                    if (!document.formattingMatchesIbcSemantics()) {
                        // Cleanup must use the same authoritative full-file Properties semantics
                        // as IBC, even for a stale runtime file produced by an older release.
                        document = document.canonicalizedCopy();
                    }
                    for (String key : IbcConfigSchema.sensitiveKeys()) document.set(key, "");
                    AtomicFileWriter.write(path, document.toIbcBytes(), false);
                } finally {
                    java.util.Arrays.fill(bytes, (byte) 0);
                }
            } catch (IOException | RuntimeException ex) {
                scrubFailure = ex instanceof IOException io ? io
                        : new IOException("Could not parse runtime configuration while scrubbing it", ex);
            }
        } else {
            scrubFailure = new IOException("Runtime configuration is not a regular file: " + path);
        }

        IOException deleteFailure = null;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            deleteFailure = ex;
        }

        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            closed = true;
            if (scrubFailure != null) throw scrubFailure;
            return;
        }

        if (deleteFailure != null) {
            if (scrubFailure != null) deleteFailure.addSuppressed(scrubFailure);
            throw deleteFailure;
        }
        if (scrubFailure != null) throw scrubFailure;
        throw new IOException("Could not remove runtime configuration: " + path);
    }
}
