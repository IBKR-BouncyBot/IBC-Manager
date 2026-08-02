package io.github.ibcmanager.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

public final class RuntimeLogBuffer {
    private final int capacity;
    private final Deque<String> lines = new ArrayDeque<>();

    public RuntimeLogBuffer(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public synchronized void append(String line) {
        if (lines.size() == capacity) lines.removeFirst();
        lines.addLast(line == null ? "" : line);
    }

    public synchronized void appendAll(Collection<String> values) {
        for (String value : values) append(value);
    }

    public synchronized List<String> snapshot() {
        return List.copyOf(new ArrayList<>(lines));
    }

    public synchronized void clear() {
        lines.clear();
    }

    public synchronized int size() {
        return lines.size();
    }
}
