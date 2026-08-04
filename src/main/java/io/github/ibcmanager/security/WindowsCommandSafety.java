package io.github.ibcmanager.security;

import java.util.Objects;

/** Shared policy for values expanded by the official Windows IBC batch launcher. */
public final class WindowsCommandSafety {
    private static final String UNSAFE_EXTERNAL_ARGUMENT_CHARACTERS = "\"%!&|<>^()";
    private static final String UNSAFE_INTERNAL_ARGUMENT_CHARACTERS = "\"%!";

    private WindowsCommandSafety() { }

    public static boolean containsUnsafeExternalArgumentCharacter(CharSequence value) {
        return firstUnsafeExternalArgumentCharacter(value) != null;
    }

    public static Character firstUnsafeExternalArgumentCharacter(CharSequence value) {
        if (value == null) return null;
        if (TextSafety.containsConfigBreakingControl(value)) return '\0';
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (UNSAFE_EXTERNAL_ARGUMENT_CHARACTERS.indexOf(character) >= 0) return character;
        }
        return null;
    }

    public static String unsupportedExternalCharacters() {
        return UNSAFE_EXTERNAL_ARGUMENT_CHARACTERS;
    }

    public static String unsupportedExternalCharactersDisplay() {
        return "\"  %  !  &  |  <  >  ^  (  )";
    }

    public static String externalPathGuidance() {
        return "The official StartIBC.bat launcher re-expands paths through cmd.exe. "
                + "Unsupported characters: " + unsupportedExternalCharactersDisplay() + ". "
                + "Use a simple path such as C:\\IBC, C:\\Jts, or C:\\IBKRSettings. "
                + "A path under C:\\Program Files (x86) is intentionally rejected because of its parentheses.";
    }

    public static String describeUnsafeExternalCharacter(Character character) {
        if (character == null) return "an unsupported character";
        if (character == '\0') return "a line break, NUL, or other control character";
        return "unsupported character '" + character + "'";
    }

    public static boolean containsUnsafeInternalArgumentCharacter(CharSequence value) {
        return containsUnsafe(value, UNSAFE_INTERNAL_ARGUMENT_CHARACTERS);
    }

    public static void requireSafeExternalArgument(String value) {
        requireSafe(value, true);
    }

    public static void requireSafeInternalArgument(String value) {
        requireSafe(value, false);
    }

    private static boolean containsUnsafe(CharSequence value, String unsafeCharacters) {
        if (value == null) return false;
        if (TextSafety.containsConfigBreakingControl(value)) return true;
        for (int index = 0; index < value.length(); index++) {
            if (unsafeCharacters.indexOf(value.charAt(index)) >= 0) return true;
        }
        return false;
    }

    private static void requireSafe(String value, boolean external) {
        Objects.requireNonNull(value, "value");
        boolean unsafe = external
                ? containsUnsafeExternalArgumentCharacter(value)
                : containsUnsafeInternalArgumentCharacter(value);
        if (unsafe) {
            Character offending = external ? firstUnsafeExternalArgumentCharacter(value) : null;
            String detail = external ? describeUnsafeExternalCharacter(offending)
                    : "an unsupported quote, percent sign, exclamation mark, or control character";
            String guidance = external ? " " + externalPathGuidance() : "";
            throw new IllegalArgumentException(
                    "Value cannot be represented safely in the official IBC batch launcher: "
                            + detail + "." + guidance);
        }
    }
}
