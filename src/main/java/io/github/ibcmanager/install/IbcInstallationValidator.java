package io.github.ibcmanager.install;

import io.github.ibcmanager.app.Version;
import io.github.ibcmanager.security.BoundedFileReader;
import io.github.ibcmanager.security.SecureFileOperations;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Validates that a manually selected IBC tree is the complete tested IBC baseline. */
public final class IbcInstallationValidator {
    private static final int MAX_VERSION_BYTES = 4096;
    private static final int MAX_LAUNCHER_BYTES = 1024 * 1024;
    private static final int MAX_VERSION_CLASS_BYTES = 1024 * 1024;
    private static final List<String> REQUIRED_CLASSES = List.of(
            "ibcalpha/ibc/IbcTws.class",
            "ibcalpha/ibc/IbcGateway.class",
            "ibcalpha/ibc/CommandDispatcher.class",
            "ibcalpha/ibc/RestartTask.class",
            "ibcalpha/ibc/DefaultSettings.class",
            "ibcalpha/ibc/IbcVersionInfo.class");
    private static final List<String> REQUIRED_LAUNCHER_MARKERS = List.of(
            "ibc.jar",
            "/on2fatimeout",
            "ibcsessionid",
            "ibc is paused",
            "starting ibc with this command:",
            "jxbrowser_opt",
            "-djxbrowserkey=");

    public InstallationInfo validate(Path requestedRoot) throws IOException, IbcInstallationException {
        Path root = Objects.requireNonNull(requestedRoot, "requestedRoot").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)) {
            throw new IbcInstallationException("IBC installation directory must not be a symbolic link.");
        }
        if (!SecureFileOperations.isDirectory(root)) {
            throw new IbcInstallationException("IBC installation directory does not exist: " + root);
        }
        for (String required : List.of("IBC.jar", "version", "config.ini", "LICENSE.txt")) {
            if (!SecureFileOperations.isRegularFile(root.resolve(required))) {
                throw new IbcInstallationException("IBC installation is missing " + required + '.');
            }
        }
        Path startScript = root.resolve("scripts").resolve("StartIBC.bat");
        if (!SecureFileOperations.isRegularFile(startScript)) {
            throw new IbcInstallationException("IBC installation is missing scripts\\StartIBC.bat.");
        }
        String version = readVersion(root);
        if (!Version.IBC_BASELINE.equals(version)) {
            throw new IbcInstallationException("Expected IBC " + Version.IBC_BASELINE
                    + " but the selected installation contains version " + version + '.');
        }
        try (JarFile jar = new JarFile(root.resolve("IBC.jar").toFile(), true)) {
            for (String requiredClass : REQUIRED_CLASSES) {
                if (jar.getJarEntry(requiredClass) == null) {
                    throw new IbcInstallationException("IBC.jar is not the complete tested "
                            + Version.IBC_BASELINE + " build; missing required program class: "
                            + requiredClass + '.');
                }
            }
            String jarVersion = readJarVersion(jar);
            if (!Version.IBC_BASELINE.equals(jarVersion)) {
                throw new IbcInstallationException("IBC installation is internally inconsistent: the version file "
                        + "reports " + version + " but IBC.jar reports " + jarVersion + '.');
            }
        }
        String launcher = BoundedFileReader.readString(startScript, StandardCharsets.UTF_8,
                MAX_LAUNCHER_BYTES, "IBC StartIBC.bat");
        String normalizedLauncher = launcher.toLowerCase(Locale.ROOT);
        for (String marker : REQUIRED_LAUNCHER_MARKERS) {
            if (!normalizedLauncher.contains(marker)) {
                if ("ibc.jar".equals(marker)) {
                    throw new IbcInstallationException("scripts\\StartIBC.bat does not reference IBC.jar.");
                }
                throw new IbcInstallationException("scripts\\StartIBC.bat is not compatible with tested IBC "
                        + Version.IBC_BASELINE + "; missing launcher capability: " + marker + '.');
            }
        }
        return new InstallationInfo(root, version, root.resolve("IBC.jar"), startScript);
    }

    private static String readJarVersion(JarFile jar) throws IOException, IbcInstallationException {
        JarEntry entry = jar.getJarEntry("ibcalpha/ibc/IbcVersionInfo.class");
        if (entry == null) throw new IbcInstallationException("IBC.jar is missing IbcVersionInfo.class.");
        long declaredSize = entry.getSize();
        if (declaredSize > MAX_VERSION_CLASS_BYTES) {
            throw new IbcInstallationException("IBC.jar version class exceeds its safety limit.");
        }
        byte[] bytes;
        try (InputStream input = jar.getInputStream(entry)) {
            bytes = input.readNBytes(MAX_VERSION_CLASS_BYTES + 1);
        }
        if (bytes.length > MAX_VERSION_CLASS_BYTES) {
            throw new IbcInstallationException("IBC.jar version class exceeds its safety limit.");
        }
        try {
            String value = ClassFileStringConstantReader.read(bytes, "IBC_VERSION").trim();
            if (value.isEmpty() || value.length() > 32 || !value.matches("[0-9]+(?:\\.[0-9]+){2}")) {
                throw new IbcInstallationException("IBC.jar contains an invalid embedded version.");
            }
            return value;
        } catch (IOException ex) {
            throw new IbcInstallationException("Could not verify the embedded IBC.jar version.", ex);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    public boolean isValid(Path root) {
        if (root == null) return false;
        try {
            validate(root);
            return true;
        } catch (IOException | IbcInstallationException | SecurityException ex) {
            return false;
        }
    }

    private static String readVersion(Path root) throws IOException, IbcInstallationException {
        String value = BoundedFileReader.readString(root.resolve("version"), StandardCharsets.UTF_8,
                MAX_VERSION_BYTES, "IBC version file").trim();
        if (value.startsWith("\uFEFF")) value = value.substring(1).trim();
        if (value.isEmpty() || value.length() > 32 || !value.matches("[0-9]+(?:\\.[0-9]+){2}")) {
            throw new IbcInstallationException("IBC version file is invalid.");
        }
        return value;
    }

    public record InstallationInfo(Path root, String version, Path jar, Path startScript) {
        public InstallationInfo {
            root = root.toAbsolutePath().normalize();
            jar = jar.toAbsolutePath().normalize();
            startScript = startScript.toAbsolutePath().normalize();
            Objects.requireNonNull(version, "version");
        }
    }
}
