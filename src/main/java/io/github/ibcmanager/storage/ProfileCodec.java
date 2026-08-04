package io.github.ibcmanager.storage;

import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.TargetType;
import io.github.ibcmanager.model.TradingMode;
import io.github.ibcmanager.model.TwoFactorTimeoutAction;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

public final class ProfileCodec {
    public static final int FORMAT_VERSION = 2;

    public String encode(Profile profile) {
        Objects.requireNonNull(profile, "profile");
        List<String> lines = new ArrayList<>();
        lines.add("formatVersion=" + FORMAT_VERSION);
        lines.add("id=" + profile.id());
        lines.add("name=" + escape(profile.name()));
        lines.add("enabled=" + profile.enabled());
        lines.add("targetType=" + profile.targetType().name());
        lines.add("tradingMode=" + profile.tradingMode().name());
        lines.add("twsMajorVersion=" + escape(profile.twsMajorVersion()));
        lines.add("ibcPath=" + escape(profile.ibcPath().toString()));
        lines.add("twsPath=" + escape(profile.twsPath().toString()));
        lines.add("twsSettingsPath=" + escape(profile.twsSettingsPath().toString()));
        lines.add("baseConfigPath=" + escape(profile.baseConfigPath().toString()));
        lines.add("ibcJavaPath=" + escape(profile.ibcJavaPath().toString()));
        lines.add("apiPort=" + profile.apiPort());
        lines.add("commandServerPort=" + profile.commandServerPort());
        lines.add("bindAddress=" + escape(profile.bindAddress()));
        lines.add("username=" + escape(profile.username()));
        lines.add("credentialMode=" + profile.credentialMode().name());
        lines.add("twoFactorTimeoutAction=" + profile.twoFactorTimeoutAction().name());
        lines.add("reloginAfterSecondFactorTimeout=" + profile.reloginAfterSecondFactorTimeout());
        lines.add("forceApiPortAtLaunch=" + profile.forceApiPortAtLaunch());
        lines.add("autoStart=" + profile.autoStart());
        lines.add("minimizeMainWindow=" + profile.minimizeMainWindow());
        lines.add("gracefulStopTimeoutSeconds=" + profile.gracefulStopTimeoutSeconds());
        new TreeMap<>(profile.settings()).forEach((key, value) ->
                lines.add("setting." + encodeKey(key) + "=" + escape(value)));
        return String.join("\n", lines) + "\n";
    }

    public Profile decode(String text) {
        Objects.requireNonNull(text, "text");
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> settings = new LinkedHashMap<>();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        int lineNumber = 0;
        for (String raw : normalized.split("\n", -1)) {
            lineNumber++;
            if (raw.isBlank() || raw.stripLeading().startsWith("#")) continue;
            int separator = raw.indexOf('=');
            if (separator <= 0) throw new IllegalArgumentException("Invalid profile line " + lineNumber);
            String key = raw.substring(0, separator).trim();
            String value = unescape(raw.substring(separator + 1));
            if (key.startsWith("setting.")) {
                String settingKey = decodeKey(key.substring("setting.".length()));
                if (settings.put(settingKey, value) != null) {
                    throw new IllegalArgumentException("Duplicate profile setting: " + settingKey);
                }
            } else {
                if (values.put(key, value) != null) {
                    throw new IllegalArgumentException("Duplicate profile key: " + key);
                }
            }
        }

        int version = parseInt(required(values, "formatVersion"), "formatVersion");
        if (version < 1 || version > FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported profile format version: " + version);
        }
        TwoFactorTimeoutAction timeoutAction = TwoFactorTimeoutAction.valueOf(required(values,
                "twoFactorTimeoutAction"));
        boolean reloginAfterTimeout = version >= 2
                ? parseBoolean(required(values, "reloginAfterSecondFactorTimeout"),
                        "reloginAfterSecondFactorTimeout")
                : timeoutAction == TwoFactorTimeoutAction.RESTART;
        boolean forceApiPort = version >= 2
                ? parseBoolean(required(values, "forceApiPortAtLaunch"), "forceApiPortAtLaunch")
                : true;

        return Profile.builder()
                .id(UUID.fromString(required(values, "id")))
                .name(required(values, "name"))
                .enabled(parseBoolean(required(values, "enabled"), "enabled"))
                .targetType(TargetType.valueOf(required(values, "targetType")))
                .tradingMode(TradingMode.valueOf(required(values, "tradingMode")))
                .twsMajorVersion(required(values, "twsMajorVersion"))
                .ibcPath(path(values.get("ibcPath")))
                .twsPath(path(values.get("twsPath")))
                .twsSettingsPath(path(values.get("twsSettingsPath")))
                .baseConfigPath(path(values.get("baseConfigPath")))
                .ibcJavaPath(version >= 2 ? path(values.get("ibcJavaPath")) : Path.of(""))
                .apiPort(parseInt(required(values, "apiPort"), "apiPort"))
                .commandServerPort(parseInt(required(values, "commandServerPort"), "commandServerPort"))
                .bindAddress(required(values, "bindAddress"))
                .username(required(values, "username"))
                .credentialMode(CredentialMode.valueOf(required(values, "credentialMode")))
                .twoFactorTimeoutAction(timeoutAction)
                .reloginAfterSecondFactorTimeout(reloginAfterTimeout)
                .forceApiPortAtLaunch(forceApiPort)
                .autoStart(parseBoolean(required(values, "autoStart"), "autoStart"))
                .minimizeMainWindow(parseBoolean(required(values, "minimizeMainWindow"), "minimizeMainWindow"))
                .gracefulStopTimeoutSeconds(parseInt(required(values, "gracefulStopTimeoutSeconds"), "gracefulStopTimeoutSeconds"))
                .settings(settings)
                .build();
    }

    private static Path path(String value) {
        return Path.of(value == null ? "" : value);
    }

    private static String required(Map<String, String> values, String key) {
        if (!values.containsKey(key)) throw new IllegalArgumentException("Missing profile key: " + key);
        return values.get(key);
    }

    private static int parseInt(String value, String key) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid integer for " + key, ex);
        }
    }

    private static boolean parseBoolean(String value, String key) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException("Invalid boolean for " + key);
    }

    static String escape(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> result.append(ch);
            }
        }
        return result.toString();
    }

    static String unescape(String value) {
        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!escaped) {
                if (ch == '\\') escaped = true;
                else result.append(ch);
                continue;
            }
            switch (ch) {
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case '\\' -> result.append('\\');
                default -> {
                    result.append('\\');
                    result.append(ch);
                }
            }
            escaped = false;
        }
        if (escaped) result.append('\\');
        return result.toString();
    }

    private static String encodeKey(String key) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeKey(String key) {
        try {
            return new String(Base64.getUrlDecoder().decode(key), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid encoded setting key", ex);
        }
    }
}
