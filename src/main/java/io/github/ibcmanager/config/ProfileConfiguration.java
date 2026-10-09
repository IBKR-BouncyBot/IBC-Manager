package io.github.ibcmanager.config;

import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.Severity;
import io.github.ibcmanager.model.TwoFactorTimeoutAction;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Single source of active settings. Legacy INI files are inputs only during migration. */
public final class ProfileConfiguration {
    private static final Set<String> INACTIVE = Set.of("secondfactorauthenticationexitinterval",
            "storesettingsonserver", "readonlylogin", "confirmorderidreset", "logcomponents");

    private ProfileConfiguration() { }

    public static boolean editableEngineSetting(String key) {
        return key != null && !IbcConfigSchema.isSensitive(key)
                && !ManagedConfigService.isProfileControlled(key)
                && !IbcCompatibilityPolicy.isUnsupportedStructuredSetting(key)
                && !INACTIVE.contains(key.toLowerCase(Locale.ROOT));
    }

    public static IbcConfigDocument template() throws IOException {
        try (InputStream input = ProfileConfiguration.class.getResourceAsStream("/default-config.ini")) {
            if (input == null) throw new IOException("Missing included engine configuration template");
            byte[] bytes = input.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new IOException("Engine template exceeds its safety limit");
            IbcConfigDocument result = IbcConfigDocument.parseBytes(bytes);
            ManagedConfigService.sanitizePersistentSecrets(result);
            return result;
        }
    }

    public static IbcConfigDocument document(Profile profile) throws IOException {
        if (!profile.profileOnlyConfiguration() || profile.credentialMode() == CredentialMode.EXISTING_CONFIG
                || !profile.baseConfigPath().toString().isBlank()) {
            throw new IOException("Complete the one-time configuration import before starting this profile");
        }
        IbcConfigDocument document = IbcConfigDocument.parse("# Generated from the Profile editor; this file is not a configuration input.\n" + template().render());
        for (var entry : profile.settings().entrySet()) {
            if (IbcConfigSchema.isSensitive(entry.getKey()) && !entry.getValue().isBlank()) {
                throw new IOException("Passwords must be stored in the profile credential store, not engine properties");
            }
            if (editableEngineSetting(entry.getKey())) {
                document.set(IbcConfigSchema.canonicalKey(entry.getKey()), entry.getValue());
            }
        }
        ManagedConfigService.applyProfileControlled(document, profile);
        // Imported obsolete controls are not another configuration path.
        document.set("SecondFactorAuthenticationExitInterval", "");
        document.set("ReadOnlyLogin", "no");
        document.set("StoreSettingsOnServer", "");
        document.remove("LogComponents");
        IbcCompatibilityPolicy.applySafeRuntimeDefaults(document);
        String errors = new ConfigValueValidator().validateManagedConfig(document).stream()
                .filter(issue -> issue.severity() == Severity.ERROR)
                .map(issue -> issue.field() + ": " + issue.message())
                .collect(java.util.stream.Collectors.joining("; "));
        if (!errors.isEmpty()) throw new IOException("Profile engine configuration is invalid: " + errors);
        return document;
    }

    /** Collapse the exact old launch-time non-secret values into one self-contained profile. */
    public static Profile importLegacy(Profile profile, IbcConfigDocument effective, CredentialMode mode)
            throws IOException {
        IbcConfigDocument merged = template();
        // Missing old properties used the engine getter's fallback, not necessarily
        // the distributed template value. An explicit blank has that same fallback
        // meaning for IBC getters and prevents migration from adding new policies.
        for (String key : merged.activeSettings().keySet()) {
            if (editableEngineSetting(key)) merged.set(key, effective.get(key).orElse(""));
        }
        for (var entry : effective.activeSettings().entrySet()) merged.set(entry.getKey(), entry.getValue());
        Map<String, String> settings = new LinkedHashMap<>();
        for (var entry : merged.activeSettings().entrySet()) {
            if (editableEngineSetting(entry.getKey())) settings.put(IbcConfigSchema.canonicalKey(entry.getKey()), entry.getValue());
        }
        String device = effective.get(ManagedConfigService.SECOND_FACTOR_DEVICE_KEY).orElse("");
        if (!device.isBlank()) settings.put(ManagedConfigService.SECOND_FACTOR_DEVICE_KEY, device.trim());
        // Translate the deprecated diagnostic alias once; it must not override the
        // profile's current LogStructure controls on every later launch.
        String alias = effective.get("LogComponents").orElse("ignore").trim().toLowerCase(Locale.ROOT);
        if (!alias.equals("ignore")) {
            settings.put("LogStructureScope", "all");
            settings.put("LogStructureWhen", switch (alias) {
                case "yes", "true" -> "open";
                case "no", "false" -> "never";
                default -> alias;
            });
        }
        return profile.toBuilder().profileOnlyConfiguration(true).baseConfigPath(Path.of(""))
                .credentialMode(mode).username(effective.get("IbLoginId").orElse(profile.username()).trim())
                .twoFactorTimeoutAction(TwoFactorTimeoutAction.EXIT).settings(settings).build();
    }
}
