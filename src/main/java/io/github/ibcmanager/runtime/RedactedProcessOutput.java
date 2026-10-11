package io.github.ibcmanager.runtime;

import io.github.ibcmanager.security.SecretRedactor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/** Complete-line redaction before either the disk buffer or live pipe sees child output. */
final class RedactedProcessOutput {
    static final int MAX_LINE_CHARACTERS = 64 * 1024;
    static final String OMITTED_LINE = "[IBC Manager omitted an oversized process-output line]";

    private RedactedProcessOutput() { }

    static void relay(InputStream input, Consumer<byte[]> output) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        StringBuilder line = new StringBuilder();
        boolean oversized = false;
        boolean carriageReturn = false;
        char[] buffer = new char[4096];
        try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            int count;
            while ((count = reader.read(buffer)) != -1) {
                for (int i = 0; i < count; i++) {
                    char ch = buffer[i];
                    if (ch == '\r' || ch == '\n') {
                        if (!(ch == '\n' && carriageReturn)) emit(line, oversized, output);
                        line.setLength(0);
                        oversized = false;
                        carriageReturn = ch == '\r';
                    } else {
                        carriageReturn = false;
                        if (!oversized) {
                            if (line.length() < MAX_LINE_CHARACTERS) line.append(ch);
                            else {
                                // Never publish a truncated token/password prefix or its tail.
                                line.setLength(0);
                                oversized = true;
                            }
                        }
                    }
                }
            }
            if (!line.isEmpty() || oversized) emit(line, oversized, output);
        } finally {
            Arrays.fill(buffer, '\0');
            line.setLength(0);
        }
    }

    private static void emit(StringBuilder line, boolean oversized, Consumer<byte[]> output) {
        String safe = oversized ? OMITTED_LINE : SecretRedactor.redact(line.toString());
        output.accept((safe + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
