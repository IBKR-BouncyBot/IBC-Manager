package io.github.ibcmanager.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class DefaultCommandExecutor implements CommandExecutor {
    private static final int MAX_OUTPUT_BYTES = 2 * 1024 * 1024;

    @Override
    public CommandResult execute(List<String> command, String stdin, Duration timeout)
            throws IOException, InterruptedException {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(timeout, "timeout");
        if (command.isEmpty()) throw new IllegalArgumentException("Command must not be empty");

        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        Process process = new ProcessBuilder(command).start();
        CompletableFuture<OutputCapture> stdout = readAsync(process.getInputStream());
        CompletableFuture<OutputCapture> stderr = readAsync(process.getErrorStream());
        try {
            try (var writer = process.outputWriter(StandardCharsets.UTF_8)) {
                if (stdin != null) writer.write(stdin);
            }

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            }
            int exitCode = finished ? process.exitValue() : -1;
            boolean timedOut = !finished;
            return new CommandResult(exitCode, await(stdout, timedOut), await(stderr, timedOut), timedOut);
        } catch (IOException | InterruptedException | RuntimeException failure) {
            terminateAfterFailure(process);
            throw failure;
        }
    }

    private static void terminateAfterFailure(Process process) {
        if (process.isAlive()) process.destroyForcibly();
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getErrorStream().close(); } catch (IOException ignored) { }
    }

    private static CompletableFuture<OutputCapture> readAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (stream; output) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    if (total + read > MAX_OUTPUT_BYTES) {
                        int accepted = Math.max(0, MAX_OUTPUT_BYTES - total);
                        output.write(buffer, 0, accepted);
                        break;
                    }
                    output.write(buffer, 0, read);
                    total += read;
                }
                return new OutputCapture(output.toString(StandardCharsets.UTF_8), null);
            } catch (IOException ex) {
                // Destroying a timed-out process can close its pipe while a
                // reader is blocked. Preserve any bytes already captured and
                // let the caller decide whether the read failure is expected
                // for a timed-out command or must still fail a completed one.
                return new OutputCapture(output.toString(StandardCharsets.UTF_8), ex);
            }
        });
    }

    private static String await(CompletableFuture<OutputCapture> future, boolean tolerateReadFailure)
            throws IOException, InterruptedException {
        try {
            OutputCapture capture = future.get(5, TimeUnit.SECONDS);
            if (capture.failure() != null && !tolerateReadFailure) throw capture.failure();
            return capture.text();
        } catch (ExecutionException ex) {
            throw new IOException("Could not capture command output", ex.getCause());
        } catch (java.util.concurrent.TimeoutException ex) {
            throw new IOException("Timed out while capturing command output", ex);
        }
    }

    private record OutputCapture(String text, IOException failure) { }
}
