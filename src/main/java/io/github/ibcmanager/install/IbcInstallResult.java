package io.github.ibcmanager.install;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

public record IbcInstallResult(
        Path installationDirectory,
        String version,
        String assetName,
        String sha256,
        boolean downloaded) {

    public IbcInstallResult {
        installationDirectory = Objects.requireNonNull(installationDirectory, "installationDirectory")
                .toAbsolutePath().normalize();
        version = requireText(version, "version");
        assetName = requireText(assetName, "assetName");
        sha256 = requireText(sha256, "sha256").toLowerCase(Locale.ROOT);
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must contain 64 hexadecimal characters");
        }
    }

    private static String requireText(String value, String name) {
        String result = Objects.requireNonNull(value, name).trim();
        if (result.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return result;
    }
}
