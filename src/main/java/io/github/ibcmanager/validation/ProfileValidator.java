package io.github.ibcmanager.validation;

import io.github.ibcmanager.config.ConfigValueValidator;
import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.Severity;
import io.github.ibcmanager.model.TargetType;
import io.github.ibcmanager.model.ValidationIssue;
import io.github.ibcmanager.security.CredentialStore;
import io.github.ibcmanager.security.TextSafety;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ProfileValidator {
    private final CredentialStore credentialStore;
    private final ConfigValueValidator configValueValidator;

    public ProfileValidator(CredentialStore credentialStore) {
        this(credentialStore, new ConfigValueValidator());
    }

    public ProfileValidator(CredentialStore credentialStore, ConfigValueValidator configValueValidator) {
        this.credentialStore = Objects.requireNonNull(credentialStore, "credentialStore");
        this.configValueValidator = Objects.requireNonNull(configValueValidator, "configValueValidator");
    }

    public ValidationResult validate(Profile profile, boolean requireInstalledFiles) {
        return validateInternal(profile, requireInstalledFiles, true);
    }

    public ValidationResult validateForEdit(Profile profile, boolean requireInstalledFiles) {
        return validateInternal(profile, requireInstalledFiles, false);
    }

    private ValidationResult validateInternal(Profile profile, boolean requireInstalledFiles,
            boolean requireStoredCredential) {
        Objects.requireNonNull(profile, "profile");
        List<ValidationIssue> issues = new ArrayList<>();
        if (profile.name().isBlank()) error(issues, "name", "Profile name is required");
        if (profile.name().length() > 80) error(issues, "name", "Profile name must not exceed 80 characters");
        rejectUnsafeControls(profile.name(), "name", issues);
        rejectUnsafeControls(profile.twsMajorVersion(), "twsMajorVersion", issues);
        rejectUnsafeControls(profile.bindAddress(), "bindAddress", issues);
        rejectUnsafeControls(profile.username(), "username", issues);
        if (!profile.twsMajorVersion().matches("[0-9]{3,5}")) {
            error(issues, "twsMajorVersion", "Use the numeric offline TWS/Gateway major version, for example 1045");
        }

        validatePath(profile.ibcPath(), "ibcPath", true, issues, requireInstalledFiles);
        validatePath(profile.twsPath(), "twsPath", true, issues, requireInstalledFiles);
        validatePath(profile.twsSettingsPath(), "twsSettingsPath", true, issues, requireInstalledFiles);
        validatePath(profile.baseConfigPath(), "baseConfigPath", false, issues, false);

        if (requireInstalledFiles && !isEmpty(profile.ibcPath())) {
            if (!Files.isRegularFile(profile.ibcPath().resolve("IBC.jar"))) {
                error(issues, "ibcPath", "IBC.jar was not found in the selected IBC directory");
            }
            if (!Files.isRegularFile(profile.ibcPath().resolve("scripts").resolve("StartIBC.bat"))) {
                error(issues, "ibcPath", "scripts\\StartIBC.bat was not found in the selected IBC directory");
            }
        }

        if (requireInstalledFiles && !isEmpty(profile.twsPath()) && profile.twsMajorVersion().matches("[0-9]{3,5}")) {
            Path primary = profile.targetType() == TargetType.GATEWAY
                    ? profile.twsPath().resolve("ibgateway").resolve(profile.twsMajorVersion())
                    : profile.twsPath().resolve(profile.twsMajorVersion());
            Path alternate = profile.targetType() == TargetType.GATEWAY
                    ? profile.twsPath().resolve(profile.twsMajorVersion())
                    : profile.twsPath().resolve("ibgateway").resolve(profile.twsMajorVersion());
            if (!Files.isDirectory(primary.resolve("jars")) && !Files.isDirectory(primary.resolve("JARS"))
                    && !Files.isDirectory(alternate.resolve("jars")) && !Files.isDirectory(alternate.resolve("JARS"))) {
                error(issues, "twsPath", "The selected offline TWS/Gateway version does not contain a jars directory");
            }
        }

        validatePort(profile.apiPort(), "apiPort", issues, false);
        validatePort(profile.commandServerPort(), "commandServerPort", issues, true);
        if (profile.apiPort() == profile.commandServerPort()) {
            error(issues, "commandServerPort", "The IBC command-server port must differ from the TWS/Gateway API port");
        }

        if (profile.bindAddress().isBlank()) {
            warning(issues, "bindAddress", "A blank bind address makes the IBC command server listen on all local interfaces; use 127.0.0.1 for local-only control");
        } else {
            try {
                InetAddress address = InetAddress.getByName(profile.bindAddress());
                if (!address.isLoopbackAddress()) {
                    warning(issues, "bindAddress", "The IBC command server is not restricted to a loopback address");
                }
            } catch (UnknownHostException ex) {
                error(issues, "bindAddress", "The command-server bind address is invalid or cannot be resolved");
            }
        }

        if (profile.gracefulStopTimeoutSeconds() < 3 || profile.gracefulStopTimeoutSeconds() > 300) {
            error(issues, "gracefulStopTimeoutSeconds", "Graceful stop timeout must be between 3 and 300 seconds");
        }

        if (profile.credentialMode() == CredentialMode.ENCRYPTED) {
            if (profile.username().isBlank()) error(issues, "username", "A username is required for encrypted credential mode");
            if (!credentialStore.isAvailable()) error(issues, "credentialMode", "Encrypted credential storage is unavailable on this operating system");
            else if (requireStoredCredential && !credentialStore.exists(profile.id())) {
                error(issues, "credentialMode", "No encrypted password has been stored for this profile");
            }
        }
        if (profile.credentialMode() == CredentialMode.EXISTING_CONFIG) {
            if (isEmpty(profile.baseConfigPath()) || !Files.isRegularFile(profile.baseConfigPath())) {
                error(issues, "baseConfigPath", "Select an existing readable IBC config.ini file");
            }
        }

        issues.addAll(configValueValidator.validate(profile.settings()));
        if (profile.settings().containsKey("CommandServerPort")
                || profile.settings().containsKey("OverrideTwsApiPort")
                || profile.settings().containsKey("TradingMode")
                || profile.settings().containsKey("MinimizeMainWindow")
                || profile.settings().containsKey("BindAddress")) {
            warning(issues, "settings", "Profile-controlled keys in advanced settings are ignored");
        }
        if (!profile.settings().getOrDefault("ControlFrom", "").isBlank()) {
            warning(issues, "ControlFrom", "Additional remote IBC command sources are enabled");
        }

        return new ValidationResult(issues);
    }

    private static void validatePath(Path path, String field, boolean required, List<ValidationIssue> issues,
            boolean requireExists) {
        if (isEmpty(path)) {
            if (required) error(issues, field, "Path is required");
            return;
        }
        String value = path.toString();
        if (value.indexOf('"') >= 0 || TextSafety.containsConfigBreakingControl(value)
                || value.indexOf('%') >= 0 || value.indexOf('!') >= 0) {
            error(issues, field, "Path contains characters that cannot be passed safely to the official IBC Windows launcher");
        }
        if (!path.isAbsolute()) warning(issues, field, "Use an absolute path to avoid launcher ambiguity");
        if (requireExists && !Files.isDirectory(path)) error(issues, field, "Directory does not exist");
    }

    private static void rejectUnsafeControls(String value, String field, List<ValidationIssue> issues) {
        if (TextSafety.containsConfigBreakingControl(value)) {
            error(issues, field, "Value must not contain line breaks, Unicode line separators, or NUL characters");
        }
    }

    private static void validatePort(int port, String field, List<ValidationIssue> issues, boolean nonZero) {
        int minimum = nonZero ? 1 : 1;
        if (port < minimum || port > 65535) error(issues, field, "Port must be between 1 and 65535");
    }

    private static boolean isEmpty(Path path) {
        return path == null || path.toString().isBlank();
    }

    private static void error(List<ValidationIssue> issues, String field, String message) {
        issues.add(new ValidationIssue(Severity.ERROR, field, message));
    }

    private static void warning(List<ValidationIssue> issues, String field, String message) {
        issues.add(new ValidationIssue(Severity.WARNING, field, message));
    }
}
