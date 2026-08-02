package io.github.ibcmanager.config;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.security.FilePermissionHardener;
import io.github.ibcmanager.storage.AtomicFileWriter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class ManagedConfigService {
    public static final String SECOND_FACTOR_DEVICE_KEY = "SecondFactorDevice";
    private static final String TEMPLATE_RESOURCE = "/default-config.ini";
    private static final java.util.Set<String> PROFILE_CONTROLLED = java.util.Set.of(
            "IbLoginId", "IbPassword", "TradingMode", "MinimizeMainWindow",
            "OverrideTwsApiPort", "CommandServerPort", "BindAddress", "IbDir",
            SECOND_FACTOR_DEVICE_KEY);
    private final AppPaths paths;

    public ManagedConfigService(AppPaths paths) {
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    public Path ensureManagedConfig(Profile profile) throws IOException {
        Objects.requireNonNull(profile, "profile");
        Path target = paths.profileConfig(profile.id());
        if (Files.isRegularFile(target)) return target;

        IbcConfigDocument document;
        if (!isEmpty(profile.baseConfigPath()) && Files.isRegularFile(profile.baseConfigPath())) {
            document = IbcConfigDocument.parse(Files.readString(profile.baseConfigPath(), StandardCharsets.UTF_8));
        } else {
            document = IbcConfigDocument.parse(loadTemplate());
        }
        sanitizePersistentSecrets(document);
        applyProfile(document, profile);
        saveManagedConfig(profile, document);
        return target;
    }

    public IbcConfigDocument loadManagedConfig(Profile profile) throws IOException {
        Path path = ensureManagedConfig(profile);
        return IbcConfigDocument.parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    public IbcConfigDocument loadRuntimeBase(Profile profile) throws IOException {
        Objects.requireNonNull(profile, "profile");
        if (profile.credentialMode() == CredentialMode.EXISTING_CONFIG) {
            if (isEmpty(profile.baseConfigPath()) || !Files.isRegularFile(profile.baseConfigPath())) {
                throw new IOException("The profile is configured to use an existing IBC config, but no readable config file is selected");
            }
            return IbcConfigDocument.parse(Files.readString(profile.baseConfigPath(), StandardCharsets.UTF_8));
        }
        return loadManagedConfig(profile);
    }

    public void saveManagedConfig(Profile profile, IbcConfigDocument document) throws IOException {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(document, "document");
        if (document.hasPlaintextSecret()) {
            throw new IOException("Manager-owned persistent configuration must not contain plaintext passwords, "
                    + "including inside comment lines");
        }
        applyProfile(document, profile);
        if (document.hasPlaintextSecret()) {
            throw new IOException("Profile settings would introduce a plaintext password into manager-owned configuration");
        }
        Path target = paths.profileConfig(profile.id());
        FilePermissionHardener.hardenDirectory(target.getParent());
        AtomicFileWriter.write(target, document.render().getBytes(StandardCharsets.UTF_8), true);
        FilePermissionHardener.hardenFile(target);
        Path backup = target.resolveSibling(target.getFileName() + ".bak");
        if (Files.isRegularFile(backup)) FilePermissionHardener.hardenFile(backup);
    }

    public void refreshManagedSettings(Profile profile) throws IOException {
        IbcConfigDocument document = loadManagedConfig(profile);
        sanitizePersistentSecrets(document);
        saveManagedConfig(profile, document);
    }

    public static void applyProfile(IbcConfigDocument document, Profile profile) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(profile, "profile");
        if (profile.credentialMode() != CredentialMode.EXISTING_CONFIG) {
            document.set("IbLoginId", profile.username());
            document.set("IbPassword", "");
        }
        document.set("TradingMode", profile.tradingMode().ibcValue());
        document.set("MinimizeMainWindow", profile.minimizeMainWindow() ? "yes" : "no");
        document.set("OverrideTwsApiPort", Integer.toString(profile.apiPort()));
        document.set("CommandServerPort", Integer.toString(profile.commandServerPort()));
        document.set("BindAddress", profile.bindAddress());
        document.set("IbDir", "");
        document.set(SECOND_FACTOR_DEVICE_KEY, settingValue(profile, SECOND_FACTOR_DEVICE_KEY));
        for (var entry : profile.settings().entrySet()) {
            if (!IbcConfigSchema.isSensitive(entry.getKey()) && !isProfileControlled(entry.getKey())) {
                document.set(entry.getKey(), entry.getValue());
            }
        }
    }

    public static String settingValue(Profile profile, String key) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(key, "key");
        for (var entry : profile.settings().entrySet()) {
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

    private String loadTemplate() throws IOException {
        try (InputStream stream = ManagedConfigService.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (stream == null) throw new IOException("Missing bundled IBC configuration template");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static boolean isEmpty(Path path) {
        return path == null || path.toString().isBlank();
    }
}
