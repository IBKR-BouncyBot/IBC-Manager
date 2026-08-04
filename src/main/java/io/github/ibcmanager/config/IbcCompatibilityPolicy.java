package io.github.ibcmanager.config;

import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.Severity;
import io.github.ibcmanager.model.TargetType;
import io.github.ibcmanager.model.ValidationIssue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compatibility rules imposed by IBC Manager's supported IBC 3.24.1 integration surface.
 *
 * <p>IBC itself supports FIX CTCI mode, but IBC Manager's lifecycle, command, status, and
 * credential model is intentionally limited to ordinary TWS and IB Gateway sessions.</p>
 */
public final class IbcCompatibilityPolicy {
    public static final String SAFE_ORDER_ID_RESET_POLICY = "ignore/ignore";
    private static final Set<String> UNSUPPORTED_STRUCTURED_SETTINGS = Set.of(
            "fix", "fixloginid", "fixpassword", "trustedtwsapiclientips");

    private IbcCompatibilityPolicy() {
    }

    public static boolean isUnsupportedStructuredSetting(String key) {
        return key != null && UNSUPPORTED_STRUCTURED_SETTINGS.contains(key.toLowerCase(Locale.ROOT));
    }

    public static boolean isTruthy(String value) {
        return value != null && (value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("true"));
    }

    /** Validates settings whose meaning depends on whether the profile launches TWS or Gateway. */
    public static List<ValidationIssue> validateForProfile(Profile profile, Map<String, String> settings) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (profile == null || settings == null || profile.targetType() != TargetType.GATEWAY) {
            return List.copyOf(issues);
        }
        if (isTruthy(valueIgnoreCase(settings, "ReadOnlyLogin"))) {
            issues.add(new ValidationIssue(Severity.ERROR, "ReadOnlyLogin",
                    "IBC 3.24.1 does not support read-only login for IB Gateway; use TWS or disable this setting"));
        }
        if (!valueIgnoreCase(settings, "StoreSettingsOnServer").isBlank()) {
            issues.add(new ValidationIssue(Severity.WARNING, "StoreSettingsOnServer",
                    "StoreSettingsOnServer is TWS-only and is ignored by IB Gateway"));
        }
        String confirmOrderIdReset = valueIgnoreCase(settings, "ConfirmOrderIdReset");
        if (!confirmOrderIdReset.isBlank() && !confirmOrderIdReset.equalsIgnoreCase(SAFE_ORDER_ID_RESET_POLICY)) {
            issues.add(new ValidationIssue(Severity.WARNING, "ConfirmOrderIdReset",
                    "IB Gateway does not display the order-ID reset confirmation dialog, so this setting is ignored"));
        }
        return List.copyOf(issues);
    }

    private static String valueIgnoreCase(Map<String, String> settings, String key) {
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue() == null ? "" : entry.getValue();
            }
        }
        return "";
    }

    /** Applies defaults required to avoid known unsafe behavior in the supported IBC baseline. */
    public static void applySafeRuntimeDefaults(IbcConfigDocument document) {
        String confirmOrderIdReset = document.get("ConfirmOrderIdReset").orElse("").trim();
        if (confirmOrderIdReset.isEmpty()) {
            document.set("ConfirmOrderIdReset", SAFE_ORDER_ID_RESET_POLICY);
        }
    }
}
