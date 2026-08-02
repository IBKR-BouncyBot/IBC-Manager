package io.github.ibcmanager.security;

import java.util.Arrays;
import java.util.Objects;

public final class SecureChars implements AutoCloseable {
    private char[] value;

    public SecureChars(char[] value) {
        this.value = Objects.requireNonNull(value, "value").clone();
    }

    public boolean isEmpty() {
        return value.length == 0;
    }

    public int length() {
        return value.length;
    }

    public char[] copy() {
        ensureOpen();
        return value.clone();
    }

    public String revealAsString() {
        ensureOpen();
        return new String(value);
    }

    public boolean containsConfigBreakingControl() {
        ensureOpen();
        return TextSafety.containsConfigBreakingControl(value);
    }

    private void ensureOpen() {
        if (value == null) throw new IllegalStateException("Secret has already been cleared");
    }

    @Override
    public void close() {
        if (value != null) {
            Arrays.fill(value, '\0');
            value = null;
        }
    }
}
