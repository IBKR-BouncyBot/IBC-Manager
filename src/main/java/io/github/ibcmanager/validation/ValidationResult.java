package io.github.ibcmanager.validation;

import io.github.ibcmanager.model.Severity;
import io.github.ibcmanager.model.ValidationIssue;

import java.util.List;

public record ValidationResult(List<ValidationIssue> issues) {
    public ValidationResult {
        issues = List.copyOf(issues);
    }

    public boolean isValid() {
        return issues.stream().noneMatch(issue -> issue.severity() == Severity.ERROR);
    }

    public long errorCount() {
        return issues.stream().filter(issue -> issue.severity() == Severity.ERROR).count();
    }

    public long warningCount() {
        return issues.stream().filter(issue -> issue.severity() == Severity.WARNING).count();
    }
}
