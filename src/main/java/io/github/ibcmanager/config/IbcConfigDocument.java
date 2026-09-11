package io.github.ibcmanager.config;

import io.github.ibcmanager.security.TextSafety;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Comment/order-preserving representation of an IBC Java-properties configuration.
 *
 * <p>IBC loads config.ini through {@link Properties#load(java.io.InputStream)}. This class
 * therefore uses Java Properties grammar for parsing and emits an ASCII/ISO-8859-1-safe canonical
 * form for files consumed by IBC. The ordinary {@link #render()} method remains lossless for
 * untouched input so the raw editor can preserve comments and formatting.</p>
 */
public final class IbcConfigDocument {
    private static final List<Pattern> SENSITIVE_ASSIGNMENTS = buildSensitiveAssignmentPatterns();
    private static final String DUPLICATE_MARKER = "# IBC Manager disabled duplicate: ";

    private final List<Entry> entries;
    private final String newline;
    private final boolean finalNewline;
    private final int physicalLineCount;
    private final LinkedHashMap<String, String> authoritativeSettings;
    private final boolean formattingMatchesSemantics;

    private IbcConfigDocument(List<Entry> entries, String newline, boolean finalNewline,
            int physicalLineCount, Map<String, String> authoritativeSettings,
            boolean formattingMatchesSemantics) {
        this.entries = new ArrayList<>(entries);
        this.newline = newline;
        this.finalNewline = finalNewline;
        this.physicalLineCount = physicalLineCount;
        this.authoritativeSettings = new LinkedHashMap<>(authoritativeSettings);
        this.formattingMatchesSemantics = formattingMatchesSemantics;
    }

    /**
     * Parses Unicode editor text with one full-file {@link Properties#load(java.io.Reader)} pass
     * as the authoritative semantic interpretation. A separate scanner retains comments, ordering,
     * and physical formatting for the raw editor.
     */
    public static IbcConfigDocument parse(String text) {
        Objects.requireNonNull(text, "text");
        return parseDecodedText(text, loadReaderSettings(text));
    }

    /**
     * Parses bytes exactly as IBC reads them through
     * {@link Properties#load(java.io.InputStream)}: one ISO-8859-1 character per byte, with Java
     * Properties escapes applied afterwards. The full-file JDK parser is authoritative; the
     * formatting scanner is used only to preserve comments, ordering, and line endings.
     */
    public static IbcConfigDocument parseBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return parseDecodedText(new String(bytes, StandardCharsets.ISO_8859_1),
                loadByteSettings(bytes));
    }

    /**
     * Migrates manager-owned configuration files written by IBC Manager 1.0.14 and earlier.
     * Those releases wrote raw UTF-8, while IBC reads ISO-8859-1. Only manager-owned files may use
     * this compatibility path; user-selected IBC files must be parsed with {@link #parseBytes(byte[])}.
     */
    public static IbcConfigDocument parseLegacyManagerBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (hasUtf8Bom(bytes)) return parse(decodeUtf8(bytes, 3, bytes.length - 3));
        if (containsNonAscii(bytes)) {
            String utf8 = tryDecodeUtf8(bytes);
            if (utf8 != null) return parse(utf8);
        }
        return parseBytes(bytes);
    }

    public static IbcConfigDocument empty() {
        return new IbcConfigDocument(List.of(), System.lineSeparator(), true, 0, Map.of(), true);
    }

    public IbcConfigDocument copy() {
        List<Entry> copy = new ArrayList<>(entries.size());
        for (Entry entry : entries) copy.add(entry.copy());
        return new IbcConfigDocument(copy, newline, finalNewline, physicalLineCount,
                authoritativeSettings, formattingMatchesSemantics);
    }

    /**
     * Returns whether the formatting-preservation scanner and IBC's authoritative full-file Java
     * Properties parser assign identical key/value semantics to the source document.
     */
    public boolean formattingMatchesIbcSemantics() {
        return formattingMatchesSemantics;
    }

    /**
     * Creates an explicit canonical representation of the authoritative IBC semantics. Comments and
     * unusual formatting are intentionally discarded. This is used only after user confirmation or
     * for an ephemeral runtime copy of an external configuration.
     */
    public IbcConfigDocument canonicalizedCopy() {
        List<Entry> canonicalEntries = new ArrayList<>(authoritativeSettings.size());
        for (Map.Entry<String, String> setting : authoritativeSettings.entrySet()) {
            canonicalEntries.add(PropertyEntry.created(setting.getKey(), setting.getValue()));
        }
        return new IbcConfigDocument(canonicalEntries, newline, finalNewline,
                canonicalEntries.size(), authoritativeSettings, true);
    }

    public Optional<String> get(String key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(authoritativeSettings.get(key));
    }

    public List<String> getAll(String key) {
        Objects.requireNonNull(key, "key");
        List<String> values = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry instanceof PropertyEntry property && property.key().equals(key)) values.add(property.value());
        }
        return List.copyOf(values);
    }

    public Map<String, String> activeSettings() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(authoritativeSettings));
    }

    /** Keys whose raw lexical value contains a single backslash that Java Properties may consume. */
    public Set<String> suspiciousBackslashKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Entry entry : entries) {
            if (entry instanceof PropertyEntry property && property.suspiciousBackslash()) {
                keys.add(property.key());
            }
        }
        return Collections.unmodifiableSet(keys);
    }

    public Set<String> duplicateKeys() {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (Entry entry : entries) {
            if (entry instanceof PropertyEntry property && !seen.add(property.key())) duplicates.add(property.key());
        }
        return Collections.unmodifiableSet(duplicates);
    }

    public void set(String key, String value) {
        requireUnambiguousFormatting("modify");
        validateKey(key);
        String safeValue = value == null ? "" : value;
        TextSafety.requireSingleLine(safeValue, "IBC configuration value for " + key);
        List<Integer> matches = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            if (entry instanceof PropertyEntry property && property.key().equals(key)) matches.add(index);
        }
        if (matches.isEmpty()) {
            if (!entries.isEmpty() && !(entries.get(entries.size() - 1) instanceof BlankEntry)) {
                entries.add(new BlankEntry(""));
            }
            entries.add(PropertyEntry.created(key, safeValue));
            authoritativeSettings.put(key, safeValue);
            return;
        }
        int retained = matches.get(matches.size() - 1);
        PropertyEntry original = (PropertyEntry) entries.get(retained);
        entries.set(retained, original.withValue(safeValue));
        for (int index = 0; index < matches.size() - 1; index++) {
            int duplicateIndex = matches.get(index);
            PropertyEntry duplicate = (PropertyEntry) entries.get(duplicateIndex);
            entries.set(duplicateIndex, new CommentEntry(DUPLICATE_MARKER
                    + redactSensitiveAssignments(duplicate.render())));
        }
        authoritativeSettings.put(key, safeValue);
    }

    public void remove(String key) {
        requireUnambiguousFormatting("modify");
        validateKey(key);
        entries.removeIf(entry -> entry instanceof PropertyEntry property && property.key().equals(key));
        authoritativeSettings.remove(key);
    }

    /** Lossless for untouched input; modified properties are emitted with safe Java escaping. */
    public String render() {
        return joinEntries(false);
    }

    /**
     * Canonical bytes for IBC. Output is pure ASCII and is validated by a real
     * {@code Properties.load(InputStream)} round-trip before it is returned.
     */
    public byte[] toIbcBytes() {
        requireUnambiguousFormatting("write");
        String canonical = joinEntries(true);
        byte[] bytes = canonical.getBytes(StandardCharsets.ISO_8859_1);
        Properties loaded = new Properties();
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            loaded.load(input);
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("Generated IBC configuration is not valid Java Properties data", ex);
        }
        Map<String, String> expected = activeSettings();
        Map<String, String> actual = new LinkedHashMap<>();
        for (String name : loaded.stringPropertyNames()) actual.put(name, loaded.getProperty(name));
        if (!actual.equals(expected)) {
            throw new IllegalStateException("Generated IBC configuration changes property semantics");
        }
        return bytes;
    }

    public String renderRedacted() {
        IbcConfigDocument copy = copy();
        for (int index = 0; index < copy.entries.size(); index++) {
            Entry entry = copy.entries.get(index);
            if (entry instanceof PropertyEntry property && isSensitiveKey(property.key())
                    && !property.value().isBlank()) {
                copy.entries.set(index, property.withValue("[REDACTED]"));
            } else if (entry instanceof PropertyEntry property
                    && containsSensitiveAssignment(property.render())) {
                copy.entries.set(index, new RawEntry(redactSensitiveAssignments(property.render())));
            } else if (entry instanceof TextEntry textEntry) {
                copy.entries.set(index, textEntry.withRaw(redactSensitiveAssignments(textEntry.raw())));
            }
        }
        if (copy.hasPlaintextSecret() && !copy.formattingMatchesIbcSemantics()) {
            // A malformed formatting model must never cause diagnostics to expose a secret that
            // the authoritative full-file Properties parser recognized. Canonicalize the semantic
            // map before redacting rather than trusting the ambiguous raw scanner output.
            return copy.canonicalizedCopy().renderRedacted();
        }
        return copy.render();
    }

    public void redactSensitiveComments() {
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            if (entry instanceof PropertyEntry property
                    && !isSensitiveKey(property.key())
                    && containsSensitiveAssignment(property.render())) {
                entries.set(index, new RawEntry(redactSensitiveAssignments(property.render())));
            } else if (entry instanceof TextEntry textEntry) {
                entries.set(index, textEntry.withRaw(redactSensitiveAssignments(textEntry.raw())));
            }
        }
    }

    public void sanitizeSensitiveValues() {
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            if (entry instanceof PropertyEntry property && isSensitiveKey(property.key())) {
                entries.set(index, property.withValue(""));
            } else if (entry instanceof PropertyEntry property
                    && containsSensitiveAssignment(property.render())) {
                entries.set(index, new RawEntry(redactSensitiveAssignments(property.render())));
            } else if (entry instanceof TextEntry textEntry) {
                entries.set(index, textEntry.withRaw(redactSensitiveAssignments(textEntry.raw())));
            }
        }
        for (Map.Entry<String, String> setting : authoritativeSettings.entrySet()) {
            if (isSensitiveKey(setting.getKey())) setting.setValue("");
        }
    }

    public boolean hasPlaintextSecret() {
        for (Map.Entry<String, String> setting : authoritativeSettings.entrySet()) {
            if (isSensitiveKey(setting.getKey()) && !setting.getValue().isEmpty()) return true;
        }
        for (Entry entry : entries) {
            if (entry instanceof PropertyEntry property
                    && isSensitiveKey(property.key()) && !property.value().isEmpty()) return true;
            if (entry instanceof PropertyEntry property && !isSensitiveKey(property.key())
                    && containsSensitiveAssignment(property.render())) return true;
            if (entry instanceof TextEntry textEntry
                    && containsSensitiveAssignment(textEntry.raw())) return true;
        }
        return false;
    }

    public int lineCount() {
        return physicalLineCount;
    }


    private void requireUnambiguousFormatting(String operation) {
        if (!formattingMatchesSemantics) {
            throw new IllegalStateException("Cannot " + operation
                    + " an IBC configuration whose raw formatting and full-file Java Properties "
                    + "semantics disagree; correct the syntax or call canonicalizedCopy() explicitly");
        }
    }

    private String joinEntries(boolean canonicalProperties) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0) result.append(newline);
            Entry entry = entries.get(index);
            result.append(canonicalProperties && entry instanceof PropertyEntry property
                    ? property.renderCanonical() : entry.render());
        }
        if (finalNewline && (!entries.isEmpty() || result.length() > 0)) result.append(newline);
        return result.toString();
    }

    private static IbcConfigDocument parseDecodedText(String text, Map<String, String> authoritative) {
        String newline = detectNewline(text);
        boolean finalNewline = text.endsWith("\n") || text.endsWith("\r");
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] physicalLines = normalized.split("\n", -1);
        int count = finalNewline && physicalLines.length > 0 ? physicalLines.length - 1 : physicalLines.length;
        List<Entry> entries = new ArrayList<>();
        int index = 0;
        while (index < count) {
            String first = physicalLines[index];
            String stripped = first.stripLeading();
            if (first.isBlank()) {
                entries.add(new BlankEntry(first));
                index++;
                continue;
            }
            if (stripped.startsWith("#") || stripped.startsWith("!")) {
                entries.add(new CommentEntry(first));
                index++;
                continue;
            }

            StringBuilder raw = new StringBuilder(first);
            int physicalInEntry = 1;
            while (hasContinuation(physicalLines[index]) && index + 1 < count) {
                index++;
                raw.append(newline).append(physicalLines[index]);
                physicalInEntry++;
            }
            entries.add(parsePropertyEntry(raw.toString(), physicalInEntry));
            index++;
        }
        Map<String, String> scanner = scannerSettings(entries);
        LinkedHashMap<String, String> ordered = orderAuthoritativeSettings(authoritative, entries);
        return new IbcConfigDocument(entries, newline, finalNewline, count, ordered,
                scanner.equals(authoritative));
    }

    private static Map<String, String> loadByteSettings(byte[] bytes) {
        Properties properties = new Properties();
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            properties.load(input);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not parse IBC configuration", ex);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid Java Properties escape in IBC configuration", ex);
        }
        return propertiesMap(properties);
    }

    private static Map<String, String> loadReaderSettings(String text) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(text));
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not parse IBC configuration", ex);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid Java Properties escape in IBC configuration", ex);
        }
        return propertiesMap(properties);
    }

    private static Map<String, String> propertiesMap(Properties properties) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(properties.stringPropertyNames());
        Collections.sort(names);
        for (String name : names) values.put(name, properties.getProperty(name));
        return values;
    }

    private static Map<String, String> scannerSettings(List<Entry> entries) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (entry instanceof PropertyEntry property) values.put(property.key(), property.value());
        }
        return values;
    }

    private static LinkedHashMap<String, String> orderAuthoritativeSettings(
            Map<String, String> authoritative, List<Entry> entries) {
        LinkedHashMap<String, String> ordered = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (entry instanceof PropertyEntry property && authoritative.containsKey(property.key())) {
                ordered.put(property.key(), authoritative.get(property.key()));
            }
        }
        List<String> remaining = authoritative.keySet().stream()
                .filter(key -> !ordered.containsKey(key)).sorted().toList();
        for (String key : remaining) ordered.put(key, authoritative.get(key));
        return ordered;
    }

    private static Entry parsePropertyEntry(String raw, int physicalLines) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(raw + "\n"));
        } catch (IOException | IllegalArgumentException ex) {
            // The full-file parser has already accepted the document. The formatting scanner is
            // deliberately fail-soft: an entry it cannot isolate safely remains raw and the
            // semantic mismatch is reported instead of replacing the authoritative IBC map.
            return new RawEntry(raw);
        }
        if (properties.size() != 1) {
            return new RawEntry(raw);
        }
        String key = properties.stringPropertyNames().iterator().next();
        String value = properties.getProperty(key);
        String prefix = physicalLines == 1 ? simpleValuePrefix(raw) : null;
        return new PropertyEntry(raw, key, value, prefix, false,
                containsSuspiciousBackslash(raw));
    }

    private static boolean containsSuspiciousBackslash(String raw) {
        int valueStart = rawValueStart(raw);
        if (valueStart >= raw.length()) return false;
        String value = raw.substring(valueStart);
        boolean pathLike = value.matches("(?s)^[A-Za-z]:\\\\.*") || value.startsWith("\\\\");
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != '\\') continue;
            int runStart = index;
            while (index < value.length() && value.charAt(index) == '\\') index++;
            int runLength = index - runStart;
            if ((runLength & 1) == 0) {
                index--;
                continue;
            }
            if (index >= value.length()) return true;
            char escaped = value.charAt(index);
            if (escaped == '\r' || escaped == '\n') {
                index--;
                continue;
            }
            if (pathLike) return true;
            if (escaped == 'u' && index + 4 < value.length()
                    && isHex(value.charAt(index + 1)) && isHex(value.charAt(index + 2))
                    && isHex(value.charAt(index + 3)) && isHex(value.charAt(index + 4))) {
                index += 4;
                continue;
            }
            if ("trnf\\ =:#!".indexOf(escaped) < 0) return true;
        }
        return false;
    }

    private static int rawValueStart(String raw) {
        int index = 0;
        while (index < raw.length() && isPropertyWhitespace(raw.charAt(index))) index++;
        boolean escaped = false;
        while (index < raw.length()) {
            char ch = raw.charAt(index);
            if (!escaped && (ch == '=' || ch == ':' || isPropertyWhitespace(ch))) break;
            if (!escaped && ch == '\\') escaped = true;
            else escaped = false;
            index++;
        }
        if (index < raw.length() && isPropertyWhitespace(raw.charAt(index))) {
            while (index < raw.length() && isPropertyWhitespace(raw.charAt(index))) index++;
            if (index < raw.length() && (raw.charAt(index) == '=' || raw.charAt(index) == ':')) index++;
        } else if (index < raw.length() && (raw.charAt(index) == '=' || raw.charAt(index) == ':')) {
            index++;
        }
        while (index < raw.length() && isPropertyWhitespace(raw.charAt(index))) index++;
        return index;
    }

    private static boolean isPropertyWhitespace(char value) {
        return value == ' ' || value == '\t' || value == '\f';
    }

    private static boolean isHex(char value) {
        return value >= '0' && value <= '9' || value >= 'a' && value <= 'f'
                || value >= 'A' && value <= 'F';
    }

    private static String simpleValuePrefix(String raw) {
        int length = raw.length();
        int index = 0;
        while (index < length && Character.isWhitespace(raw.charAt(index))) index++;
        boolean escaped = false;
        while (index < length) {
            char ch = raw.charAt(index);
            if (!escaped && (ch == '=' || ch == ':' || Character.isWhitespace(ch))) break;
            if (!escaped && ch == '\\') escaped = true;
            else escaped = false;
            index++;
        }
        while (index < length && Character.isWhitespace(raw.charAt(index))) index++;
        if (index < length && (raw.charAt(index) == '=' || raw.charAt(index) == ':')) index++;
        while (index < length && Character.isWhitespace(raw.charAt(index))) index++;
        return raw.substring(0, index);
    }

    private static boolean hasContinuation(String line) {
        int backslashes = 0;
        for (int index = line.length() - 1; index >= 0 && line.charAt(index) == '\\'; index--) backslashes++;
        return (backslashes & 1) == 1;
    }

    private static String escapeKey(String value) {
        StringBuilder result = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case ' ' -> result.append("\\ ");
                case '\\' -> result.append("\\\\");
                case '\t' -> result.append("\\t");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\f' -> result.append("\\f");
                case '=', ':', '#', '!' -> result.append('\\').append(ch);
                default -> appendAsciiSafe(result, ch);
            }
        }
        return result.toString();
    }

    private static String escapeValue(String value) {
        StringBuilder result = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '\\' -> result.append("\\\\");
                case '\t' -> result.append("\\t");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\f' -> result.append("\\f");
                case ' ' -> {
                    if (index == 0) result.append("\\ ");
                    else result.append(' ');
                }
                case '=', ':', '#', '!' -> result.append('\\').append(ch);
                default -> appendAsciiSafe(result, ch);
            }
        }
        return result.toString();
    }

    private static void appendAsciiSafe(StringBuilder result, char ch) {
        if (ch >= 0x20 && ch <= 0x7e) {
            result.append(ch);
        } else {
            result.append(String.format(Locale.ROOT, "\\u%04X", (int) ch));
        }
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
            patterns.add(Pattern.compile("(?i)(\\b\\Q" + key
                    + "\\E\\s*[=:])\\s*[^\\r\\n]*"));
            patterns.add(Pattern.compile("(?i)(\\b\\Q" + key
                    + "\\E)(\\s+)(?=\\S)[^\\r\\n]*"));
        }
        return List.copyOf(patterns);
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

    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb
                && (bytes[2] & 0xff) == 0xbf;
    }

    private static boolean containsNonAscii(byte[] bytes) {
        for (byte value : bytes) if ((value & 0x80) != 0) return true;
        return false;
    }

    private static String tryDecodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            return null;
        }
    }

    private static String decodeUtf8(byte[] bytes, int offset, int length) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length)).toString();
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException("IBC configuration has an invalid UTF-8 byte-order-mark payload", ex);
        }
    }

    private sealed interface Entry permits TextEntry, PropertyEntry {
        String render();
        Entry copy();
    }

    private sealed interface TextEntry extends Entry permits BlankEntry, CommentEntry, RawEntry {
        String raw();
        TextEntry withRaw(String value);
    }

    private record BlankEntry(String raw) implements TextEntry {
        @Override public String render() { return raw; }
        @Override public Entry copy() { return this; }
        @Override public TextEntry withRaw(String value) { return new BlankEntry(value); }
    }

    private record CommentEntry(String raw) implements TextEntry {
        @Override public String render() { return raw; }
        @Override public Entry copy() { return this; }
        @Override public TextEntry withRaw(String value) { return new CommentEntry(value); }
    }

    private record RawEntry(String raw) implements TextEntry {
        @Override public String render() { return raw; }
        @Override public Entry copy() { return this; }
        @Override public TextEntry withRaw(String value) { return new RawEntry(value); }
    }

    private static final class PropertyEntry implements Entry {
        private final String raw;
        private final String key;
        private final String value;
        private final String simplePrefix;
        private final boolean modified;
        private final boolean suspiciousBackslash;

        private PropertyEntry(String raw, String key, String value, String simplePrefix,
                boolean modified, boolean suspiciousBackslash) {
            this.raw = raw;
            this.key = key;
            this.value = value;
            this.simplePrefix = simplePrefix;
            this.modified = modified;
            this.suspiciousBackslash = suspiciousBackslash;
        }

        static PropertyEntry created(String key, String value) {
            return new PropertyEntry("", key, value, null, true, false);
        }

        String key() { return key; }
        String value() { return value; }
        boolean suspiciousBackslash() { return suspiciousBackslash; }

        PropertyEntry withValue(String newValue) {
            return new PropertyEntry(raw, key, newValue, simplePrefix, true, false);
        }

        String renderCanonical() {
            return escapeKey(key) + "=" + escapeValue(value);
        }

        @Override
        public String render() {
            if (!modified) return raw;
            if (simplePrefix != null) return simplePrefix + escapeValue(value);
            return renderCanonical();
        }

        @Override
        public Entry copy() {
            return new PropertyEntry(raw, key, value, simplePrefix, modified, suspiciousBackslash);
        }
    }
}
