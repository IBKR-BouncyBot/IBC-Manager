package io.github.ibcmanager.config;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.Severity;
import io.github.ibcmanager.model.TradingMode;
import io.github.ibcmanager.security.BoundedFileReader;
import io.github.ibcmanager.security.FilePermissionHardener;
import io.github.ibcmanager.security.SecureFileOperations;
import io.github.ibcmanager.storage.AtomicFileWriter;
import io.github.ibcmanager.storage.ProfileCodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Generates current profile projections; legacy INI readers exist for one-time import only. */
public final class ManagedConfigService {
    public static final String SECOND_FACTOR_DEVICE_KEY = "SecondFactorDevice";
    public static final int MAX_CONFIG_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TEMPLATE_BYTES = 1024 * 1024;
    private static final String TEMPLATE_RESOURCE = "/default-config.ini";
    private static final Set<String> PROFILE_CONTROLLED = Set.of(
            "IbLoginId", "IbPassword", "TradingMode", "MinimizeMainWindow",
            "OverrideTwsApiPort", "CommandServerPort", "BindAddress", "IbDir",
            "ReloginAfterSecondFactorAuthenticationTimeout",
            "SecondFactorAuthenticationTimeout",
            "ExitAfterSecondFactorAuthenticationTimeout", SECOND_FACTOR_DEVICE_KEY);
    private final AppPaths paths;
    private final ConfigValueValidator configValueValidator = new ConfigValueValidator();
    private final ProfileCodec profileCodec = new ProfileCodec();

