package io.github.ibcmanager.validation;

import io.github.ibcmanager.engine.EmbeddedEngine;
import io.github.ibcmanager.model.TargetType;

import io.github.ibcmanager.config.ConfigValueValidator;
import io.github.ibcmanager.config.IbcCompatibilityPolicy;
import io.github.ibcmanager.config.IbcConfigDocument;
import io.github.ibcmanager.config.ManagedConfigService;
import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.Severity;
import io.github.ibcmanager.model.ValidationIssue;
import io.github.ibcmanager.runtime.IbcJavaRuntimeResolver;
import io.github.ibcmanager.runtime.OfflineApplicationLayoutResolver;
import io.github.ibcmanager.security.BoundedFileReader;
import io.github.ibcmanager.security.CredentialStore;
import io.github.ibcmanager.security.SecureFileOperations;
import io.github.ibcmanager.security.TextSafety;
import io.github.ibcmanager.security.WindowsCommandSafety;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class ProfileValidator {
    private final CredentialStore credentialStore;
    private final ConfigValueValidator configValueValidator;
    private final IbcJavaRuntimeResolver javaRuntimeResolver;
    private final OfflineApplicationLayoutResolver layoutResolver;

    public ProfileValidator(CredentialStore credentialStore) {
        this(credentialStore, new ConfigValueValidator(),
                new IbcJavaRuntimeResolver(), new OfflineApplicationLayoutResolver());
    }

    public ProfileValidator(CredentialStore credentialStore, ConfigValueValidator configValueValidator) {
        this(credentialStore, configValueValidator,
                new IbcJavaRuntimeResolver(), new OfflineApplicationLayoutResolver());
    }

    public ProfileValidator(CredentialStore credentialStore, ConfigValueValidator configValueValidator,
            IbcJavaRuntimeResolver javaRuntimeResolver) {
        this(credentialStore, configValueValidator, javaRuntimeResolver,
                new OfflineApplicationLayoutResolver());
    }

    public ProfileValidator(CredentialStore credentialStore, ConfigValueValidator configValueValidator,
            IbcJavaRuntimeResolver javaRuntimeResolver,
            OfflineApplicationLayoutResolver layoutResolver) {
        this.credentialStore = Objects.requireNonNull(credentialStore, "credentialStore");
        this.configValueValidator = Objects.requireNonNull(configValueValidator, "configValueValidator");
        this.javaRuntimeResolver = Objects.requireNonNull(javaRuntimeResolver, "javaRuntimeResolver");
        this.layoutResolver = Objects.requireNonNull(layoutResolver, "layoutResolver");
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
            error(issues, "twsMajorVersion", "Use the numeric offline IB Gateway major version, for example 1045");
        }

        if (profile.targetType() != TargetType.GATEWAY) {
            error(issues, "targetType", "TWS is no longer supported; this legacy profile cannot be started");
        }
        validatePath(profile.twsPath(), "twsPath", true, issues, requireInstalledFiles);
        validatePath(profile.twsSettingsPath(), "twsSettingsPath", true, issues, requireInstalledFiles);
        validatePath(profile.baseConfigPath(), "baseConfigPath", false, issues, false);
        validatePath(profile.ibcJavaPath(), "ibcJavaPath", false, issues, requireInstalledFiles);

        if (requireInstalledFiles) {
            try { EmbeddedEngine.verifyBundledPayload(); }
            catch (IOException ex) { error(issues, "engine", safeMessage(ex)); }
        }

        if (requireInstalledFiles && profile.twsMajorVersion().matches("[0-9]{3,5}")
                && !isEmpty(profile.twsPath())) {
            try {
                layoutResolver.resolve(profile);
            } catch (IOException | SecurityException ex) {
                error(issues, "twsPath", safeMessage(ex));
            }
            if (javaRuntimeResolver.isSupportedPlatform()) {
                try {
                    javaRuntimeResolver.resolve(profile).ifPresent(java -> {
                        if (java.major() < 17) {
                            error(issues, "ibcJavaPath", "IBC requires Java 17 or newer");
                        }
                    });
                } catch (IOException | SecurityException ex) {
                    error(issues, "ibcJavaPath", safeMessage(ex));
                }
            }
        }

        validatePort(profile.apiPort(), "apiPort", issues);
        validatePort(profile.commandServerPort(), "commandServerPort", issues);
        if (profile.apiPort() == profile.commandServerPort()) {
            error(issues, "commandServerPort", "The IBC command-server port must differ from the IB Gateway API port");
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

        if (profile.gracefulStopTimeoutSeconds() < 30 || profile.gracefulStopTimeoutSeconds() > 300) {
            error(issues, "gracefulStopTimeoutSeconds", "Graceful stop timeout must be between 30 and 300 seconds");
        }

        if (profile.forceApiPortAtLaunch()) {
            warning(issues, "forceApiPortAtLaunch", "IBC will open the IB Gateway configuration UI and force the API port on every launch");
        }

        if (profile.profileOnlyConfiguration() && (profile.credentialMode() == CredentialMode.EXISTING_CONFIG
                || !profile.baseConfigPath().toString().isBlank())) {
            error(issues, "configuration", "All configuration and credentials must be owned by the Profile editor");
        }

        if (profile.autoRecoverStartupStall() && profile.credentialMode() == CredentialMode.MANUAL) {
            warning(issues, "autoRecoverStartupStall", "Automatic stalled-start recovery can relaunch IBC, "
                    + "but unattended login cannot complete while the password must be entered manually");
        }
        if (profile.reloginAfterSecondFactorTimeout() && profile.credentialMode() == CredentialMode.MANUAL) {
            warning(issues, "reloginAfterSecondFactorTimeout", "The five-minute 2FA retry can request another "
                    + "phone approval, but a repeated login may still require the password to be entered manually");
        }

        if (profile.credentialMode() == CredentialMode.ENCRYPTED) {
            if (profile.username().isBlank()) error(issues, "username", "A username is required for encrypted credential mode");
            if (!credentialStore.isAvailable()) error(issues, "credentialMode", "Encrypted credential storage is unavailable on this operating system");
            else if (requireStoredCredential && !credentialStore.exists(profile.id())) {
                error(issues, "credentialMode", "No encrypted password has been stored for this profile");
            }
        }
        if (profile.credentialMode() == CredentialMode.EXISTING_CONFIG) {
            if (isEmpty(profile.baseConfigPath()) || !SecureFileOperations.isRegularFile(profile.baseConfigPath())) {
                error(issues, "baseConfigPath", "Select an existing readable IBC config.ini file");
            } else {
                byte[] bytes = null;
                try {
                    bytes = BoundedFileReader.readBytes(profile.baseConfigPath(),
                            ManagedConfigService.MAX_CONFIG_BYTES, "Existing IBC configuration");
                    IbcConfigDocument external = IbcConfigDocument.parseBytes(bytes);
                    issues.addAll(configValueValidator.validate(external));
                    issues.addAll(IbcCompatibilityPolicy.validateForProfile(profile, external.activeSettings()));
                } catch (IOException | IllegalArgumentException | SecurityException ex) {
                    error(issues, "baseConfigPath", "Could not validate the existing IBC configuration: "
                            + safeMessage(ex));
                } finally {
                    if (bytes != null) Arrays.fill(bytes, (byte) 0);
                }
            }
        }

        issues.addAll(configValueValidator.validate(profile.settings()));
        issues.addAll(IbcCompatibilityPolicy.validateForProfile(profile, profile.settings()));
        if (containsSetting(profile, "CommandServerPort")
                || containsSetting(profile, "OverrideTwsApiPort")
                || containsSetting(profile, "ReloginAfterSecondFactorAuthenticationTimeout")
                || containsSetting(profile, "ExitAfterSecondFactorAuthenticationTimeout")
                || containsSetting(profile, "TradingMode")
                || containsSetting(profile, "MinimizeMainWindow")
                || containsSetting(profile, "BindAddress")) {
            warning(issues, "settings", "Profile-controlled keys in advanced settings are ignored");
        }
        if (!settingValue(profile, "ControlFrom").isBlank()) {
            warning(issues, "ControlFrom", "Additional remote IBC command sources are enabled");
        }

        return new ValidationResult(issues);
    }

    private static boolean containsSetting(Profile profile, String key) {
        return profile.settings().keySet().stream().anyMatch(candidate -> candidate.equalsIgnoreCase(key));
    }

    private static String settingValue(Profile profile, String key) {
        return profile.settings().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(java.util.Map.Entry::getValue).findFirst().orElse("");
    }

    private static void validatePath(Path path, String field, boolean required,
            List<ValidationIssue> issues, boolean requireExists) {
        if (isEmpty(path)) {
            if (required) error(issues, field, "Path is required");
            return;
        }
        String value = path.toString();
        Character unsafeCharacter = WindowsCommandSafety.firstUnsafeExternalArgumentCharacter(value);
        if (unsafeCharacter != null) {
            error(issues, field, "Path contains "
                    + WindowsCommandSafety.describeUnsafeExternalCharacter(unsafeCharacter) + ". "
                    + WindowsCommandSafety.externalPathGuidance());
        }
        for (Path segment : path) {
            String name = segment.toString();
            if (name.endsWith(" ") || name.endsWith(".")) {
                error(issues, field, "Path contains a Windows-ambiguous segment ending in a space or dot");
                break;
            }
        }
        if (!path.isAbsolute()) warning(issues, field, "Use an absolute path to avoid launcher ambiguity");
        if (requireExists && !SecureFileOperations.isDirectory(path)) {
            error(issues, field, "Directory does not exist or is not a regular directory");
        }
    }

    private static void rejectUnsafeControls(String value, String field, List<ValidationIssue> issues) {
        if (TextSafety.containsConfigBreakingControl(value)) {
            error(issues, field, "Value must not contain line breaks, Unicode line separators, or NUL characters");
        }
    }

    private static void validatePort(int port, String field, List<ValidationIssue> issues) {
        if (port < 1 || port > 65535) error(issues, field, "Port must be between 1 and 65535");
    }

    private static boolean isEmpty(Path path) {
        return path == null || path.toString().isBlank();
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static void error(List<ValidationIssue> issues, String field, String message) {
        issues.add(new ValidationIssue(Severity.ERROR, field, message));
    }

    private static void warning(List<ValidationIssue> issues, String field, String message) {
        issues.add(new ValidationIssue(Severity.WARNING, field, message));
    }
}
