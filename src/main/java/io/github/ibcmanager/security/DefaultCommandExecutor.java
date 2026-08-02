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

        Process process = new ProcessBuilder(command).start();
        CompletableFuture<String> stdout = readAsync(process.getInputStream());
        CompletableFuture<String> stderr = readAsync(process.getErrorStream());
        try (var writer = process.outputWriter(StandardCharsets.UTF_8)) {
            if (stdin != null) writer.write(stdin);
        }

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        }
        int exitCode = finished ? process.exitValue() : -1;
        return new CommandResult(exitCode, await(stdout), await(stderr), !finished);
    }

    private static CompletableFuture<String> readAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
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
                return output.toString(StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    private static String await(CompletableFuture<String> future) throws IOException, InterruptedException {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IllegalStateException state && state.getCause() instanceof IOException io) throw io;
            throw new IOException("Could not capture command output", cause);
        } catch (java.util.concurrent.TimeoutException ex) {
            throw new IOException("Timed out while capturing command output", ex);
        }
    }
}
