package io.github.ibcmanager.runtime;

import io.github.ibcmanager.security.SecretRedactor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

public final class RuntimeLogBuffer {
    private static final int MAX_LINE_CHARACTERS = 64 * 1024;
    private static final int MAX_TOTAL_CHARACTERS = 4 * 1024 * 1024;
    private final int capacity;
    private final Deque<String> lines = new ArrayDeque<>();
    private int characters;

    public RuntimeLogBuffer(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public synchronized void append(String line) {
        String safe = SecretRedactor.redact(line == null ? "" : line);
        if (safe.length() > MAX_LINE_CHARACTERS) {
            safe = safe.substring(0, MAX_LINE_CHARACTERS) + " [line truncated by IBC Manager]";
        }
        while (!lines.isEmpty() && (lines.size() >= capacity
                || characters + safe.length() > MAX_TOTAL_CHARACTERS)) {
            characters -= lines.removeFirst().length();
        }
        lines.addLast(safe);
        characters += safe.length();
    }

    public synchronized void appendAll(Collection<String> values) {
        for (String value : values) append(value);
    }

    public synchronized List<String> snapshot() { return List.copyOf(new ArrayList<>(lines)); }

    public synchronized void clear() { lines.clear(); characters = 0; }

    public synchronized int size() { return lines.size(); }
}
