package io.github.ibcmanager.security;

import java.util.Collection;
import java.util.Objects;
import java.util.regex.Pattern;

public final class SecretRedactor {
    private static final Pattern CONFIG_PASSWORD = Pattern.compile(
            "(?im)^(\\s*(?:IbPassword|FIXPassword)\\s*=).*$");
    private static final Pattern COMMAND_PASSWORD = Pattern.compile(
            "(?i)(/PW:|/FIXPW:)(?:\\\"[^\\\"]*\\\"|\\S*)");

    private SecretRedactor() {
    }

    public static String redact(String input) {
        if (input == null || input.isEmpty()) return input == null ? "" : input;
        String redacted = CONFIG_PASSWORD.matcher(input).replaceAll("$1[REDACTED]");
        return COMMAND_PASSWORD.matcher(redacted).replaceAll("$1[REDACTED]");
    }

    public static String redact(String input, Collection<String> exactSecrets) {
        String redacted = redact(input);
        if (exactSecrets == null) return redacted;
        for (String secret : exactSecrets) {
            if (secret != null && !secret.isEmpty()) redacted = redacted.replace(secret, "[REDACTED]");
        }
        return redacted;
    }

    public static boolean contains(String input, String secret) {
        Objects.requireNonNull(input, "input");
        return secret != null && !secret.isEmpty() && input.contains(secret);
    }
}
