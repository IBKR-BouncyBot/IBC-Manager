package io.github.ibcmanager.security;

public final class TextSafety {
    private TextSafety() { }

    public static boolean containsConfigBreakingControl(CharSequence value) {
        if (value == null) return false;
        for (int index = 0; index < value.length(); index++) {
            if (isUnsafe(value.charAt(index))) return true;
        }
        return false;
    }

    public static boolean containsConfigBreakingControl(char[] value) {
        if (value == null) return false;
        for (char character : value) {
            if (isUnsafe(character)) return true;
        }
        return false;
    }

    public static void requireSingleLine(CharSequence value, String label) {
        if (containsConfigBreakingControl(value)) {
            throw new IllegalArgumentException(label
                    + " must not contain line breaks, Unicode line separators, or NUL characters");
        }
    }

    private static boolean isUnsafe(char character) {
        return character == '\0' || character == '\r' || character == '\n'
                || character == '\u0085' || character == '\u2028' || character == '\u2029';
    }
}
