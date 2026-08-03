package io.github.ibcmanager.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Set;

/** Reads an application control file without following links or allocating without a bound. */
public final class BoundedFileReader {
    private BoundedFileReader() { }

    public static byte[] readBytes(Path path, int maximumBytes, String description) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(description, "description");
        if (maximumBytes < 1) throw new IllegalArgumentException("maximumBytes must be positive");
        SecureFileOperations.requireRegularFile(path, description);
        long declaredSize = java.nio.file.Files.size(path);
        if (declaredSize > maximumBytes) {
            throw new IOException(description + " exceeds the " + maximumBytes + " byte safety limit: " + path);
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(declaredSize, 8192));
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        try (var channel = java.nio.file.Files.newByteChannel(path,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            int total = 0;
            while (true) {
                buffer.clear();
                int count = channel.read(buffer);
                if (count < 0) break;
                if (count == 0) continue;
                total += count;
                if (total > maximumBytes) {
                    throw new IOException(description + " grew beyond the " + maximumBytes
                            + " byte safety limit while being read: " + path);
                }
                output.write(buffer.array(), 0, count);
            }
        }
        return output.toByteArray();
    }

    public static String readString(Path path, Charset charset, int maximumBytes, String description)
            throws IOException {
        Objects.requireNonNull(charset, "charset");
        byte[] bytes = readBytes(path, maximumBytes, description);
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw new IOException(description + " is not valid " + charset.displayName() + ": " + path, ex);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }
}
