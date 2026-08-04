package io.github.ibcmanager.runtime;

import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.TargetType;
import io.github.ibcmanager.security.SecureFileOperations;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Resolves the same primary/alternate offline installation layout used by StartIBC.bat. */
public final class OfflineApplicationLayoutResolver {
    public Layout resolve(Profile profile) throws IOException {
        Objects.requireNonNull(profile, "profile");
        Path root = profile.twsPath().toAbsolutePath().normalize();
        String version = profile.twsMajorVersion();
        Path tws = root.resolve(version);
        Path gateway = root.resolve("ibgateway").resolve(version);
        Path primary = profile.targetType() == TargetType.GATEWAY ? gateway : tws;
        Path alternate = profile.targetType() == TargetType.GATEWAY ? tws : gateway;
        Path selected;
        String vmOptionsName;
        if (hasJars(primary)) {
            selected = primary;
            vmOptionsName = profile.targetType() == TargetType.GATEWAY
                    ? "ibgateway.vmoptions" : "tws.vmoptions";
        } else if (hasJars(alternate)) {
            selected = alternate;
            vmOptionsName = profile.targetType() == TargetType.GATEWAY
                    ? "tws.vmoptions" : "ibgateway.vmoptions";
        } else {
            throw new IOException("Offline TWS/Gateway " + version
                    + " does not contain a jars directory in either supported layout");
        }
        Path jars = SecureFileOperations.isDirectory(selected.resolve("jars"))
                ? selected.resolve("jars") : selected.resolve("JARS");
        Path install4j = selected.resolve(".install4j");
        Path vmOptions = selected.resolve(vmOptionsName);
        if (!SecureFileOperations.isDirectory(install4j)) {
            throw new IOException("Offline TWS/Gateway installation is missing .install4j: " + selected);
        }
        if (!SecureFileOperations.isRegularFile(vmOptions)) {
            throw new IOException("Offline TWS/Gateway installation is missing " + vmOptionsName
                    + ": " + selected);
        }
        return new Layout(selected.toAbsolutePath().normalize(), jars.toAbsolutePath().normalize(),
                install4j.toAbsolutePath().normalize(), vmOptions.toAbsolutePath().normalize());
    }

    private static boolean hasJars(Path directory) {
        return SecureFileOperations.isDirectory(directory.resolve("jars"))
                || SecureFileOperations.isDirectory(directory.resolve("JARS"));
    }

    public record Layout(Path programPath, Path jarsPath, Path install4jPath, Path vmOptionsPath) { }
}
