package io.github.ibcmanager.config;

import io.github.ibcmanager.storage.AtomicFileWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class RuntimeConfigLease implements AutoCloseable {
    private final Path path;
    private boolean closed;

    public RuntimeConfigLease(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    public Path path() {
        return path;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        if (!Files.exists(path)) {
            closed = true;
            return;
        }

        IOException scrubFailure = null;
        if (Files.isRegularFile(path)) {
            try {
                IbcConfigDocument document = IbcConfigDocument.parse(Files.readString(path, StandardCharsets.UTF_8));
                for (String key : IbcConfigSchema.sensitiveKeys()) document.set(key, "");
                AtomicFileWriter.write(path, document.render().getBytes(StandardCharsets.UTF_8), false);
            } catch (IOException ex) {
                scrubFailure = ex;
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

        if (!Files.exists(path)) {
            closed = true;
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
