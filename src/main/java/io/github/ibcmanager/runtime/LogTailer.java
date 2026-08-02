package io.github.ibcmanager.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class LogTailer {
    private static final int MAX_READ_BYTES = 1024 * 1024;
    private final Path path;
    private long position;
    private String remainder = "";

    public LogTailer(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    public synchronized List<String> readNewLines() throws IOException {
        if (!Files.isRegularFile(path)) return List.of();
        long size = Files.size(path);
        if (size < position) {
            position = 0;
            remainder = "";
        }
        if (size == position) return List.of();

        long start = position;
        if (size - start > MAX_READ_BYTES) {
            start = Math.max(0, size - MAX_READ_BYTES);
            remainder = "[IBC Manager skipped older log data]\n";
        }
        int length = Math.toIntExact(size - start);
        ByteBuffer buffer = ByteBuffer.allocate(length);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.position(start);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Read until the captured file size has been consumed.
            }
        }
        buffer.flip();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        int completeLength = completeUtf8Length(bytes);
        position = start + completeLength;
        String text = remainder + new String(bytes, 0, completeLength, StandardCharsets.UTF_8);
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] parts = text.split("\n", -1);
        int completeCount = parts.length - 1;
        List<String> result = new ArrayList<>(completeCount);
        for (int i = 0; i < completeCount; i++) result.add(parts[i]);
        remainder = parts[parts.length - 1];
        return List.copyOf(result);
    }

    private static int completeUtf8Length(byte[] bytes) {
        if (bytes.length == 0) return 0;
        int index = bytes.length - 1;
        int continuationBytes = 0;
        while (index >= 0 && isContinuation(bytes[index]) && continuationBytes < 3) {
            continuationBytes++;
            index--;
        }
        if (continuationBytes == 0) {
            int expected = sequenceLength(bytes[bytes.length - 1]);
            return expected > 1 ? bytes.length - 1 : bytes.length;
        }
        if (index < 0) return bytes.length;
        int expected = sequenceLength(bytes[index]);
        return expected > continuationBytes + 1 ? index : bytes.length;
    }

    private static boolean isContinuation(byte value) {
        return (value & 0xC0) == 0x80;
    }

    private static int sequenceLength(byte value) {
        int unsigned = value & 0xFF;
        if ((unsigned & 0x80) == 0) return 1;
        if ((unsigned & 0xE0) == 0xC0) return 2;
        if ((unsigned & 0xF0) == 0xE0) return 3;
        if ((unsigned & 0xF8) == 0xF0) return 4;
        return 1;
    }

    public synchronized void resetToEnd() throws IOException {
        position = Files.isRegularFile(path) ? Files.size(path) : 0;
        remainder = "";
    }

    public synchronized long position() {
        return position;
    }
}
