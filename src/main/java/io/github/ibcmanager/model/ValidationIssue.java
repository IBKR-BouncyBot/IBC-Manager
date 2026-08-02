package io.github.ibcmanager.model;

import java.util.Objects;

public record ValidationIssue(Severity severity, String field, String message) {
    public ValidationIssue {
        Objects.requireNonNull(severity, "severity");
        field = field == null ? "" : field;
        message = Objects.requireNonNullElse(message, "");
    }
}
