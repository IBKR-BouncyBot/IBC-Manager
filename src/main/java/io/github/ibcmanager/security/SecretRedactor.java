package io.github.ibcmanager.security;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Central best-effort redaction for logs, diagnostics, and user-visible errors. */
public final class SecretRedactor {
    private static final Pattern CONFIG_PASSWORD = Pattern.compile(
            "(?im)^(\\s*(?:[#;]\\s*)?(?:IbPassword|FIXPassword|Password|Passwd|Passphrase|Token|Secret|ApiKey)\\s*[=:])\\s*.*$");
    private static final Pattern COMMAND_PASSWORD = Pattern.compile(
            "(?i)((?:/PW:|/FIXPW:|--?password(?:=|\\s+)|--?passwd(?:=|\\s+)|--?token(?:=|\\s+)))(?:\\\"[^\\\"]*\\\"|'[^']*'|\\S*)");
    private static final Pattern STRUCTURED_SECRET = Pattern.compile(
            "(?i)([\\\"']?(?:password|passwd|passphrase|token|secret|api[_-]?key)[\\\"']?\\s*[:=]\\s*)([\\\"'])(.*?)(\\2)");
    private static final Pattern QUERY_SECRET = Pattern.compile(
            "(?i)([?&](?:password|passwd|passphrase|token|secret|api[_-]?key)=)([^&#\\s]*)");

    private SecretRedactor() { }

    public static String redact(String input) {
        if (input == null || input.isEmpty()) return input == null ? "" : input;
        String redacted = CONFIG_PASSWORD.matcher(input).replaceAll("$1[REDACTED]");
        redacted = COMMAND_PASSWORD.matcher(redacted).replaceAll("$1[REDACTED]");
        redacted = replaceStructured(redacted);
        return QUERY_SECRET.matcher(redacted).replaceAll("$1[REDACTED]");
    }

    private static String replaceStructured(String input) {
        Matcher matcher = STRUCTURED_SECRET.matcher(input);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    matcher.group(1) + matcher.group(2) + "[REDACTED]" + matcher.group(4)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static String redact(String input, Collection<String> exactSecrets) {
        String redacted = redact(input);
        if (exactSecrets == null) return redacted;
        for (String secret : exactSecrets.stream()
                .filter(value -> value != null && !value.isEmpty())
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList()) {
            redacted = redacted.replace(secret, "[REDACTED]");
        }
        return redacted;
    }

    public static boolean contains(String input, String secret) {
        Objects.requireNonNull(input, "input");
        return secret != null && !secret.isEmpty() && input.contains(secret);
    }
}