    public ManagedConfigService(AppPaths paths) {
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    public Path managedConfigPath(Profile profile) {
        Objects.requireNonNull(profile, "profile");
        return paths.profileConfig(profile.id());
    }

    public Path ensureManagedConfig(Profile profile) throws IOException {
        Objects.requireNonNull(profile, "profile");
        Path target = managedConfigPath(profile);
        if (profile.profileOnlyConfiguration()) {
            writeManagedConfig(profile, ProfileConfiguration.document(profile));
            return target;
        }
        if (SecureFileOperations.isRegularFile(target)) return target;
        if (SecureFileOperations.exists(target)) {
            throw new IOException("Managed configuration is not a regular file: " + target);
        }

        IbcConfigDocument document = loadInitialDocument(profile);
        requireUnambiguousFormatting(document, "Initial IBC configuration");
        sanitizePersistentSecrets(document);
        applyProfile(document, profile);
        writeManagedConfig(profile, document);
        return target;
    }

    public IbcConfigDocument loadManagedConfig(Profile profile) throws IOException {
        if (profile.profileOnlyConfiguration()) return ProfileConfiguration.document(profile);
        Path path = ensureManagedConfig(profile);
        return readManagedConfig(path, "Managed IBC configuration");
    }

    public IbcConfigDocument loadRuntimeBase(Profile profile) throws IOException {
        Objects.requireNonNull(profile, "profile");
        if (profile.profileOnlyConfiguration()) return ProfileConfiguration.document(profile);
        if (profile.credentialMode() == CredentialMode.EXISTING_CONFIG) {
            if (isEmpty(profile.baseConfigPath()) || !SecureFileOperations.isRegularFile(profile.baseConfigPath())) {
                throw new IOException("The profile is configured to use an existing IBC config, but no readable config file is selected");
            }
            return readIbcConfig(profile.baseConfigPath(), "Existing IBC configuration");
        }
        return loadManagedConfig(profile);
    }

    /**
     * Saves a raw manager-owned document. Profile-controlled values are authoritative and are
     * re-applied, while advanced values from the raw document remain authoritative.
     */
    public void saveManagedConfig(Profile profile, IbcConfigDocument document) throws IOException {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(document, "document");
        if (profile.profileOnlyConfiguration()) throw new IOException("Edit configuration only through the Profile editor");
        requireUnambiguousFormatting(document, "Managed IBC configuration");
        if (document.hasPlaintextSecret()) {
            throw new IOException("Manager-owned persistent configuration must not contain plaintext passwords, "
                    + "including inside comment lines");
        }
        applyProfileControlled(document, profile);
        if (document.hasPlaintextSecret()) {
            throw new IOException("Profile settings would introduce a plaintext password into manager-owned configuration");
        }
        writeManagedConfig(profile, document);
    }

    /** Re-applies structured profile fields and rebuilds known advanced settings from the base plus overrides. */
    public void refreshManagedSettings(Profile profile) throws IOException {
        if (profile.profileOnlyConfiguration()) {
            writeManagedConfig(profile, ProfileConfiguration.document(profile));
            return;
        }
        IbcConfigDocument document = loadManagedConfig(profile);
        IbcConfigDocument baseline = loadInitialDocument(profile);
        requireUnambiguousFormatting(document, "Managed IBC configuration");
        requireUnambiguousFormatting(baseline, "Base IBC configuration");
        sanitizePersistentSecrets(document);
        sanitizePersistentSecrets(baseline);
        IbcCompatibilityPolicy.applySafeRuntimeDefaults(document);
        IbcCompatibilityPolicy.applySafeRuntimeDefaults(baseline);
        applyProfileControlled(document, profile);
        resetKnownAdvancedSettings(document, baseline, profile);
        applyProfileSettings(document, profile);
        if (document.hasPlaintextSecret()) {
            throw new IOException("Profile settings would introduce a plaintext password into manager-owned configuration");
        }
        writeManagedConfig(profile, document);
    }

    /**
     * Returns a profile whose structured advanced-settings view reflects the current managed
     * config.ini. This is used immediately before opening the profile editor and after a raw
     * managed-config save so both editors show and persist the same effective values.
     */
    public Profile synchronizeEditableProfile(Profile profile) throws IOException {
        Objects.requireNonNull(profile, "profile");
        if (profile.profileOnlyConfiguration() || profile.credentialMode() == CredentialMode.EXISTING_CONFIG) return profile;
        return synchronizeEditableProfile(profile, loadManagedConfig(profile));
    }

    /** Synchronizes both Profile-tab fields and advanced settings from a raw managed document. */
    public Profile synchronizeEditableProfile(Profile profile, IbcConfigDocument document) throws IOException {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(document, "document");
        if (profile.profileOnlyConfiguration() || profile.credentialMode() == CredentialMode.EXISTING_CONFIG) return profile;
        requireUnambiguousFormatting(document, "Managed IBC configuration");
        IbcConfigDocument baseline = loadInitialDocument(profile);
        requireUnambiguousFormatting(baseline, "Base IBC configuration");
        sanitizePersistentSecrets(baseline);
        IbcCompatibilityPolicy.applySafeRuntimeDefaults(baseline);
        IbcConfigDocument effective = document.copy();
        IbcCompatibilityPolicy.applySafeRuntimeDefaults(effective);
        return synchronizeEditableProfile(profile, effective, baseline);
    }

    public Profile synchronizeEditableProfile(Profile profile, IbcConfigDocument document,
            IbcConfigDocument baseline) throws IOException {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(baseline, "baseline");
        requireUnambiguousFormatting(document, "Managed IBC configuration");
        requireUnambiguousFormatting(baseline, "Base IBC configuration");
        Map<String, String> synchronizedSettings = new LinkedHashMap<>();
        for (SettingDefinition definition : IbcConfigSchema.definitions()) {
            if (definition.sensitive() || isProfileControlled(definition.key())
                    || IbcCompatibilityPolicy.isUnsupportedStructuredSetting(definition.key())) continue;
            String key = definition.key();
            var current = document.get(key);
            var base = baseline.get(key);
            if (!current.equals(base)) synchronizedSettings.put(key, current.orElse(""));
        }
        for (Map.Entry<String, String> entry : document.activeSettings().entrySet()) {
            String key = entry.getKey();
            if (IbcConfigSchema.isSensitive(key) || isProfileControlled(key)
                    || IbcConfigSchema.find(key).isPresent()) continue;
            if (!Objects.equals(entry.getValue(), baseline.get(key).orElse(null))) {
                synchronizedSettings.put(key, entry.getValue());
            }
        }
        String secondFactorDevice = document.get(SECOND_FACTOR_DEVICE_KEY)
                .orElse(settingValue(profile, SECOND_FACTOR_DEVICE_KEY));
        if (!secondFactorDevice.isBlank()) {
            synchronizedSettings.put(SECOND_FACTOR_DEVICE_KEY, secondFactorDevice);
        }

        Profile.Builder builder = profile.toBuilder().settings(synchronizedSettings);
        builder.username(document.get("IbLoginId").orElse(profile.username()));
        builder.tradingMode(parseTradingMode(document.get("TradingMode")
                .orElse(profile.tradingMode().ibcValue())));
        builder.minimizeMainWindow(parseBoolean(document, "MinimizeMainWindow", profile.minimizeMainWindow()));
        boolean migratingSecondFactorPolicy = storedProfileNeedsSecondFactorMigration(profile);
        builder.reloginAfterSecondFactorTimeout(migratingSecondFactorPolicy
                ? profile.reloginAfterSecondFactorTimeout()
                : parseBoolean(document, "ReloginAfterSecondFactorAuthenticationTimeout",
                        profile.reloginAfterSecondFactorTimeout()));
        builder.bindAddress(document.get("BindAddress").orElse(profile.bindAddress()));

        String commandPort = document.get("CommandServerPort").orElse("");
        if (commandPort.isBlank()) {
            throw new IOException("CommandServerPort must not be blank in an IBC Manager profile");
        }
        builder.commandServerPort(parsePort(commandPort, "CommandServerPort"));

        String overridePort = document.get("OverrideTwsApiPort").orElse("");
        if (overridePort.isBlank()) {
            builder.forceApiPortAtLaunch(false);
        } else {
            builder.apiPort(parsePort(overridePort, "OverrideTwsApiPort"));
            builder.forceApiPortAtLaunch(true);
        }
        return builder.build();
    }

    /**
     * A pre-2.0.0 profile is migrated in memory before its older managed config is rewritten.
     * Preserve that one-time compatibility migration so opening Profile Edit cannot restore the
     * stale pre-migration 2FA policy from config.ini. Once the profile is saved as format 4, raw
     * managed-config edits become authoritative again.
     */
    private boolean storedProfileNeedsSecondFactorMigration(Profile profile) throws IOException {
        Path storedProfile = paths.profileFile(profile.id());
        if (!SecureFileOperations.isRegularFile(storedProfile)) return false;
        try {
            String encoded = BoundedFileReader.readString(storedProfile, StandardCharsets.UTF_8,
                    ProfileCodec.MAX_ENCODED_BYTES, "Profile file");
            return profileCodec.sourceFormatVersion(encoded) < 4;
        } catch (IllegalArgumentException ex) {
            throw new IOException("Could not inspect the stored profile format for 2FA policy migration", ex);
        }
    }

    private static TradingMode parseTradingMode(String value) throws IOException {
        if (value.equalsIgnoreCase("live")) return TradingMode.LIVE;
        if (value.equalsIgnoreCase("paper")) return TradingMode.PAPER;
        throw new IOException("TradingMode must be live or paper");
    }

    private static boolean parseBoolean(IbcConfigDocument document, String key, boolean fallback)
            throws IOException {
        String value = document.get(key).orElse("");
        if (value.isBlank()) return fallback;
        if (value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("no") || value.equalsIgnoreCase("false")) return false;
        throw new IOException(key + " must be yes or no");
    }

    private static int parsePort(String value, String key) throws IOException {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) throw new NumberFormatException("out of range");
            return port;
        } catch (NumberFormatException ex) {
            throw new IOException(key + " must be a TCP port between 1 and 65535", ex);
        }
    }

    private static void resetKnownAdvancedSettings(IbcConfigDocument document,
            IbcConfigDocument baseline, Profile profile) {
        for (SettingDefinition definition : IbcConfigSchema.definitions()) {
            String key = definition.key();
            if (definition.sensitive() || isProfileControlled(key)
                    || IbcCompatibilityPolicy.isUnsupportedStructuredSetting(key)
                    || containsSetting(profile, key)) continue;
            var base = baseline.get(key);
            if (base.isPresent()) document.set(key, base.get());
            else document.remove(key);
        }
    }

    private static boolean containsSetting(Profile profile, String key) {
        return profile.settings().keySet().stream().anyMatch(candidate -> candidate.equalsIgnoreCase(key));
    }

    public static void applyProfile(IbcConfigDocument document, Profile profile) {
        applyProfileControlled(document, profile);
        applyProfileSettings(document, profile);
    }

    public static void applyProfileControlled(IbcConfigDocument document, Profile profile) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(profile, "profile");
        if (profile.credentialMode() != CredentialMode.EXISTING_CONFIG) {
            document.set("IbLoginId", profile.username());
            document.set("IbPassword", "");
        }
        document.set("TradingMode", profile.tradingMode().ibcValue());
        document.set("MinimizeMainWindow", profile.minimizeMainWindow() ? "yes" : "no");
        document.set("OverrideTwsApiPort", profile.forceApiPortAtLaunch()
                ? Integer.toString(profile.apiPort()) : "");
        document.set("ReloginAfterSecondFactorAuthenticationTimeout",
                profile.reloginAfterSecondFactorTimeout() ? "yes" : "no");
        document.set("SecondFactorAuthenticationTimeout",
                SecondFactorPolicy.configuredTimeout(profile.reloginAfterSecondFactorTimeout()));
        // Supported IBC releases consult the deprecated ExitAfter... key only when the current
        // ReloginAfter... key is blank. Always clear the legacy key so one imported
        // configuration cannot silently override the explicit profile policy.
        document.set("ExitAfterSecondFactorAuthenticationTimeout", "");
        document.set("CommandServerPort", Integer.toString(profile.commandServerPort()));
        document.set("BindAddress", profile.bindAddress());
        document.set("IbDir", "");
        document.set(SECOND_FACTOR_DEVICE_KEY, settingValue(profile, SECOND_FACTOR_DEVICE_KEY));
    }

    public static void applyProfileSettings(IbcConfigDocument document, Profile profile) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(profile, "profile");
        for (Map.Entry<String, String> entry : profile.settings().entrySet()) {
            String key = IbcConfigSchema.canonicalKey(entry.getKey());
            if (!IbcConfigSchema.isSensitive(key) && !isProfileControlled(key)
                    && !IbcCompatibilityPolicy.isUnsupportedStructuredSetting(key)) {
                document.set(key, entry.getValue());
            }
        }
    }

    public static String settingValue(Profile profile, String key) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(key, "key");
        for (Map.Entry<String, String> entry : profile.settings().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
        }
        return "";
    }

    public static boolean isProfileControlled(String key) {
        return key != null && PROFILE_CONTROLLED.stream().anyMatch(candidate -> candidate.equalsIgnoreCase(key));
    }

    public static void sanitizePersistentSecrets(IbcConfigDocument document) {
        document.sanitizeSensitiveValues();
        for (String key : IbcConfigSchema.sensitiveKeys()) document.set(key, "");
        if ("edemo".equalsIgnoreCase(document.get("IbLoginId").orElse(""))) document.set("IbLoginId", "");
        if ("demouser".equalsIgnoreCase(document.get("FIXLoginId").orElse(""))) document.set("FIXLoginId", "");
    }

    private IbcConfigDocument loadInitialDocument(Profile profile) throws IOException {
        if (!isEmpty(profile.baseConfigPath()) && SecureFileOperations.isRegularFile(profile.baseConfigPath())) {
            return readIbcConfig(profile.baseConfigPath(), "Base IBC configuration");
        }
        return IbcConfigDocument.parseBytes(loadTemplate());
    }

    private void writeManagedConfig(Profile profile, IbcConfigDocument document) throws IOException {
        requireUnambiguousFormatting(document, "Managed IBC configuration");
        IbcCompatibilityPolicy.applySafeRuntimeDefaults(document);
        String errors = java.util.stream.Stream.concat(
                        configValueValidator.validateManagedConfig(document).stream(),
                        IbcCompatibilityPolicy.validateForProfile(profile, document.activeSettings()).stream())
                .filter(issue -> issue.severity() == Severity.ERROR)
                .map(issue -> issue.field() + ": " + issue.message())
                .collect(java.util.stream.Collectors.joining("; "));
        if (!errors.isEmpty()) {
            throw new IOException("Managed IBC configuration is invalid: " + errors);
        }
        byte[] rendered = document.toIbcBytes();
        if (rendered.length > MAX_CONFIG_BYTES) {
            throw new IOException("Managed IBC configuration exceeds the " + MAX_CONFIG_BYTES + " byte safety limit");
        }
        Path target = managedConfigPath(profile);
        FilePermissionHardener.hardenDirectory(target.getParent());
        AtomicFileWriter.write(target, rendered, true);
        FilePermissionHardener.hardenFile(target);
        Path backup = target.resolveSibling(target.getFileName() + ".bak");
        if (SecureFileOperations.isRegularFile(backup)) FilePermissionHardener.hardenFile(backup);
    }

    private static void requireUnambiguousFormatting(IbcConfigDocument document, String description)
            throws IOException {
        if (!document.formattingMatchesIbcSemantics()) {
            throw new IOException(description + " contains malformed or ambiguous Java Properties syntax. "
                    + "IBC's full-file parser and the formatting-preservation scanner disagree; "
                    + "correct the legacy input syntax before retrying its one-time import.");
        }
    }

    private static IbcConfigDocument readIbcConfig(Path path, String description) throws IOException {
        return readConfig(path, description, false);
    }

    private static IbcConfigDocument readManagedConfig(Path path, String description) throws IOException {
        return readConfig(path, description, true);
    }

    private static IbcConfigDocument readConfig(Path path, String description,
            boolean allowLegacyManagerUtf8) throws IOException {
        byte[] bytes = BoundedFileReader.readBytes(path, MAX_CONFIG_BYTES, description);
        try {
            return allowLegacyManagerUtf8
                    ? IbcConfigDocument.parseLegacyManagerBytes(bytes)
                    : IbcConfigDocument.parseBytes(bytes);
        } catch (IllegalArgumentException ex) {
            throw new IOException(description + " is not a valid Java Properties file: " + path, ex);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private byte[] loadTemplate() throws IOException {
        try (InputStream stream = ManagedConfigService.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (stream == null) throw new IOException("Missing bundled IBC configuration template");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                if (count == 0) continue;
                total += count;
                if (total > MAX_TEMPLATE_BYTES) {
                    throw new IOException("Bundled IBC configuration template exceeds its safety limit");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static boolean isEmpty(Path path) {
        return path == null || path.toString().isBlank();
    }
}
