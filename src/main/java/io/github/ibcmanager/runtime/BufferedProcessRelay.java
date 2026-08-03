package io.github.ibcmanager.runtime;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Detached process-output relay with bounded sixty-second disk buffering. */
public final class BufferedProcessRelay {
    private static final int START_FAILURE_EXIT_CODE = 70;

    private BufferedProcessRelay() { }

    public static void main(String[] arguments) {
        int result = run(arguments);
        System.exit(result);
    }

    static int run(String[] arguments) {
        if (arguments.length != 1) return 64;
        Path descriptorPath;
        try {
            descriptorPath = Path.of(arguments[0]).toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            return 64;
        }
        if (!ProcessRelayDescriptor.isExpectedDescriptorPath(descriptorPath)) return 64;

        ProcessRelayDescriptor descriptor;
        try {
            descriptor = ProcessRelayDescriptor.read(descriptorPath);
        } catch (IOException | RuntimeException ex) {
            deleteExpectedDescriptorQuietly(descriptorPath);
            return 65;
        }
        try {
            ProcessRelayDescriptor.deleteExpectedRegularDescriptor(descriptorPath);
        } catch (IOException ex) {
            return 66;
        }

        byte[] initial = descriptor.initialLogText().getBytes(StandardCharsets.UTF_8);
        try (PeriodicByteLog log = new PeriodicByteLog(
                descriptor.logFile(), descriptor.flushInterval(), initial)) {
            OutputStream live = new FileOutputStream(FileDescriptor.out);
            boolean liveAvailable = writeLive(live, initial);

            ProcessBuilder builder = new ProcessBuilder(descriptor.command());
            builder.directory(descriptor.workingDirectory().toFile());
            builder.environment().putAll(descriptor.environment());
            builder.redirectErrorStream(true);
            Process child;
            try {
                child = builder.start();
            } catch (IOException ex) {
                byte[] message = ("IBC Manager process relay: launch failed: " + safeMessage(ex)
                        + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
                log.append(message, 0, message.length);
                if (liveAvailable) writeLive(live, message);
                return START_FAILURE_EXIT_CODE;
            }

            byte[] buffer = new byte[16 * 1024];
            try (InputStream input = child.getInputStream()) {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count == 0) continue;
                    log.append(buffer, 0, count);
                    if (liveAvailable) liveAvailable = writeLive(live, buffer, 0, count);
                }
            }
            return child.waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return 130;
        } catch (IOException ex) {
            return 74;
        } finally {
            java.util.Arrays.fill(initial, (byte) 0);
        }
    }

    private static boolean writeLive(OutputStream output, byte[] bytes) {
        return writeLive(output, bytes, 0, bytes.length);
    }

    private static boolean writeLive(OutputStream output, byte[] bytes, int offset, int length) {
        if (length == 0) return true;
        try {
            output.write(bytes, offset, length);
            output.flush();
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private static void deleteExpectedDescriptorQuietly(Path path) {
        try { ProcessRelayDescriptor.deleteExpectedRegularDescriptor(path); }
        catch (IOException ignored) { }
    }

    private static String safeMessage(IOException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
