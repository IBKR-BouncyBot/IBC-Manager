package io.github.ibcmanager.config;

import io.github.ibcmanager.model.Severity;
import io.github.ibcmanager.model.ValidationIssue;
import io.github.ibcmanager.security.TextSafety;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ConfigValueValidator {
    // Validate IBC 3.24.1's documented, case-sensitive English AM/PM grammar
    // independently of the Manager JVM's host locale. In particular, a Dutch
    // Windows locale must not reject "11:45 PM", while lowercase am/pm remains invalid.
    private static final DateTimeFormatter TIME_12 = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_24 = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .toFormatter(Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Set<String> ORDER_ID_RESET_ACTIONS = Set.of("confirm", "reject", "ignore");
    private static final Set<String> CASE_SENSITIVE_ENUM_KEYS = Set.of(
            "AcceptBidAskLastSizeDisplayUpdateNotification", "ConfirmCryptoCurrencyOrders");

    public List<ValidationIssue> validate(IbcConfigDocument document) {
        return validate(document, Severity.WARNING, Severity.WARNING, Severity.WARNING, true);
    }

    /** Validates a manager-owned document, where persisted secrets and mistyped known keys block saving. */
    public List<ValidationIssue> validateManagedConfig(IbcConfigDocument document) {
        return validate(document, Severity.ERROR, Severity.ERROR, Severity.ERROR, true);
    }

    /** Validates an ephemeral runtime document, where a plaintext login password is expected. */
    public List<ValidationIssue> validateRuntimeConfig(IbcConfigDocument document) {
        return validate(document, null, Severity.ERROR, Severity.ERROR, false);
    }

    private static List<ValidationIssue> validate(IbcConfigDocument document, Severity secretSeverity,
            Severity keyCaseSeverity, Severity semanticMismatchSeverity, boolean reportSecrets) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (!document.formattingMatchesIbcSemantics()) {
            issues.add(new ValidationIssue(semanticMismatchSeverity, "config.ini",
                    "The raw formatting scanner and IBC's full-file Java Properties parser assign "
                            + "different meanings to this file. Correct the malformed syntax or explicitly "
                            + "canonicalize it before saving."));
        }
        for (String key : document.suspiciousBackslashKeys()) {
            issues.add(new ValidationIssue(Severity.WARNING, key,
                    "The raw value contains a single Windows-style backslash that Java Properties "
                            + "consumes as an escape. Use doubled backslashes for a literal path, for "
                            + "example C:\\\\Jts rather than C:\\Jts. The value itself is not shown."));
        }
        for (String duplicate : document.duplicateKeys()) {
            issues.add(new ValidationIssue(Severity.ERROR, duplicate,
                    "The configuration contains more than one active value for this setting"));
        }
        Map<String, String> settings = document.activeSettings();
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (TextSafety.containsConfigBreakingControl(value)) {
                issues.add(new ValidationIssue(Severity.ERROR, key,
                        "Value must not contain line breaks, Unicode line separators, or NUL characters"));
                continue;
            }
            var exact = IbcConfigSchema.find(key);
            if (exact.isPresent()) {
                validate(exact.get(), value, issues);
                continue;
            }
            IbcConfigSchema.findIgnoreCase(key).ifPresent(definition -> issues.add(new ValidationIssue(
                    keyCaseSeverity, key, "Known IBC setting has incorrect letter case; use "
                            + definition.key() + " because Java Properties keys are case-sensitive")));
        }
        validateCrossSettingRules(settings, issues);
        if (reportSecrets && document.hasPlaintextSecret()) {
            issues.add(new ValidationIssue(secretSeverity, "IbPassword",
                    "This configuration contains a plaintext password, including in a duplicate, comment, or raw line"));
        }
        return List.copyOf(issues);
    }

    public List<ValidationIssue> validate(Map<String, String> settings) {
        List<ValidationIssue> issues = new ArrayList<>();
        Set<String> seenKnownKeys = new HashSet<>();
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_.-]*")) {
                issues.add(new ValidationIssue(Severity.ERROR, "settings", "Invalid IBC setting key: " + key));
                continue;
            }
            if (value == null) {
                issues.add(new ValidationIssue(Severity.ERROR, key, "IBC setting value must not be null"));
                continue;
            }
            if (TextSafety.containsConfigBreakingControl(value)) {
                issues.add(new ValidationIssue(Severity.ERROR, key,
                        "Value must not contain line breaks, Unicode line separators, or NUL characters"));
                continue;
            }
            var definition = IbcConfigSchema.findIgnoreCase(key);
            if (definition.isEmpty()) continue;
            String canonical = definition.get().key();
            String normalized = canonical.toLowerCase(Locale.ROOT);
            if (!seenKnownKeys.add(normalized)) {
                issues.add(new ValidationIssue(Severity.ERROR, canonical,
                        "The profile contains the same known IBC setting more than once with different letter case"));
                continue;
            }
            if (!key.equals(canonical)) {
                issues.add(new ValidationIssue(Severity.ERROR, key,
                        "Use the canonical IBC setting name " + canonical));
                continue;
            }
            validate(definition.get(), value, issues);
        }
        validateCrossSettingRules(settings, issues);
        return List.copyOf(issues);
    }

    private static void validate(SettingDefinition definition, String value, List<ValidationIssue> issues) {
        String raw = value == null ? "" : value;
        String trimmed = raw.trim();
        if (requiresUntrimmedIbcValue(definition) && !raw.equals(trimmed)) {
            issues.add(new ValidationIssue(Severity.ERROR, definition.key(),
                    "IBC does not preserve surrounding whitespace for this setting; remove leading or trailing whitespace"));
            return;
        }
        if (trimmed.isEmpty() && (definition.type() == SettingType.TRI_STATE_BOOLEAN
                || definition.type() == SettingType.TEXT
                || definition.type() == SettingType.PATH
                || definition.type() == SettingType.IP_LIST
                || definition.type() == SettingType.SCHEDULE
                || definition.type() == SettingType.TIME_12_HOUR
                || definition.type() == SettingType.TIME_24_HOUR
                || definition.type() == SettingType.INTEGER
                || definition.type() == SettingType.PORT)) return;

        switch (definition.type()) {
            case BOOLEAN -> requireAllowed(definition, trimmed, Set.of("yes", "no", "true", "false"), issues);
            case TRI_STATE_BOOLEAN -> requireAllowed(definition, trimmed, Set.of("yes", "no", "true", "false", ""), issues);
            case ENUM -> {
                Set<String> allowed = new HashSet<>(definition.allowedValues());
                if (CASE_SENSITIVE_ENUM_KEYS.contains(definition.key())) {
                    requireAllowedExact(definition, trimmed, allowed, issues);
                } else {
                    requireAllowed(definition, trimmed, allowed, issues);
                }
            }
            case INTEGER -> validateInteger(definition, trimmed, minimumInteger(definition.key()),
                    maximumInteger(definition.key()), issues);
            case PORT -> validateInteger(definition, trimmed, 0, 65535, issues);
            case TIME_12_HOUR -> validateTime(definition, trimmed, TIME_12, issues);
            case TIME_24_HOUR -> validateTime(definition, trimmed, TIME_24, issues);
            case IP_LIST -> validateIpList(definition, value, issues);
            case SCHEDULE -> validateSchedule(definition, trimmed, issues);
            default -> { }
        }

        if (definition.key().equals("FIX") && IbcCompatibilityPolicy.isTruthy(trimmed)) {
            issues.add(new ValidationIssue(Severity.ERROR, definition.key(),
                    "FIX CTCI mode is not supported by IBC Manager; use ordinary TWS or IB Gateway mode"));
        } else if ((definition.key().equals("FIXLoginId") || definition.key().equals("FIXPassword"))
                && !trimmed.isEmpty()) {
            issues.add(new ValidationIssue(Severity.WARNING, definition.key(),
                    "FIX credentials are ignored because IBC Manager does not support FIX CTCI mode"));
        } else if (definition.key().equals("TrustedTwsApiClientIPs") && !trimmed.isEmpty()) {
            issues.add(new ValidationIssue(Severity.WARNING, definition.key(),
                    "IBC 3.24.1 uses this setting only for FIX CTCI Gateway mode; it is ignored in IBC Manager's supported ordinary TWS and IB Gateway modes"));
        }

        if (definition.key().equals("ConfirmOrderIdReset") && !trimmed.isEmpty()) {
            validateOrderIdResetPolicy(trimmed, issues);
        }
    }

    private static boolean requiresUntrimmedIbcValue(SettingDefinition definition) {
        return definition.type() == SettingType.BOOLEAN
                || definition.type() == SettingType.TRI_STATE_BOOLEAN
                || definition.type() == SettingType.INTEGER
                || definition.type() == SettingType.PORT
                || definition.key().equals("IbLoginId")
                || definition.key().equals("IbPassword")
                || definition.key().equals("FIXLoginId")
                || definition.key().equals("FIXPassword");
    }

    private static int minimumInteger(String key) {
        return switch (key) {
            case "LoginDialogDisplayTimeout", "SecondFactorAuthenticationTimeout",
                    "SecondFactorAuthenticationExitInterval" -> 1;
            case "OverrideTwsMasterClientID" -> 0;
            default -> Integer.MIN_VALUE;
        };
    }

    private static int maximumInteger(String key) {
        return switch (key) {
            case "LoginDialogDisplayTimeout", "SecondFactorAuthenticationTimeout",
                    "SecondFactorAuthenticationExitInterval" -> 86_400;
            default -> Integer.MAX_VALUE;
        };
    }

    private static void validateCrossSettingRules(Map<String, String> settings, List<ValidationIssue> issues) {
        String autoLogoff = settingValueIgnoreCase(settings, "AutoLogoffTime").trim();
        String autoRestart = settingValueIgnoreCase(settings, "AutoRestartTime").trim();
        if (!autoLogoff.isEmpty() && !autoRestart.isEmpty()) {
            issues.add(new ValidationIssue(Severity.WARNING, "AutoLogoffTime",
                    "Both AutoLogoffTime and AutoRestartTime are set; IBC 3.24.1 uses AutoRestartTime and ignores AutoLogoffTime"));
        }
    }

    private static String settingValueIgnoreCase(Map<String, String> settings, String key) {
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue() == null ? "" : entry.getValue();
            }
        }
        return "";
    }

    private static void requireAllowed(SettingDefinition definition, String value, Set<String> allowed,
            List<ValidationIssue> issues) {
        boolean found = allowed.stream().anyMatch(item -> item.equalsIgnoreCase(value));
        if (!found) issues.add(new ValidationIssue(Severity.ERROR, definition.key(),
                "Value must be one of: " + String.join(", ", allowed)));
    }

    private static void requireAllowedExact(SettingDefinition definition, String value, Set<String> allowed,
            List<ValidationIssue> issues) {
        if (!allowed.contains(value)) {
            issues.add(new ValidationIssue(Severity.ERROR, definition.key(),
                    "Value must use IBC's exact canonical spelling and be one of: "
                            + String.join(", ", allowed)));
        }
    }

    private static void validateInteger(SettingDefinition definition, String value, int minimum, int maximum,
            List<ValidationIssue> issues) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                issues.add(new ValidationIssue(Severity.ERROR, definition.key(),
                        "Value must be between " + minimum + " and " + maximum));
            }
        } catch (NumberFormatException ex) {
            issues.add(new ValidationIssue(Severity.ERROR, definition.key(), "Value must be an integer"));
        }
    }

    private static void validateTime(SettingDefinition definition, String value, DateTimeFormatter formatter,
            List<ValidationIssue> issues) {
        try {
            LocalTime.parse(value, formatter);
        } catch (DateTimeParseException ex) {
            issues.add(new ValidationIssue(Severity.ERROR, definition.key(), "Invalid time format"));
        }
    }

    private static void validateIpList(SettingDefinition definition, String value, List<ValidationIssue> issues) {
        if (value.isBlank()) return;
        for (String item : value.split(",", -1)) {
            String host = item.trim();
            if (!item.equals(host)) {
                issues.add(new ValidationIssue(Severity.ERROR, definition.key(),
                        "IBC does not trim spaces around command-source or trusted-IP entries; remove surrounding whitespace"));
            }
            if (host.isEmpty()) {
                issues.add(new ValidationIssue(Severity.ERROR, definition.key(), "IP/host list contains an empty item"));
                continue;
            }
            try {
                InetAddress.getByName(host);
            } catch (UnknownHostException ex) {
                issues.add(new ValidationIssue(Severity.WARNING, definition.key(), "Could not resolve host: " + host));
            }
        }
    }

    private static void validateSchedule(SettingDefinition definition, String value,
            List<ValidationIssue> issues) {
        if (definition.key().equals("ClosedownAt")) {
            validateClosedownAt(definition, value, issues);
        } else if (definition.key().equals("SaveTwsSettingsAt")) {
            validateSaveTwsSettingsAt(definition, value, issues);
        }
    }

    private static void validateClosedownAt(SettingDefinition definition, String value,
            List<ValidationIssue> issues) {
        if (parseStrictLegacyDate(value, "HH:mm") || parseStrictLegacyDate(value, "E HH:mm")) return;
        issues.add(new ValidationIssue(Severity.ERROR, definition.key(),
                "Use HH:mm for a daily shutdown or a locale-recognized day plus HH:mm, for example 22:00 or Friday 22:00"));
    }

    private static boolean parseStrictLegacyDate(String value, String pattern) {
        SimpleDateFormat formatter = new SimpleDateFormat(pattern);
        formatter.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        return formatter.parse(value, position) != null && position.getIndex() == value.length();
    }

    private static void validateSaveTwsSettingsAt(SettingDefinition definition, String value,
            List<ValidationIssue> issues) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character) && character != ' ') {
                issues.add(new ValidationIssue(Severity.ERROR, definition.key(),
                        "IBC separates SaveTwsSettingsAt tokens only with ordinary spaces; tabs and other whitespace are invalid"));
                return;
            }
        }
        String[] tokens = value.split("[ ]+");
        if (!tokens[0].equalsIgnoreCase("Every")) {
            for (String token : tokens) {
                if (!isStrictTime24(token)) {
                    issues.add(new ValidationIssue(Severity.ERROR, definition.key(),
                            "Each save time must use strict HH:mm format"));
                    return;
                }
            }
            return;
        }

        if (tokens.length < 2) {
            issues.add(new ValidationIssue(Severity.ERROR, definition.key(),
                    "Every requires an interval between 1 and 1439 minutes"));
            return;
        }
        long interval;
        try {
            interval = Long.parseLong(tokens[1]);
        } catch (NumberFormatException ex) {
            issues.add(new ValidationIssue(Severity.ERROR, definition.key(),
                    "The Every interval must be an integer"));
            return;
        }
        int nextIndex = 2;
        if (tokens.length > 2 && tokens[2].equalsIgnoreCase("mins")) {
            nextIndex = 3;
        } else if (tokens.length > 2 && tokens[2].equalsIgnoreCase("hours")) {
            nextIndex = 3;
            interval *= 60L;
        }
        if (interval < 1 || interval > 1439) {
            issues.add(new ValidationIssue(Severity.ERROR, definition.key(),
                    "The saving interval must be between 1 and 1439 minutes"));
        }
        int remaining = tokens.length - nextIndex;
        if (remaining > 2) {
            issues.add(new ValidationIssue(Severity.ERROR, definition.key(),
                    "Every accepts at most a start time and an end time"));
            return;
        }
        if (remaining >= 1 && !isStrictTime24(tokens[nextIndex])) {
            issues.add(new ValidationIssue(Severity.ERROR, definition.key(),
                    "The Every start time must use strict HH:mm format"));
        }
        if (remaining == 2 && !isStrictTime24OrEndOfDay(tokens[nextIndex + 1])) {
            issues.add(new ValidationIssue(Severity.ERROR, definition.key(),
                    "The Every end time must use HH:mm or 24:00"));
        }
    }

    private static boolean isStrictTime24(String value) {
        try {
            LocalTime.parse(value, TIME_24);
            return true;
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    private static boolean isStrictTime24OrEndOfDay(String value) {
        return value.equals("24:00") || isStrictTime24(value);
    }

    private static void validateOrderIdResetPolicy(String value, List<ValidationIssue> issues) {
        String[] parts = value.split("/", -1);
        if (parts.length == 2 && ORDER_ID_RESET_ACTIONS.contains(parts[0])
                && ORDER_ID_RESET_ACTIONS.contains(parts[1])) return;
        issues.add(new ValidationIssue(Severity.ERROR, "ConfirmOrderIdReset",
                "Use two lowercase actions separated by '/'; each action must be confirm, reject, or ignore"));
    }
}
