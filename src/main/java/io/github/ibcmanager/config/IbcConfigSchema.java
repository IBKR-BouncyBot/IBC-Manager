package io.github.ibcmanager.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class IbcConfigSchema {
    private static final List<SettingDefinition> DEFINITIONS = buildDefinitions();
    private static final Map<String, SettingDefinition> BY_KEY = buildMap();
    private static final Set<String> SENSITIVE = Set.of("IbPassword", "FIXPassword");

    private IbcConfigSchema() {
    }

    public static List<SettingDefinition> definitions() {
        return DEFINITIONS;
    }

    public static Optional<SettingDefinition> find(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }

    public static boolean isSensitive(String key) {
        return key != null && SENSITIVE.stream().anyMatch(candidate -> candidate.equalsIgnoreCase(key));
    }

    public static Set<String> sensitiveKeys() {
        return SENSITIVE;
    }

    private static Map<String, SettingDefinition> buildMap() {
        Map<String, SettingDefinition> result = new LinkedHashMap<>();
        for (SettingDefinition definition : DEFINITIONS) result.put(definition.key(), definition);
        return Map.copyOf(result);
    }

    private static List<SettingDefinition> buildDefinitions() {
        List<SettingDefinition> items = new ArrayList<>();
        add(items, "FIX", "FIX CTCI gateway", "Startup", SettingType.BOOLEAN, List.of("yes", "no"), "no", "Start the FIX CTCI gateway instead of the regular IB API gateway.", false, true);
        add(items, "IbLoginId", "IBKR username", "Authentication", SettingType.TEXT, List.of(), "", "Interactive Brokers login name.", false, false);
        add(items, "IbPassword", "IBKR password", "Authentication", SettingType.PASSWORD, List.of(), "", "Sensitive. IBC Manager keeps this blank in persistent managed configurations.", true, false);
        add(items, "FIXLoginId", "FIX username", "Authentication", SettingType.TEXT, List.of(), "", "FIX CTCI username.", false, true);
        add(items, "FIXPassword", "FIX password", "Authentication", SettingType.PASSWORD, List.of(), "", "Sensitive FIX CTCI password.", true, true);
        add(items, "SecondFactorDevice", "Second-factor device", "Authentication", SettingType.TEXT, List.of(), "", "Exact device name to select when IBKR presents multiple second-factor devices.", false, false);
        add(items, "ReloginAfterSecondFactorAuthenticationTimeout", "Retry after 2FA timeout", "Authentication", SettingType.BOOLEAN, List.of("yes", "no"), "no", "Restart the login sequence after second-factor authentication times out.", false, false);
        add(items, "SecondFactorAuthenticationExitInterval", "2FA completion exit interval", "Authentication", SettingType.INTEGER, List.of(), "", "Seconds to wait after the second-factor action before IBC exits.", false, true);
        add(items, "SecondFactorAuthenticationTimeout", "IBKR 2FA timeout", "Authentication", SettingType.INTEGER, List.of(), "180", "IBKR second-factor timeout in seconds.", false, true);
        add(items, "ExitAfterSecondFactorAuthenticationTimeout", "Legacy exit after 2FA timeout", "Authentication", SettingType.BOOLEAN, List.of("yes", "no"), "no", "Deprecated IBC setting retained for compatibility.", false, true);
        add(items, "TradingMode", "Trading mode", "Authentication", SettingType.ENUM, List.of("live", "paper"), "live", "Select live or paper trading.", false, false);
        add(items, "AcceptNonBrokerageAccountWarning", "Accept paper-account warning", "Authentication", SettingType.BOOLEAN, List.of("yes", "no"), "no", "Automatically accept the non-brokerage paper-account warning.", false, false);
        add(items, "LoginDialogDisplayTimeout", "Login dialog timeout", "Authentication", SettingType.INTEGER, List.of(), "60", "Seconds to wait for the login dialog before IBC restarts.", false, false);
        add(items, "IbDir", "Legacy settings directory", "Startup", SettingType.PATH, List.of(), "", "Deprecated. Prefer the launcher's TWS settings path.", false, true);
        add(items, "StoreSettingsOnServer", "Store TWS settings on server", "Startup", SettingType.TRI_STATE_BOOLEAN, List.of("", "yes", "no"), "", "TWS only. Blank preserves the existing choice.", false, true);
        add(items, "MinimizeMainWindow", "Minimize main window", "Startup", SettingType.BOOLEAN, List.of("yes", "no"), "no", "Minimize TWS/Gateway after startup.", false, false);
        add(items, "ExistingSessionDetectedAction", "Existing session action", "Startup", SettingType.ENUM, List.of("manual", "primary", "primaryoverride", "secondary"), "manual", "Action when IBKR reports another trading session.", false, false);
        add(items, "OverrideTwsApiPort", "Override API socket port", "API", SettingType.PORT, List.of(), "", "Set the TWS/Gateway API socket port at startup.", false, false);
        add(items, "OverrideTwsMasterClientID", "Override master client ID", "API", SettingType.INTEGER, List.of(), "", "Set the API master client ID at startup.", false, true);
        add(items, "ReadOnlyLogin", "Read-only login", "API", SettingType.BOOLEAN, List.of("yes", "no"), "no", "Log in without second factor in read-only mode where supported.", false, true);
        add(items, "ReadOnlyApi", "Read-only API", "API", SettingType.TRI_STATE_BOOLEAN, List.of("", "yes", "no"), "", "Prevent API order submission when set to yes.", false, false);
        for (String key : List.of("BypassOrderPrecautions", "BypassBondWarning", "BypassNegativeYieldToWorstConfirmation", "BypassCalledBondWarning", "BypassSameActionPairTradeWarning", "BypassPriceBasedVolatilityRiskWarning", "BypassUSStocksMarketDataInSharesWarning", "BypassRedirectOrderWarning", "BypassNoOverfillProtectionPrecaution")) {
            add(items, key, humanize(key), "API precautions", SettingType.TRI_STATE_BOOLEAN, List.of("", "yes", "no"), "", "Blank preserves the existing TWS/Gateway setting.", false, true);
        }
        add(items, "AcceptBidAskLastSizeDisplayUpdateNotification", "US stock size notification", "API", SettingType.ENUM, List.of("", "accept", "defer", "ignore"), "", "How IBC handles the bid/ask/last size notification.", false, true);
        add(items, "SendMarketDataInLotsForUSstocks", "Send US stock sizes in lots", "API", SettingType.TRI_STATE_BOOLEAN, List.of("", "yes", "no"), "", "Blank preserves the current API setting.", false, true);
        add(items, "TrustedTwsApiClientIPs", "Trusted API client IPs", "API", SettingType.IP_LIST, List.of(), "", "Comma-separated list. Relevant to the FIX gateway only.", false, true);
        add(items, "ResetOrderIdsAtStart", "Reset API order IDs at start", "API", SettingType.TRI_STATE_BOOLEAN, List.of("", "yes", "no"), "", "Reset the API order ID sequence at startup.", false, true);
        add(items, "ConfirmOrderIdReset", "Order-ID reset confirmation", "API", SettingType.TEXT, List.of(), "", "Pair such as confirm/reject. See IBC documentation.", false, true);
        add(items, "AutoLogoffTime", "Daily auto-logoff", "Scheduling", SettingType.TIME_12_HOUR, List.of(), "", "Daily time in hh:mm AM/PM format.", false, false);
        add(items, "AutoRestartTime", "Daily auto-restart", "Scheduling", SettingType.TIME_12_HOUR, List.of(), "", "Daily time in hh:mm AM/PM format.", false, false);
        add(items, "ColdRestartTime", "Sunday cold restart", "Scheduling", SettingType.TIME_24_HOUR, List.of(), "", "Sunday cold-restart time in HH:mm local time.", false, false);
        add(items, "ClosedownAt", "Scheduled closedown", "Scheduling", SettingType.SCHEDULE, List.of(), "", "IBC ClosedownAt schedule; see IBC documentation.", false, true);
        add(items, "AcceptIncomingConnectionAction", "Incoming API connection action", "Warnings", SettingType.ENUM, List.of("manual", "accept", "reject"), "manual", "How to handle API incoming-connection dialogs.", false, false);
        add(items, "AllowBlindTrading", "Allow blind trading", "Warnings", SettingType.BOOLEAN, List.of("yes", "no"), "no", "Automatically accept the blind-trading warning.", false, false);
        add(items, "SaveTwsSettingsAt", "Save TWS settings at", "Scheduling", SettingType.SCHEDULE, List.of(), "", "One or more HH:mm times separated by spaces.", false, true);
        add(items, "ConfirmCryptoCurrencyOrders", "Cryptocurrency order confirmation", "Warnings", SettingType.ENUM, List.of("manual", "transmit", "cancel"), "manual", "How to handle supported cryptocurrency order confirmations.", false, true);
        add(items, "DismissPasswordExpiryWarning", "Dismiss password-expiry warning", "Warnings", SettingType.BOOLEAN, List.of("yes", "no"), "no", "Suppress password-expiry warnings. Usually leave disabled.", false, true);
        add(items, "DismissNSEComplianceNotice", "Dismiss NSE compliance notice", "Warnings", SettingType.BOOLEAN, List.of("yes", "no"), "yes", "Indian TWS versions only.", false, true);
        add(items, "CommandServerPort", "IBC command-server port", "Command server", SettingType.PORT, List.of(), "0", "Unique port for STOP, RESTART, PAUSE and related IBC commands.", false, false);
        add(items, "ControlFrom", "Additional permitted command sources", "Command server", SettingType.IP_LIST, List.of(), "", "Comma-separated hosts. The local host is always permitted.", false, true);
        add(items, "BindAddress", "Command-server bind address", "Command server", SettingType.TEXT, List.of(), "", "Use 127.0.0.1 for local-only control.", false, false);
        add(items, "CommandPrompt", "Command-server prompt", "Command server", SettingType.TEXT, List.of(), "", "Optional prompt returned by the IBC command server.", false, true);
        add(items, "SuppressInfoMessages", "Suppress command info messages", "Command server", SettingType.BOOLEAN, List.of("yes", "no"), "yes", "Suppress intermediate command-server messages.", false, true);
        add(items, "LogStructureScope", "Window structure scope", "Diagnostics", SettingType.ENUM, List.of("known", "unknown", "untitled", "all"), "known", "Which windows are eligible for structure logging.", false, true);
        add(items, "LogStructureWhen", "Window structure logging", "Diagnostics", SettingType.ENUM, List.of("never", "open", "openclose", "activate"), "never", "When IBC logs Swing component structure.", false, true);
        add(items, "IncludeStackTraceForExceptions", "Include exception stack traces", "Diagnostics", SettingType.TRI_STATE_BOOLEAN, List.of("", "yes", "no"), "", "Include stack traces for unhandled exceptions.", false, true);
        return List.copyOf(items);
    }

    private static void add(List<SettingDefinition> items, String key, String label, String category,
            SettingType type, List<String> values, String defaultValue, String description,
            boolean sensitive, boolean advanced) {
        items.add(new SettingDefinition(key, label, category, type, values, defaultValue, description, sensitive, advanced));
    }

    private static String humanize(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
    }
}
