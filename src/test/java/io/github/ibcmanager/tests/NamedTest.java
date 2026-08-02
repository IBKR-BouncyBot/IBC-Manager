package io.github.ibcmanager.tests;

import java.util.Objects;

public record NamedTest(String name, ThrowingRunnable body) {
    public NamedTest {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(body, "body");
    }
}
