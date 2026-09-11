package io.github.ibcmanager.install;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Small dependency-free JSON parser used only for bounded GitHub release metadata. */
final class StrictJsonParser {
    private static final int MAX_DEPTH = 64;
    private static final int MAX_NUMBER_CHARACTERS = 128;

    private final String input;
    private int position;

    private StrictJsonParser(String input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    static Object parse(String input) throws IOException {
        StrictJsonParser parser = new StrictJsonParser(input);
        Object value = parser.parseValue(0);
        parser.skipWhitespace();
        if (!parser.atEnd()) throw parser.error("Unexpected trailing JSON content");
        return value;
    }

    private Object parseValue(int depth) throws IOException {
        if (depth > MAX_DEPTH) throw error("JSON nesting exceeds the safety limit");
        skipWhitespace();
        if (atEnd()) throw error("Unexpected end of JSON input");
        return switch (current()) {
            case '{' -> parseObject(depth + 1);
            case '[' -> parseArray(depth + 1);
            case '"' -> parseString();
            case 't' -> parseLiteral("true", Boolean.TRUE);
            case 'f' -> parseLiteral("false", Boolean.FALSE);
            case 'n' -> parseLiteral("null", null);
            default -> {
                char value = current();
                if (value == '-' || value >= '0' && value <= '9') yield parseNumber();
                throw error("Unexpected JSON token");
            }
        };
    }

    private Map<String, Object> parseObject(int depth) throws IOException {
        expect('{');
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        skipWhitespace();
        if (consume('}')) return result;
        while (true) {
            skipWhitespace();
            if (atEnd() || current() != '"') throw error("JSON object key must be a string");
            String key = parseString();
            skipWhitespace();
            expect(':');
            Object value = parseValue(depth);
            if (result.containsKey(key)) throw error("Duplicate JSON object key: " + key);
            result.put(key, value);
            skipWhitespace();
            if (consume('}')) return result;
            expect(',');
        }
    }

    private List<Object> parseArray(int depth) throws IOException {
        expect('[');
        ArrayList<Object> result = new ArrayList<>();
        skipWhitespace();
        if (consume(']')) return result;
        while (true) {
            result.add(parseValue(depth));
            skipWhitespace();
            if (consume(']')) return result;
            expect(',');
        }
    }

    private String parseString() throws IOException {
        expect('"');
        StringBuilder result = new StringBuilder();
        while (!atEnd()) {
            char value = input.charAt(position++);
            if (value == '"') {
                validateSurrogates(result);
                return result.toString();
            }
            if (value == '\\') {
                if (atEnd()) throw error("Unterminated JSON escape sequence");
                char escaped = input.charAt(position++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(parseUnicodeEscape());
                    default -> throw error("Invalid JSON escape sequence");
                }
            } else {
                if (value < 0x20) throw error("Unescaped control character in JSON string");
                result.append(value);
            }
        }
        throw error("Unterminated JSON string");
    }

    private char parseUnicodeEscape() throws IOException {
        if (position + 4 > input.length()) throw error("Incomplete JSON Unicode escape");
        int value = 0;
        for (int count = 0; count < 4; count++) {
            int digit = Character.digit(input.charAt(position++), 16);
            if (digit < 0) throw error("Invalid JSON Unicode escape");
            value = value * 16 + digit;
        }
        return (char) value;
    }

    private static void validateSurrogates(CharSequence value) throws IOException {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IOException("JSON string contains an unpaired high surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IOException("JSON string contains an unpaired low surrogate");
            }
        }
    }

    private BigDecimal parseNumber() throws IOException {
        int start = position;
        consume('-');
        if (atEnd()) throw error("Incomplete JSON number");
        if (consume('0')) {
            if (!atEnd() && Character.isDigit(current())) throw error("Leading zero in JSON number");
        } else {
            requireDigits();
        }
        if (consume('.')) requireDigits();
        if (!atEnd() && (current() == 'e' || current() == 'E')) {
            position++;
            if (!atEnd() && (current() == '+' || current() == '-')) position++;
            requireDigits();
        }
        int length = position - start;
        if (length > MAX_NUMBER_CHARACTERS) throw error("JSON number exceeds the safety limit");
        try {
            return new BigDecimal(input.substring(start, position));
        } catch (NumberFormatException ex) {
            throw error("Invalid JSON number", ex);
        }
    }

    private void requireDigits() throws IOException {
        int start = position;
        while (!atEnd() && Character.isDigit(current())) position++;
        if (start == position) throw error("JSON number requires digits");
    }

    private Object parseLiteral(String literal, Object value) throws IOException {
        if (!input.regionMatches(position, literal, 0, literal.length())) {
            throw error("Invalid JSON literal");
        }
        position += literal.length();
        return value;
    }

    private void skipWhitespace() {
        while (!atEnd()) {
            char value = current();
            if (value != ' ' && value != '\t' && value != '\r' && value != '\n') return;
            position++;
        }
    }

    private void expect(char expected) throws IOException {
        if (!consume(expected)) throw error("Expected '" + expected + "'");
    }

    private boolean consume(char expected) {
        if (atEnd() || current() != expected) return false;
        position++;
        return true;
    }

    private char current() {
        return input.charAt(position);
    }

    private boolean atEnd() {
        return position >= input.length();
    }

    private IOException error(String message) {
        return new IOException(message + " at JSON character " + position);
    }

    private IOException error(String message, Throwable cause) {
        return new IOException(message + " at JSON character " + position, cause);
    }
}
