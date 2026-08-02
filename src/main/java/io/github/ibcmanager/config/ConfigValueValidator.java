package io.github.ibcmanager.config;

import io.github.ibcmanager.model.Severity;
import io.github.ibcmanager.model.ValidationIssue;
import io.github.ibcmanager.security.TextSafety;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ConfigValueValidator {
    private static final DateTimeFormatter TIME_12 = DateTimeFormatter.ofPattern("hh:mm a", Locale.ROOT);
    private static final DateTimeFormatter TIME_24 = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    public List<ValidationIssue> validate(IbcConfigDocument document) {
        return validate(document, Severity.WARNING);
    }

    /** Validates a manager-owned document, where any persisted secret is a blocking error. */
    public List<ValidationIssue> validateManagedConfig(IbcConfigDocument document) {
        return validate(document, Severity.ERROR);
    }

    private static List<ValidationIssue> validate(IbcConfigDocument document, Severity secretSeverity) {
        List<ValidationIssue> issues = new ArrayList<>();
        for (String duplicate : document.duplicateKeys()) {
            issues.add(new ValidationIssue(Severity.ERROR, duplicate, "The configuration contains more than one active value for this setting"));
        }
        for (Map.Entry<String, String> entry : document.activeSettings().entrySet()) {
            if (TextSafety.containsConfigBreakingControl(entry.getValue())) {
                issues.add(new ValidationIssue(Severity.ERROR, entry.getKey(),
                        "Value must not contain line breaks, Unicode line separators, or NUL characters"));
                continue;
            }
            IbcConfigSchema.find(entry.getKey()).ifPresent(definition -> validate(definition, entry.getValue(), issues));
        }
        if (document.hasPlaintextSecret()) {
            issues.add(new ValidationIssue(secretSeverity, "IbPassword",
                    "This configuration contains a plaintext password, including in a duplicate, comment, or raw line"));
        }
        return List.copyOf(issues);
    }

    public List<ValidationIssue> validate(Map<String, String> settings) {
        List<ValidationIssue> issues = new ArrayList<>();
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().matches("[A-Za-z][A-Za-z0-9_.-]*")) {
                issues.add(new ValidationIssue(Severity.ERROR, "settings", "Invalid IBC setting key: " + entry.getKey()));
                continue;
            }
            if (TextSafety.containsConfigBreakingControl(entry.getValue())) {
                issues.add(new ValidationIssue(Severity.ERROR, entry.getKey(),
                        "Value must not contain line breaks, Unicode line separators, or NUL characters"));
                continue;
            }
            IbcConfigSchema.find(entry.getKey()).ifPresent(definition -> validate(definition, entry.getValue(), issues));
        }
        return List.copyOf(issues);
    }

    private static void validate(SettingDefinition definition, String value, List<ValidationIssue> issues) {
        String trimmed = value == null ? "" : value.trim();
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
            case ENUM -> requireAllowed(definition, trimmed, new HashSet<>(definition.allowedValues()), issues);
            case INTEGER -> validateInteger(definition, trimmed, Integer.MIN_VALUE, Integer.MAX_VALUE, issues);
            case PORT -> validateInteger(definition, trimmed, 0, 65535, issues);
            case TIME_12_HOUR -> validateTime(definition, trimmed.toUpperCase(Locale.ROOT), TIME_12, issues);
            case TIME_24_HOUR -> validateTime(definition, trimmed, TIME_24, issues);
            case IP_LIST -> validateIpList(definition, trimmed, issues);
            default -> { }
        }
    }

    private static void requireAllowed(SettingDefinition definition, String value, Set<String> allowed,
            List<ValidationIssue> issues) {
        boolean found = allowed.stream().anyMatch(item -> item.equalsIgnoreCase(value));
        if (!found) issues.add(new ValidationIssue(Severity.ERROR, definition.key(),
                "Value must be one of: " + String.join(", ", allowed)));
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
        for (String item : value.split(",")) {
            String host = item.trim();
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
}
