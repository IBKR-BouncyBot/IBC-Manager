package io.github.ibcmanager.discovery;

import io.github.ibcmanager.model.TargetType;

import java.nio.file.Path;
import java.util.Objects;

public record DetectedInstallation(
        Path ibcPath,
        Path twsRoot,
        Path settingsPath,
        TargetType targetType,
        String version) {

    public DetectedInstallation {
        ibcPath = normalize(ibcPath, "ibcPath");
        twsRoot = normalize(twsRoot, "twsRoot");
        settingsPath = normalize(settingsPath, "settingsPath");
        targetType = Objects.requireNonNull(targetType, "targetType");
        version = Objects.requireNonNull(version, "version").trim();
        if (!version.matches("[0-9]{3,5}")) {
            throw new IllegalArgumentException("version must contain 3 to 5 digits");
        }
    }

    private static Path normalize(Path path, String name) {
        Objects.requireNonNull(path, name);
        return path.toAbsolutePath().normalize();
    }

    @Override
    public String toString() {
        return targetType + " " + version + "  |  IBC " + ibcPath + "  |  " + twsRoot;
    }
}
