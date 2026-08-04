package io.github.ibcmanager.config;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.model.Profile;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Locates the short-lived IBC configuration passed to the official Windows launcher.
 *
 * <p>The file deliberately lives below the validated TWS settings directory rather than below
 * the Manager data directory. {@code StartIBC.bat} expands the config argument through several
 * CMD contexts; the settings directory is already subject to the strict external-launcher path
 * policy, while a Windows user-profile path may legally contain CMD metacharacters.</p>
 */
public final class RuntimeConfigLocation {
    private static final String DIRECTORY = ".ibc-manager-runtime";
    private static final String FILE = "config.ini";

    private RuntimeConfigLocation() { }

    public static Path path(Profile profile) {
        Objects.requireNonNull(profile, "profile");
        return profile.twsSettingsPath().toAbsolutePath().normalize()
                .resolve(DIRECTORY)
                .resolve(profile.id().toString())
                .resolve(FILE)
                .normalize();
    }

    /** Location used by 1.0.14 and earlier, retained only for secure migration cleanup. */
    public static Path legacyPath(AppPaths paths, Profile profile) {
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(profile, "profile");
        return paths.runtimeDirectory(profile.id()).resolve(FILE).toAbsolutePath().normalize();
    }

    public static List<Path> cleanupCandidates(AppPaths paths, Profile profile) {
        Path current = path(profile);
        Path legacy = legacyPath(paths, profile);
        return current.equals(legacy) ? List.of(current) : List.of(current, legacy);
    }
}
