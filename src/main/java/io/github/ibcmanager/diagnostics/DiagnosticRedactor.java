package io.github.ibcmanager.diagnostics;

import io.github.ibcmanager.security.SecretRedactor;

import java.util.List;
import java.util.regex.Pattern;

/** Extra filtering for broker-owned logs; never print authentication material or profile usernames. */
final class DiagnosticRedactor {
    private static final Pattern FIELDS = Pattern.compile(
            "(?i)([\\\"']?(?:IbLoginId|FIXLoginId|username|userName|userSessionID|sessionId|"
                    + "authToken|ccpToken|ctciToken|logKey|logKeyId|logUploadKey|userDirname|shortTokenHashes?|"
                    + "tokenHash|newToken|oldToken)[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"\\r\\n]*\\\"|'[^'\\r\\n]*'|[^,;\\s\\]})]+)");
    private static final Pattern HASH_LIST = Pattern.compile("(?i)(shortTokenHashes?\\s*[:=]\\s*)[^\\]\\r\\n, ]+");
    private static final Pattern ENV_SECRET = Pattern.compile("(?im)^(\\s*[A-Za-z0-9_.-]*(?:PASSWORD|PASSWD|SECRET|TOKEN|API[_-]?KEY)[A-Za-z0-9_.-]*\\s*=).*$");
    private static final Pattern AUTHORIZATION = Pattern.compile("(?im)((?:Proxy-)?Authorization\\s*[:=]\\s*).*$");
    private static final Pattern ACCOUNTS = Pattern.compile("(?i)\\b(?:DU|U)[0-9]{5,}\\b");
    private static final Pattern USER_PATH = Pattern.compile("(?i)([A-Z]:[\\\\/]Users[\\\\/])[^\\\\/\\r\\n]+?(?=[\\\\/])");
    private static final Pattern SESSION_PATH = Pattern.compile("(?i)([\\\\/])[a-p]{32,64}(?=[\\\\/\\s'\\\"]|$)");
    private static final Pattern HEADER = Pattern.compile("(?im)(X-USERNAME\\s*[:=]\\s*).*$");
    private static final Pattern MAC = Pattern.compile("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b");

    private DiagnosticRedactor() { }

    static String redact(String text, String username) {
        String value = text == null ? "" : text;
        // Mask complete sensitive fields first. Generic command-argument redaction may
        // otherwise consume the next field name when a value itself ends in '-TOKEN'.
        value = ENV_SECRET.matcher(value).replaceAll("$1[REDACTED]");
        value = AUTHORIZATION.matcher(value).replaceAll("$1[REDACTED]");
        value = HASH_LIST.matcher(value).replaceAll("$1[REDACTED]");
        value = FIELDS.matcher(value).replaceAll("$1[REDACTED]");
        value = HEADER.matcher(value).replaceAll("$1[REDACTED]");
        value = SecretRedactor.redact(value, username == null || username.isBlank() ? List.of() : List.of(username));
        value = ACCOUNTS.matcher(value).replaceAll("[ACCOUNT]");
        value = USER_PATH.matcher(value).replaceAll("$1[USER]");
        value = SESSION_PATH.matcher(value).replaceAll("$1[SESSION]");
        return MAC.matcher(value).replaceAll("[MAC]");
    }
}
