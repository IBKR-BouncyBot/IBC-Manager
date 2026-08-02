package io.github.ibcmanager.config;

import java.util.List;
import java.util.Objects;

public record SettingDefinition(
        String key,
        String label,
        String category,
        SettingType type,
        List<String> allowedValues,
        String defaultValue,
        String description,
        boolean sensitive,
        boolean advanced) {

    public SettingDefinition {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(type, "type");
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        defaultValue = Objects.requireNonNullElse(defaultValue, "");
        description = Objects.requireNonNullElse(description, "");
    }
}
