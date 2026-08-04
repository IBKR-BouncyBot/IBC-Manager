package io.github.ibcmanager.runtime;

import io.github.ibcmanager.security.BoundedFileReader;
import io.github.ibcmanager.security.FilePermissionHardener;
import io.github.ibcmanager.security.SecureFileOperations;
import io.github.ibcmanager.storage.AtomicFileWriter;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class ProcessRelayDescriptor {
    private static final int MAGIC = 0x49424352; // IBCR
    private static final int FORMAT_VERSION = 2;
    private static final int MAX_ITEMS = 2048;
    private static final int MAX_STRING_BYTES = 4 * 1024 * 1024;
    static final int MAX_DESCRIPTOR_BYTES = 32 * 1024 * 1024;
    private static final String PREFIX = ".ibc-manager-process-relay-";
    private static final String SUFFIX = ".bin";

    private final List<String> command;
    private final Path workingDirectory;
    private final Map<String, String> environment;
    private final Path logFile;
    private final String initialLogText;
    private final Duration flushInterval;
    private final Path cleanupPath;

    private ProcessRelayDescriptor(List<String> command, Path workingDirectory,
            Map<String, String> environment, Path logFile, String initialLogText, Duration flushInterval,
            Path cleanupPath) {
        this.command = List.copyOf(command);
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        this.environment = Map.copyOf(environment);
        this.logFile = logFile.toAbsolutePath().normalize();
        this.initialLogText = Objects.requireNonNullElse(initialLogText, "");
        this.flushInterval = flushInterval;
        this.cleanupPath = cleanupPath == null ? null : cleanupPath.toAbsolutePath().normalize();
    }

    static Path write(LaunchSpec spec, Path logFile, String initialLogText, Duration flushInterval)
            throws IOException {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(logFile, "logFile");
        Objects.requireNonNull(flushInterval, "flushInterval");
        validate(spec.command(), spec.environment(), flushInterval);
        Path parent = spec.workingDirectory().toAbsolutePath().normalize();
        FilePermissionHardener.hardenDirectory(parent);
        Path descriptor = parent.resolve(PREFIX + UUID.randomUUID() + SUFFIX);
        byte[] payload = encode(spec, logFile, initialLogText, flushInterval);
        try {
            AtomicFileWriter.write(descriptor, payload, false);
            FilePermissionHardener.hardenFile(descriptor);
            return descriptor;
        } catch (IOException | RuntimeException failure) {
            safeDelete(descriptor, failure);
            throw failure;
        } finally {
            java.util.Arrays.fill(payload, (byte) 0);
        }
    }

    private static byte[] encode(LaunchSpec spec, Path logFile, String initialLogText,
            Duration flushInterval) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeLong(flushInterval.toMillis());
            output.writeBoolean(spec.cleanupPath() != null);
            if (spec.cleanupPath() != null) {
                writeString(output, spec.cleanupPath().toAbsolutePath().normalize().toString());
            }
            writeString(output, spec.workingDirectory().toAbsolutePath().normalize().toString());
            writeString(output, logFile.toAbsolutePath().normalize().toString());
            writeString(output, Objects.requireNonNullElse(initialLogText, ""));
            output.writeInt(spec.command().size());
            for (String argument : spec.command()) writeString(output, argument);
            List<Map.Entry<String, String>> environment = spec.environment().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList();
            output.writeInt(environment.size());
            for (Map.Entry<String, String> entry : environment) {
                writeString(output, entry.getKey());
                writeString(output, entry.getValue());
            }
        }
        if (bytes.size() > MAX_DESCRIPTOR_BYTES) {
            throw new IOException("Process-relay descriptor exceeds the " + MAX_DESCRIPTOR_BYTES
                    + " byte safety limit");
        }
        return bytes.toByteArray();
    }

    static ProcessRelayDescriptor read(Path descriptor) throws IOException {
        Objects.requireNonNull(descriptor, "descriptor");
        byte[] payload = BoundedFileReader.readBytes(descriptor, MAX_DESCRIPTOR_BYTES,
                "Process-relay descriptor");
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(new ByteArrayInputStream(payload)))) {
            if (input.readInt() != MAGIC) throw new IOException("Invalid process-relay descriptor signature");
            int formatVersion = input.readInt();
            if (formatVersion < 1 || formatVersion > FORMAT_VERSION) {
                throw new IOException("Unsupported process-relay descriptor version");
            }
            long flushMillis = input.readLong();
            if (flushMillis <= 0 || flushMillis > Duration.ofHours(1).toMillis()) {
                throw new IOException("Invalid process-relay flush interval");
            }
            Path cleanupPath = null;
            if (formatVersion >= 2 && input.readBoolean()) {
                cleanupPath = parsePath(readString(input), "cleanup file");
            }
            Path workingDirectory = parsePath(readString(input), "working directory");
            Path logFile = parsePath(readString(input), "log file");
            String initialText = readString(input);
            int commandCount = readCount(input, "command");
            List<String> command = new ArrayList<>(commandCount);
            for (int index = 0; index < commandCount; index++) command.add(readString(input));
            int environmentCount = readCount(input, "environment");
            Map<String, String> environment = new LinkedHashMap<>();
            for (int index = 0; index < environmentCount; index++) {
                String key = readString(input);
                String value = readString(input);
                if (environment.putIfAbsent(key, value) != null) {
                    throw new IOException("Duplicate process-relay environment key: " + key);
                }
            }
            if (input.read() != -1) throw new IOException("Trailing data in process-relay descriptor");
            validate(command, environment, Duration.ofMillis(flushMillis));
            return new ProcessRelayDescriptor(command, workingDirectory, environment, logFile,
                    initialText, Duration.ofMillis(flushMillis), cleanupPath);
        } catch (EOFException ex) {
            throw new IOException("Truncated process-relay descriptor", ex);
        } finally {
            java.util.Arrays.fill(payload, (byte) 0);
        }
    }

    static boolean isExpectedDescriptorPath(Path path) {
        if (path == null || path.getFileName() == null) return false;
        String name = path.getFileName().toString();
        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) return false;
        String token = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
        try {
            UUID.fromString(token);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    static void deleteExpectedRegularDescriptor(Path descriptor) throws IOException {
        if (!isExpectedDescriptorPath(descriptor)) return;
        if (!Files.exists(descriptor, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(descriptor)) return;
        SecureFileOperations.requireRegularFile(descriptor, "Process-relay descriptor");
        Files.deleteIfExists(descriptor);
    }

    private static Path parsePath(String value, String label) throws IOException {
        try {
            return Path.of(value);
        } catch (RuntimeException ex) {
            throw new IOException("Invalid process-relay " + label + " path", ex);
        }
    }

    private static void safeDelete(Path descriptor, Throwable failure) {
        try { deleteExpectedRegularDescriptor(descriptor); }
        catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
    }

    private static void validate(List<String> command, Map<String, String> environment,
            Duration flushInterval) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(environment, "environment");
        if (command.isEmpty() || command.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("Process-relay command must contain 1 to " + MAX_ITEMS + " values");
        }
        if (command.stream().anyMatch(ProcessRelayDescriptor::invalidValue)) {
            throw new IllegalArgumentException("Process-relay command contains an invalid value");
        }
        if (environment.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("Process-relay environment contains too many values");
        }
        if (environment.entrySet().stream().anyMatch(entry -> invalidValue(entry.getKey())
                || invalidValue(entry.getValue()) || entry.getKey().isEmpty())) {
            throw new IllegalArgumentException("Process-relay environment contains an invalid value");
        }
        if (flushInterval.isZero() || flushInterval.isNegative()
                || flushInterval.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Process-relay flush interval is invalid");
        }
    }

    private static boolean invalidValue(String value) { return value == null || value.indexOf('\0') >= 0; }

    private static int readCount(DataInputStream input, String label) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_ITEMS) throw new IOException("Invalid process-relay " + label + " count");
        return count;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = Objects.requireNonNullElse(value, "").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IOException("Process-relay value is too large");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) throw new IOException("Invalid process-relay string length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Truncated process-relay string");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    List<String> command() { return command; }
    Path workingDirectory() { return workingDirectory; }
    Map<String, String> environment() { return environment; }
    Path logFile() { return logFile; }
    String initialLogText() { return initialLogText; }
    Duration flushInterval() { return flushInterval; }
    Path cleanupPath() { return cleanupPath; }
}
