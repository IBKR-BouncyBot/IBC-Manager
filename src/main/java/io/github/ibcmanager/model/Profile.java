package io.github.ibcmanager.model;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class Profile {
    private final UUID id;
    private final String name;
    private final boolean enabled;
    private final TargetType targetType;
    private final TradingMode tradingMode;
    private final String twsMajorVersion;
    private final Path ibcPath;
    private final Path twsPath;
    private final Path twsSettingsPath;
    private final Path baseConfigPath;
    private final Path ibcJavaPath;
    private final int apiPort;
    private final int commandServerPort;
    private final String bindAddress;
    private final String username;
    private final CredentialMode credentialMode;
    private final TwoFactorTimeoutAction twoFactorTimeoutAction;
    private final boolean reloginAfterSecondFactorTimeout;
    private final boolean forceApiPortAtLaunch;
    private final boolean autoStart;
    private final boolean minimizeMainWindow;
    private final int gracefulStopTimeoutSeconds;
    private final Map<String, String> settings;

    private Profile(Builder builder) {
        id = Objects.requireNonNull(builder.id, "id");
        name = normalize(builder.name);
        enabled = builder.enabled;
        targetType = Objects.requireNonNull(builder.targetType, "targetType");
        tradingMode = Objects.requireNonNull(builder.tradingMode, "tradingMode");
        twsMajorVersion = normalize(builder.twsMajorVersion);
        ibcPath = normalizePath(builder.ibcPath);
        twsPath = normalizePath(builder.twsPath);
        twsSettingsPath = normalizePath(builder.twsSettingsPath);
        baseConfigPath = normalizePath(builder.baseConfigPath);
        ibcJavaPath = normalizePath(builder.ibcJavaPath);
        apiPort = builder.apiPort;
        commandServerPort = builder.commandServerPort;
        bindAddress = normalize(builder.bindAddress);
        username = normalize(builder.username);
        credentialMode = Objects.requireNonNull(builder.credentialMode, "credentialMode");
        twoFactorTimeoutAction = Objects.requireNonNull(builder.twoFactorTimeoutAction, "twoFactorTimeoutAction");
        reloginAfterSecondFactorTimeout = builder.reloginAfterSecondFactorTimeout;
        forceApiPortAtLaunch = builder.forceApiPortAtLaunch;
        autoStart = builder.autoStart;
        minimizeMainWindow = builder.minimizeMainWindow;
        gracefulStopTimeoutSeconds = builder.gracefulStopTimeoutSeconds;
        Map<String, String> copy = new LinkedHashMap<>();
        builder.settings.forEach((key, value) -> {
            if (key != null && !key.isBlank()) {
                copy.put(key.trim(), value == null ? "" : value);
            }
        });
        settings = Collections.unmodifiableMap(copy);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static Path normalizePath(Path value) {
        return value == null ? Path.of("") : value.normalize();
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public boolean enabled() { return enabled; }
    public TargetType targetType() { return targetType; }
    public TradingMode tradingMode() { return tradingMode; }
    public String twsMajorVersion() { return twsMajorVersion; }
    public Path ibcPath() { return ibcPath; }
    public Path twsPath() { return twsPath; }
    public Path twsSettingsPath() { return twsSettingsPath; }
    public Path baseConfigPath() { return baseConfigPath; }
    public Path ibcJavaPath() { return ibcJavaPath; }
    public int apiPort() { return apiPort; }
    public int commandServerPort() { return commandServerPort; }
    public String bindAddress() { return bindAddress; }
    public String username() { return username; }
    public CredentialMode credentialMode() { return credentialMode; }
    public TwoFactorTimeoutAction twoFactorTimeoutAction() { return twoFactorTimeoutAction; }
    public boolean reloginAfterSecondFactorTimeout() { return reloginAfterSecondFactorTimeout; }
    public boolean forceApiPortAtLaunch() { return forceApiPortAtLaunch; }
    public boolean autoStart() { return autoStart; }
    public boolean minimizeMainWindow() { return minimizeMainWindow; }
    public int gracefulStopTimeoutSeconds() { return gracefulStopTimeoutSeconds; }
    public Map<String, String> settings() { return settings; }

    public Builder toBuilder() {
        return new Builder()
                .id(id)
                .name(name)
                .enabled(enabled)
                .targetType(targetType)
                .tradingMode(tradingMode)
                .twsMajorVersion(twsMajorVersion)
                .ibcPath(ibcPath)
                .twsPath(twsPath)
                .twsSettingsPath(twsSettingsPath)
                .baseConfigPath(baseConfigPath)
                .ibcJavaPath(ibcJavaPath)
                .apiPort(apiPort)
                .commandServerPort(commandServerPort)
                .bindAddress(bindAddress)
                .username(username)
                .credentialMode(credentialMode)
                .twoFactorTimeoutAction(twoFactorTimeoutAction)
                .reloginAfterSecondFactorTimeout(reloginAfterSecondFactorTimeout)
                .forceApiPortAtLaunch(forceApiPortAtLaunch)
                .autoStart(autoStart)
                .minimizeMainWindow(minimizeMainWindow)
                .gracefulStopTimeoutSeconds(gracefulStopTimeoutSeconds)
                .settings(settings);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id = UUID.randomUUID();
        private String name = "New profile";
        private boolean enabled = true;
        private TargetType targetType = TargetType.GATEWAY;
        private TradingMode tradingMode = TradingMode.PAPER;
        private String twsMajorVersion = "";
        private Path ibcPath = Path.of("");
        private Path twsPath = Path.of("");
        private Path twsSettingsPath = Path.of("");
        private Path baseConfigPath = Path.of("");
        private Path ibcJavaPath = Path.of("");
        private int apiPort = 4002;
        private int commandServerPort = 7462;
        private String bindAddress = "127.0.0.1";
        private String username = "";
        private CredentialMode credentialMode = CredentialMode.MANUAL;
        private TwoFactorTimeoutAction twoFactorTimeoutAction = TwoFactorTimeoutAction.EXIT;
        private boolean reloginAfterSecondFactorTimeout;
        private boolean forceApiPortAtLaunch;
        private boolean autoStart;
        private boolean minimizeMainWindow = true;
        private int gracefulStopTimeoutSeconds = 90;
        private Map<String, String> settings = new LinkedHashMap<>();

        public Builder id(UUID value) { id = value; return this; }
        public Builder name(String value) { name = value; return this; }
        public Builder enabled(boolean value) { enabled = value; return this; }
        public Builder targetType(TargetType value) { targetType = value; return this; }
        public Builder tradingMode(TradingMode value) { tradingMode = value; return this; }
        public Builder twsMajorVersion(String value) { twsMajorVersion = value; return this; }
        public Builder ibcPath(Path value) { ibcPath = value; return this; }
        public Builder twsPath(Path value) { twsPath = value; return this; }
        public Builder twsSettingsPath(Path value) { twsSettingsPath = value; return this; }
        public Builder baseConfigPath(Path value) { baseConfigPath = value; return this; }
        public Builder ibcJavaPath(Path value) { ibcJavaPath = value; return this; }
        public Builder apiPort(int value) { apiPort = value; return this; }
        public Builder commandServerPort(int value) { commandServerPort = value; return this; }
        public Builder bindAddress(String value) { bindAddress = value; return this; }
        public Builder username(String value) { username = value; return this; }
        public Builder credentialMode(CredentialMode value) { credentialMode = value; return this; }
        public Builder twoFactorTimeoutAction(TwoFactorTimeoutAction value) { twoFactorTimeoutAction = value; return this; }
        public Builder reloginAfterSecondFactorTimeout(boolean value) { reloginAfterSecondFactorTimeout = value; return this; }
        public Builder forceApiPortAtLaunch(boolean value) { forceApiPortAtLaunch = value; return this; }
        public Builder autoStart(boolean value) { autoStart = value; return this; }
        public Builder minimizeMainWindow(boolean value) { minimizeMainWindow = value; return this; }
        public Builder gracefulStopTimeoutSeconds(int value) { gracefulStopTimeoutSeconds = value; return this; }
        public Builder settings(Map<String, String> value) {
            settings = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
            return this;
        }
        public Builder setting(String key, String value) {
            settings.put(key, value);
            return this;
        }
        public Profile build() { return new Profile(this); }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Profile profile)) return false;
        return enabled == profile.enabled
                && apiPort == profile.apiPort
                && commandServerPort == profile.commandServerPort
                && reloginAfterSecondFactorTimeout == profile.reloginAfterSecondFactorTimeout
                && forceApiPortAtLaunch == profile.forceApiPortAtLaunch
                && autoStart == profile.autoStart
                && minimizeMainWindow == profile.minimizeMainWindow
                && gracefulStopTimeoutSeconds == profile.gracefulStopTimeoutSeconds
                && id.equals(profile.id)
                && name.equals(profile.name)
                && targetType == profile.targetType
                && tradingMode == profile.tradingMode
                && twsMajorVersion.equals(profile.twsMajorVersion)
                && ibcPath.equals(profile.ibcPath)
                && twsPath.equals(profile.twsPath)
                && twsSettingsPath.equals(profile.twsSettingsPath)
                && baseConfigPath.equals(profile.baseConfigPath)
                && ibcJavaPath.equals(profile.ibcJavaPath)
                && bindAddress.equals(profile.bindAddress)
                && username.equals(profile.username)
                && credentialMode == profile.credentialMode
                && twoFactorTimeoutAction == profile.twoFactorTimeoutAction
                && settings.equals(profile.settings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, enabled, targetType, tradingMode, twsMajorVersion, ibcPath,
                twsPath, twsSettingsPath, baseConfigPath, ibcJavaPath, apiPort, commandServerPort,
                bindAddress, username, credentialMode, twoFactorTimeoutAction,
                reloginAfterSecondFactorTimeout, forceApiPortAtLaunch, autoStart, minimizeMainWindow,
                gracefulStopTimeoutSeconds, settings);
    }

    @Override
    public String toString() {
        return name;
    }
}
