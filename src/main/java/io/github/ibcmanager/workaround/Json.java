package io.github.ibcmanager.workaround;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small bounded JSON reader for the public release catalogue; no executable content. */
final class Json {
    private final String text;
    private int position;
    private Json(String text) { this.text = text; }
    static Object read(String text) throws IOException {
        if (text.length() > 8 * 1024 * 1024) throw new IOException("Release catalogue exceeds limit");
        Json reader = new Json(text);
        Object value = reader.value(0);
        reader.space();
        if (reader.position != text.length()) throw new IOException("Trailing release JSON content");
        return value;
    }
    private Object value(int depth) throws IOException {
        if (depth > 32) throw new IOException("Release JSON nesting exceeds limit");
        space();
        char c = peek();
        if (c == '"') return string();
        if (c == '{') {
            position++;
            Map<String, Object> map = new LinkedHashMap<>();
            space();
            if (take('}')) return map;
            do {
                space();
                String key = string();
                space(); expect(':');
                if (map.containsKey(key)) throw new IOException("Duplicate release JSON key");
                map.put(key, value(depth + 1));
                if (map.size() > 20000) throw new IOException("Release JSON object exceeds limit");
                space();
                if (take('}')) return map;
                expect(',');
            } while (true);
        }
        if (c == '[') {
            position++;
            List<Object> list = new ArrayList<>();
            space();
            if (take(']')) return list;
            do {
                list.add(value(depth + 1));
                if (list.size() > 20000) throw new IOException("Release JSON array exceeds limit");
                space();
                if (take(']')) return list;
                expect(',');
            } while (true);
        }
        for (String word : List.of("true", "false", "null")) {
            if (text.startsWith(word, position)) {
                position += word.length();
                return word.equals("null") ? null : Boolean.valueOf(word);
            }
        }
        int start = position;
        while (position < text.length() && "-+0123456789.eE".indexOf(text.charAt(position)) >= 0) position++;
        String number = text.substring(start, position);
        if (!number.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) {
            throw new IOException("Invalid release JSON token");
        }
        return number; // Numeric values are not used by the release selector.
    }
    private String string() throws IOException {
        expect('"');
        StringBuilder result = new StringBuilder();
        while (position < text.length()) {
            char c = text.charAt(position++);
            if (c == '"') return result.toString();
            if (c < 32) throw new IOException("Control character in release JSON string");
            if (c != '\\') { result.append(c); continue; }
            char escaped = peek(); position++;
            switch (escaped) {
                case '"', '\\', '/' -> result.append(escaped);
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> {
                    if (position + 4 > text.length()) throw new IOException("Incomplete JSON Unicode escape");
                    try { result.append((char) Integer.parseInt(text.substring(position, position + 4), 16)); }
                    catch (NumberFormatException ex) { throw new IOException("Invalid JSON Unicode escape", ex); }
                    position += 4;
                }
                default -> throw new IOException("Invalid JSON string escape");
            }
        }
        throw new IOException("Unterminated release JSON string");
    }
    private void space() { while (position < text.length() && " \n\r\t".indexOf(text.charAt(position)) >= 0) position++; }
    private char peek() throws IOException {
        if (position >= text.length()) throw new IOException("Unexpected end of release JSON");
        return text.charAt(position);
    }
    private boolean take(char c) { if (position < text.length() && text.charAt(position) == c) { position++; return true; } return false; }
    private void expect(char c) throws IOException { if (!take(c)) throw new IOException("Invalid release JSON delimiter"); }
}
