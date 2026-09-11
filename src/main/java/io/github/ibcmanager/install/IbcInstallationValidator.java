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

/** Validates that an IBC tree provides the capabilities required by IBC Manager. */
public final class IbcInstallationValidator {
    private static final int MAX_VERSION_BYTES = 4096;
    private static final int MAX_LAUNCHER_BYTES = 1024 * 1024;
    private static final int MAX_HELPER_SCRIPT_BYTES = 256 * 1024;
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
            "/gateway",
            "/twspath:",
            "/twssettingspath:",
            "/ibcpath:",
            "/config:",
            "/javapath:",
            "/mode:",
            "/on2fatimeout:",
            "ibcsessionid",
            "ibc is paused",
            "starting ibc with this command:");

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
        Path scriptsDirectory = root.resolve("scripts");
        Path startScript = scriptsDirectory.resolve("StartIBC.bat");
        if (!SecureFileOperations.isRegularFile(startScript)) {
            throw new IbcInstallationException("IBC installation is missing scripts\\StartIBC.bat.");
        }

        String version = readVersion(root);
        IbcVersion installedVersion = IbcVersion.parse(version);
        IbcVersion minimumVersion = IbcVersion.parse(Version.IBC_MINIMUM_SUPPORTED_VERSION);
        if (installedVersion.compareTo(minimumVersion) < 0) {
            throw new IbcInstallationException("IBC " + version + " is older than IBC Manager's minimum "
                    + "compatible release " + minimumVersion + '.');
        }

        int requiredJavaMajor;
        try (JarFile jar = new JarFile(root.resolve("IBC.jar").toFile(), true)) {
            for (String requiredClass : REQUIRED_CLASSES) {
                if (jar.getJarEntry(requiredClass) == null) {
                    throw new IbcInstallationException("IBC.jar " + version
                            + " is not compatible with IBC Manager; missing required program class: "
                            + requiredClass + '.');
                }
            }
            JarMetadata jarMetadata = readJarMetadata(jar);
            if (!installedVersion.semanticallyEquals(IbcVersion.parse(jarMetadata.version()))) {
                throw new IbcInstallationException("IBC installation is internally inconsistent: the version file "
                        + "reports " + version + " but IBC.jar reports " + jarMetadata.version() + '.');
            }
            requiredJavaMajor = jarMetadata.requiredJavaMajor();
        }

        String launcher = BoundedFileReader.readString(startScript, StandardCharsets.UTF_8,
                MAX_LAUNCHER_BYTES, "IBC StartIBC.bat");
        String normalizedLauncher = launcher.toLowerCase(Locale.ROOT);
        for (String marker : REQUIRED_LAUNCHER_MARKERS) {
            if (!normalizedLauncher.contains(marker)) {
                if ("ibc.jar".equals(marker)) {
                    throw new IbcInstallationException("scripts\\StartIBC.bat does not reference IBC.jar.");
                }
                throw new IbcInstallationException("scripts\\StartIBC.bat from IBC " + version
                        + " is not compatible with IBC Manager; missing launcher capability: " + marker + '.');
            }
        }

        Path extraOptionsScript = scriptsDirectory.resolve("getExtraJavaOptions.ps1");
        boolean launcherUsesExtraOptionsHelper = normalizedLauncher.contains("getextrajavaoptions.ps1");
        if (launcherUsesExtraOptionsHelper) {
            if (!SecureFileOperations.isRegularFile(extraOptionsScript)) {
                throw new IbcInstallationException(
                        "IBC installation is missing scripts\\getExtraJavaOptions.ps1 required by StartIBC.bat.");
            }
            String helper = BoundedFileReader.readString(extraOptionsScript, StandardCharsets.UTF_8,
                    MAX_HELPER_SCRIPT_BYTES, "IBC getExtraJavaOptions.ps1").toLowerCase(Locale.ROOT);
            for (String marker : List.of("i4jparams.conf", "javaoptions", "write-output")) {
                if (!helper.contains(marker)) {
                    throw new IbcInstallationException(
                            "scripts\\getExtraJavaOptions.ps1 from IBC " + version
                                    + " is not compatible with IBC Manager; missing helper capability: "
                                    + marker + '.');
                }
            }
        }
        return new InstallationInfo(root, version, root.resolve("IBC.jar"), startScript, requiredJavaMajor);
    }

    private static JarMetadata readJarMetadata(JarFile jar) throws IOException, IbcInstallationException {
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
            if (value.isEmpty() || value.length() > 32) {
                throw new IbcInstallationException("IBC.jar contains an invalid embedded version.");
            }
            int classMajor = classFileMajor(bytes);
            int requiredJavaMajor = javaFeatureForClassMajor(classMajor);
            return new JarMetadata(IbcVersion.parse(value).text(), requiredJavaMajor);
        } catch (IOException ex) {
            throw new IbcInstallationException("Could not verify the embedded IBC.jar version.", ex);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private static int classFileMajor(byte[] bytes) throws IbcInstallationException {
        if (bytes.length < 8
                || (bytes[0] & 0xFF) != 0xCA
                || (bytes[1] & 0xFF) != 0xFE
                || (bytes[2] & 0xFF) != 0xBA
                || (bytes[3] & 0xFF) != 0xBE) {
            throw new IbcInstallationException("IBC.jar contains an invalid IbcVersionInfo class file.");
        }
        return ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
    }

    private static int javaFeatureForClassMajor(int classMajor) throws IbcInstallationException {
        int javaFeature = classMajor - 44;
        if (javaFeature < 1 || javaFeature > 255) {
            throw new IbcInstallationException("IBC.jar uses an unsupported Java class-file version: "
                    + classMajor + '.');
        }
        return Math.max(17, javaFeature);
    }

    private record JarMetadata(String version, int requiredJavaMajor) {
        private JarMetadata {
            Objects.requireNonNull(version, "version");
            if (requiredJavaMajor < 17) {
                throw new IllegalArgumentException("requiredJavaMajor must be at least 17");
            }
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
        if (value.isEmpty() || value.length() > 32) {
            throw new IbcInstallationException("IBC version file is invalid.");
        }
        return IbcVersion.parse(value).text();
    }

    public record InstallationInfo(Path root, String version, Path jar, Path startScript,
            int requiredJavaMajor) {
        public InstallationInfo {
            root = root.toAbsolutePath().normalize();
            jar = jar.toAbsolutePath().normalize();
            startScript = startScript.toAbsolutePath().normalize();
            Objects.requireNonNull(version, "version");
            if (requiredJavaMajor < 17) {
                throw new IllegalArgumentException("requiredJavaMajor must be at least 17");
            }
        }
    }
}
