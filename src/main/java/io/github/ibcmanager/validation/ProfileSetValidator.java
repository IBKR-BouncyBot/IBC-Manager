package io.github.ibcmanager.validation;

import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.Severity;
import io.github.ibcmanager.model.TradingMode;
import io.github.ibcmanager.model.ValidationIssue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ProfileSetValidator {
    public ValidationResult validate(List<Profile> profiles) {
        List<ValidationIssue> issues = new ArrayList<>();
        Map<String, Profile> names = new HashMap<>();
        Map<Integer, PortUse> ports = new HashMap<>();
        Map<Path, Profile> settingsPaths = new HashMap<>();
        Map<String, Profile> liveUsers = new HashMap<>();

        for (Profile profile : profiles) {
            if (!profile.enabled()) continue;
            detect(names, profile.name().toLowerCase(Locale.ROOT), profile, "name", "Duplicate profile name", issues);
            detectPort(ports, profile.apiPort(), profile, "API", "apiPort", issues);
            detectPort(ports, profile.commandServerPort(), profile, "IBC command-server", "commandServerPort", issues);
            if (!profile.twsSettingsPath().toString().isBlank()) {
                detect(settingsPaths, profile.twsSettingsPath().toAbsolutePath().normalize(), profile,
                        "twsSettingsPath", "TWS/Gateway settings directory is also used by", issues);
            }
            if (profile.tradingMode() == TradingMode.LIVE && !profile.username().isBlank()) {
                String key = profile.username().toLowerCase(Locale.ROOT);
                Profile existing = liveUsers.putIfAbsent(key, profile);
                if (existing != null) {
                    issues.add(new ValidationIssue(Severity.WARNING, "username",
                            "Live profiles '" + existing.name() + "' and '" + profile.name()
                                    + "' use the same IBKR username; simultaneous trading sessions may conflict"));
                }
            }
        }
        return new ValidationResult(issues);
    }

    private static void detectPort(Map<Integer, PortUse> seen, int port, Profile current,
            String role, String field, List<ValidationIssue> issues) {
        PortUse existing = seen.putIfAbsent(port, new PortUse(current, role));
        if (existing != null) {
            issues.add(new ValidationIssue(Severity.ERROR, field,
                    role + " port " + port + " conflicts with the " + existing.role()
                            + " port of profile '" + existing.profile().name() + "'"));
        }
    }

    private static <K> void detect(Map<K, Profile> seen, K key, Profile current, String field,
            String message, List<ValidationIssue> issues) {
        Profile existing = seen.putIfAbsent(key, current);
        if (existing != null) {
            issues.add(new ValidationIssue(Severity.ERROR, field,
                    message + " profile '" + existing.name() + "'"));
        }
    }

    private record PortUse(Profile profile, String role) { }
}
