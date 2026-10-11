package io.github.ibcmanager.runtime;

import io.github.ibcmanager.security.SecretRedactor;

import java.util.Optional;

/** Recognizes fatal JVM bootstrap diagnostics, before the IBC main method is reached. */
public final class JavaBootstrapFailure {
    private JavaBootstrapFailure() { }

    public static Optional<String> message(String line) {
        if (line == null) return Optional.empty();
        String text = line.strip();
        if (text.equals("Error occurred during initialization of boot layer")) {
            return Optional.of("Java runtime failed during module initialization");
        }
        if (text.startsWith("java.lang.module.FindException:")
                || text.startsWith("java.lang.module.ResolutionException:")) {
            // Drop an oversized line in full rather than truncating a secret before masking it.
            return Optional.of(text.length() > 2048 ? "Java module resolution failed; see the runtime log"
                    : SecretRedactor.redact(text));
        }
        return Optional.empty();
    }
}
