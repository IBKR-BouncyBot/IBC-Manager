package io.github.ibcmanager.config;

import io.github.ibcmanager.security.TextSafety;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IbcConfigDocument {
    private static final Pattern SETTING_PATTERN = Pattern.compile(
            "^(\\s*)([A-Za-z][A-Za-z0-9_.-]*)(\\s*)=(\\s*)(.*)$", Pattern.DOTALL);
    private static final List<Pattern> SENSITIVE_ASSIGNMENTS = buildSensitiveAssignmentPatterns();
    private static final String DUPLICATE_MARKER = "# IBC Manager disabled duplicate: ";

    private final List<Line> lines;
    private final String newline;
    private final boolean finalNewline;

    private IbcConfigDocument(List<Line> lines, String newline, boolean finalNewline) {
        this.lines = new ArrayList<>(lines);
        this.newline = newline;
        this.finalNewline = finalNewline;
    }

    public static IbcConfigDocument parse(String text) {
        Objects.requireNonNull(text, "text");
        String newline = detectNewline(text);
        boolean finalNewline = text.endsWith("\n") || text.endsWith("\r");
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] rawLines = normalized.split("\n", -1);
        int count = finalNewline && rawLines.length > 0 ? rawLines.length - 1 : rawLines.length;
        List<Line> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) lines.add(parseLine(rawLines[i]));
        return new IbcConfigDocument(lines, newline, finalNewline);
    }

    public static IbcConfigDocument empty() {
        return new IbcConfigDocument(List.of(), System.lineSeparator(), true);
    }

    public IbcConfigDocument copy() {
        return new IbcConfigDocument(lines, newline, finalNewline);
    }

    public Optional<String> get(String key) {
        Objects.requireNonNull(key, "key");
        String value = null;
        for (Line line : lines) {
            if (line instanceof SettingLine setting && setting.key.equals(key)) value = setting.value;
        }
        return Optional.ofNullable(value);
    }

    public List<String> getAll(String key) {
        Objects.requireNonNull(key, "key");
        List<String> values = new ArrayList<>();
        for (Line line : lines) {
            if (line instanceof SettingLine setting && setting.key.equals(key)) values.add(setting.value);
        }
        return List.copyOf(values);
    }

    public Map<String, String> activeSettings() {
        Map<String, String> values = new LinkedHashMap<>();
        for (Line line : lines) {
            if (line instanceof SettingLine setting) values.put(setting.key, setting.value);
        }
        return Collections.unmodifiableMap(values);
    }

    public Set<String> duplicateKeys() {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (Line line : lines) {
            if (line instanceof SettingLine setting && !seen.add(setting.key)) duplicates.add(setting.key);
        }
        return Collections.unmodifiableSet(duplicates);
    }

    public void set(String key, String value) {
        validateKey(key);
        String safeValue = value == null ? "" : value;
        TextSafety.requireSingleLine(safeValue, "IBC configuration value for " + key);
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (line instanceof SettingLine setting && setting.key.equals(key)) matches.add(i);
        }
        if (matches.isEmpty()) {
            if (!lines.isEmpty() && !(lines.get(lines.size() - 1) instanceof BlankLine)) lines.add(new BlankLine(""));
            lines.add(new SettingLine("", key, "", "", safeValue));
            return;
        }
        int retained = matches.get(matches.size() - 1);
        SettingLine original = (SettingLine) lines.get(retained);
        lines.set(retained, original.withValue(safeValue));
        for (int i = 0; i < matches.size() - 1; i++) {
            int index = matches.get(i);
            SettingLine duplicate = (SettingLine) lines.get(index);
            lines.set(index, new CommentLine(DUPLICATE_MARKER
                    + redactSensitiveAssignments(duplicate.render())));
        }
    }

    public void remove(String key) {
        validateKey(key);
        lines.removeIf(line -> line instanceof SettingLine setting && setting.key.equals(key));
    }

    public String render() {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) result.append(newline);
            result.append(lines.get(i).render());
        }
        if (finalNewline && (!lines.isEmpty() || result.length() > 0)) result.append(newline);
        return result.toString();
    }

    /**
     * Renders the document for export. Redaction here is deliberately conservative: any sensitive
     * assignment is masked wherever it appears, including inside comments the user wrote, because
     * over-redacting a diagnostic bundle is harmless while under-redacting one is not.
     */
    public String renderRedacted() {
        IbcConfigDocument copy = copy();
        for (int i = 0; i < copy.lines.size(); i++) {
            Line line = copy.lines.get(i);
            if (line instanceof SettingLine setting && isSensitiveKey(setting.key) && !setting.value.isBlank()) {
                copy.lines.set(i, setting.withValue("[REDACTED]"));
            } else if (line instanceof CommentLine comment) {
                copy.lines.set(i, new CommentLine(redactSensitiveAssignments(comment.raw())));
            } else if (line instanceof RawLine raw) {
                copy.lines.set(i, new RawLine(redactSensitiveAssignments(raw.raw())));
            }
        }
        return copy.render();
    }

    /**
     * Redacts sensitive assignments embedded in comments or otherwise unparsed lines. Earlier
     * releases could copy duplicate password settings into comments, but user-authored comments
     * and malformed lines must be treated just as conservatively because they are also persisted.
     */
    public void redactSensitiveComments() {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i) instanceof CommentLine comment) {
                lines.set(i, new CommentLine(redactSensitiveAssignments(comment.raw())));
            } else if (lines.get(i) instanceof RawLine raw) {
                lines.set(i, new RawLine(redactSensitiveAssignments(raw.raw())));
            }
        }
    }

    /** Removes every sensitive value before a manager-owned configuration is persisted. */
    public void sanitizeSensitiveValues() {
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (line instanceof SettingLine setting && isSensitiveKey(setting.key)) {
                lines.set(i, setting.withValue(""));
            } else if (line instanceof CommentLine comment) {
                lines.set(i, new CommentLine(redactSensitiveAssignments(comment.raw())));
            } else if (line instanceof RawLine raw) {
                lines.set(i, new RawLine(redactSensitiveAssignments(raw.raw())));
            }
        }
    }

    public boolean hasPlaintextSecret() {
        for (Line line : lines) {
            if (line instanceof SettingLine setting
                    && isSensitiveKey(setting.key) && !setting.value.isEmpty()) return true;
            if (line instanceof CommentLine comment
                    && containsSensitiveAssignment(comment.raw())) return true;
            if (line instanceof RawLine raw
                    && containsSensitiveAssignment(raw.raw())) return true;
        }
        return false;
    }

    private static boolean containsSensitiveAssignment(String raw) {
        return !redactSensitiveAssignments(raw).equals(raw);
    }

    private static boolean isSensitiveKey(String key) {
        return IbcConfigSchema.sensitiveKeys().stream().anyMatch(candidate -> candidate.equalsIgnoreCase(key));
    }

    static String redactSensitiveAssignments(String raw) {
        String result = raw;
        for (Pattern pattern : SENSITIVE_ASSIGNMENTS) {
            result = pattern.matcher(result).replaceAll("$1[REDACTED]");
        }
        return result;
    }

    private static List<Pattern> buildSensitiveAssignmentPatterns() {
        List<Pattern> patterns = new ArrayList<>();
        for (String key : IbcConfigSchema.sensitiveKeys()) {
            patterns.add(Pattern.compile(
                    "(?i)(\\b\\Q" + key + "\\E\\s*=)(?=[^\\r\\n])[^\\r\\n]+"));
        }
        return List.copyOf(patterns);
    }

    public int lineCount() {
        return lines.size();
    }

    private static Line parseLine(String raw) {
        if (raw.isBlank()) return new BlankLine(raw);
        String leading = raw.stripLeading();
        if (leading.startsWith("#") || leading.startsWith(";")) return new CommentLine(raw);
        Matcher matcher = SETTING_PATTERN.matcher(raw);
        if (matcher.matches()) {
            return new SettingLine(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4), matcher.group(5));
        }
        return new RawLine(raw);
    }

    private static String detectNewline(String text) {
        int crlf = text.indexOf("\r\n");
        int lf = text.indexOf('\n');
        int cr = text.indexOf('\r');
        if (crlf >= 0 && (lf < 0 || crlf <= lf)) return "\r\n";
        if (lf >= 0) return "\n";
        if (cr >= 0) return "\r";
        return System.lineSeparator();
    }

    private static void validateKey(String key) {
        Objects.requireNonNull(key, "key");
        if (!key.matches("[A-Za-z][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("Invalid IBC configuration key: " + key);
        }
    }

    private sealed interface Line permits BlankLine, CommentLine, RawLine, SettingLine {
        String render();
    }

    private record BlankLine(String raw) implements Line {
        @Override public String render() { return raw; }
    }

    private record CommentLine(String raw) implements Line {
        @Override public String render() { return raw; }
    }

    private record RawLine(String raw) implements Line {
        @Override public String render() { return raw; }
    }

    private static final class SettingLine implements Line {
        private final String leading;
        private final String key;
        private final String beforeEquals;
        private final String afterEquals;
        private final String value;

        private SettingLine(String leading, String key, String beforeEquals, String afterEquals, String value) {
            this.leading = leading;
            this.key = key;
            this.beforeEquals = beforeEquals;
            this.afterEquals = afterEquals;
            this.value = value;
        }

        private SettingLine withValue(String newValue) {
            return new SettingLine(leading, key, beforeEquals, afterEquals, newValue);
        }

        @Override
        public String render() {
            return leading + key + beforeEquals + "=" + afterEquals + value;
        }
    }
}
