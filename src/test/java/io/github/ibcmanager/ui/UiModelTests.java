package io.github.ibcmanager.ui;

import io.github.ibcmanager.config.IbcConfigSchema;
import io.github.ibcmanager.config.SettingDefinition;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;

import javax.swing.DefaultCellEditor;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.awt.GridBagConstraints;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class UiModelTests implements TestSuite {
    @Override public String name() { return "Swing UI models, controls, and secret-lifecycle helpers"; }

    @Override
    public List<NamedTest> tests() {
        return List.of(
                new NamedTest("settings model exposes stable columns and editable values", this::modelStructure),
                new NamedTest("settings model filters sensitive and profile-controlled keys", this::modelFiltering),
                new NamedTest("settings model trims edits and resets overrides", this::modelEditing),
                new NamedTest("settings model returns values in schema order and independent maps", this::modelOrdering),
                new NamedTest("settings model canonicalizes known keys and preserves future settings",
                        this::modelCanonicalAndFutureSettings),
                new NamedTest("profile tab owns and normalizes SecondFactorDevice", this::secondFactorDevice),
                new NamedTest("installation action buttons retain preferred dimensions", this::installationButtons),
                new NamedTest("session actions present explicit profile-specific confirmations", this::sessionActionPrompts),
                new NamedTest("session action buttons are larger and visually distinct", this::sessionActionButtons),
                new NamedTest("profile status indicator maps states to explicit traffic-light tones", this::statusIndicator),
                new NamedTest("all runtime states have friendly labels without changing their meaning", this::statusWording),
                new NamedTest("API monitoring qualification is in a tooltip, not an error", this::apiMonitoringTooltip),
                new NamedTest("unavailable API observations are not worded as a closed listener", this::unknownListenerWording),
                new NamedTest("settings table uses enumerated editors and escaped tooltips", this::settingsTable),
                new NamedTest("profile edit result copies and clears password material", this::profileEditResult),
                new NamedTest("UI layout helpers create predictable grid constraints", this::layoutHelpers),
                new NamedTest("EDT helper executes both queued and direct actions safely", this::eventDispatchHelper),
                new NamedTest("theme installation is repeatable in a headless test process", this::themeInstall));
    }

    private void modelStructure() {
        ProfileSettingsTableModel model = new ProfileSettingsTableModel(Map.of());
        Assertions.isTrue(model.getRowCount() > 20, "the structured editor must expose a useful IBC schema");
        Assertions.equals(4, model.getColumnCount(), "settings model column count changed");
        Assertions.equals("Category", model.getColumnName(0), "category column mismatch");
        Assertions.equals("Setting", model.getColumnName(1), "setting column mismatch");
        Assertions.equals("Value", model.getColumnName(2), "value column mismatch");
        Assertions.equals(String.class, model.getColumnClass(0), "category type mismatch");
        Assertions.equals(String.class, model.getColumnClass(1), "setting type mismatch");
        Assertions.equals(String.class, model.getColumnClass(2), "value type mismatch");
        Assertions.isFalse(model.isCellEditable(0, 0), "category must be read-only");
        Assertions.isFalse(model.isCellEditable(0, 1), "setting name must be read-only");
        Assertions.isTrue(model.isCellEditable(0, 2), "setting value must be editable");
        Assertions.equals("", model.getColumnName(99), "unknown columns must have a safe name");
    }

    private void modelFiltering() {
        Map<String, String> initial = Map.of(
                "IbPassword", "secret",
                "IbLoginId", "user",
                "TradingMode", "live",
                "CommandServerPort", "7462",
                "SecondFactorDevice", "IBKR Mobile",
                "SecondFactorAuthenticationTimeout", "17",
                "FIX", "yes",
                "FIXLoginId", "fix-user",
                "TrustedTwsApiClientIPs", "127.0.0.1",
                "AcceptIncomingConnectionAction", "accept");
        ProfileSettingsTableModel model = new ProfileSettingsTableModel(initial);
        List<String> keys = definitions(model).stream().map(SettingDefinition::key).toList();
        for (String excluded : List.of("IbPassword", "IbLoginId", "TradingMode", "CommandServerPort",
                "BindAddress", "OverrideTwsApiPort", "MinimizeMainWindow", "IbDir", "SecondFactorDevice",
                "ReloginAfterSecondFactorAuthenticationTimeout", "SecondFactorAuthenticationTimeout",
                "ExitAfterSecondFactorAuthenticationTimeout", "FIX", "FIXLoginId", "FIXPassword",
                "TrustedTwsApiClientIPs")) {
            Assertions.isFalse(keys.contains(excluded), excluded + " must be controlled outside the settings table");
        }
        Assertions.equals(Map.of("AcceptIncomingConnectionAction", "accept"), model.settings(),
                "only supported editable non-sensitive overrides may be emitted");
        Assertions.isFalse(keys.contains("TrustedTwsApiClientIPs"),
                "FIX-only trusted API client addresses must not be exposed for ordinary Gateway/TWS profiles");
        Assertions.isFalse(keys.stream().anyMatch(IbcConfigSchema::isSensitive),
                "no sensitive schema entry may reach the settings table");
    }

    private void modelEditing() {
        ProfileSettingsTableModel model = new ProfileSettingsTableModel(Map.of());
        int row = rowFor(model, "AcceptIncomingConnectionAction");
        model.setValueAt("  accept  ", row, 2);
        Assertions.equals("accept", model.settings().get("AcceptIncomingConnectionAction"),
                "edited values must be trimmed");
        Assertions.equals("accept", model.getValueAt(row, 2), "edited value must be shown");
        model.setValueAt(null, row, 2);
        Assertions.equals("", model.settings().get("AcceptIncomingConnectionAction"),
                "blank is an explicit engine value");
        model.setValueAt("reject", row, 1);
        Assertions.equals("", model.settings().get("AcceptIncomingConnectionAction"),
                "edits to read-only columns must be ignored");
        model.setValueAt("reject", row, 2);
        model.reset(row);
        Assertions.equals("manual", model.getValueAt(row, 2), "reset must show the included default");
        model.reset(-1);
        model.reset(model.getRowCount());
        Assertions.equals(Map.of(), model.settings(), "out-of-range reset must be harmless");
    }

    private void modelOrdering() {
        ProfileSettingsTableModel model = new ProfileSettingsTableModel(Map.of(
                "AllowBlindTrading", "yes",
                "AcceptIncomingConnectionAction", "accept",
                "UnknownFutureSetting", "preserve elsewhere"));
        List<String> expected = definitions(model).stream()
                .map(SettingDefinition::key)
                .filter(key -> key.equals("AllowBlindTrading") || key.equals("AcceptIncomingConnectionAction"))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        expected.add("UnknownFutureSetting");
        List<String> actual = new ArrayList<>(model.settings().keySet());
        Assertions.equals(expected, actual,
                "known overrides must follow schema order and future settings must remain after them");
        Map<String, String> first = model.settings();
        first.put("AllowBlindTrading", "no");
        Assertions.equals("yes", model.settings().get("AllowBlindTrading"),
                "callers must receive an independent settings map");
        Assertions.equals("preserve elsewhere", model.settings().get("UnknownFutureSetting"),
                "opening and saving the structured editor must not delete a future setting");
    }

    private void modelCanonicalAndFutureSettings() {
        Map<String, String> initial = new java.util.LinkedHashMap<>();
        initial.put("allowblindtrading", "yes");
        initial.put("UnknownFutureSetting", "future-value");
        ProfileSettingsTableModel model = new ProfileSettingsTableModel(initial);
        Assertions.equals("yes", model.settings().get("AllowBlindTrading"),
                "known settings must use canonical IBC key spelling");
        Assertions.isFalse(model.settings().containsKey("allowblindtrading"),
                "case-variant known-key spelling must not survive structured editing");
        Assertions.equals("future-value", model.settings().get("UnknownFutureSetting"),
                "future settings must survive structured editing");

        int row = rowFor(model, "AllowBlindTrading");
        model.reset(row);
        Assertions.isFalse(model.settings().containsKey("AllowBlindTrading"),
                "reset must remove the known override");
        Assertions.equals("future-value", model.settings().get("UnknownFutureSetting"),
                "resetting a known row must not remove future settings");
    }


    private void secondFactorDevice() {
        Map<String, String> original = new java.util.LinkedHashMap<>();
        original.put("AllowBlindTrading", "yes");
        original.put("secondfactordevice", "old device");
        Map<String, String> updated = ProfileEditorDialog.withSecondFactorDevice(original, "  IBKR Mobile  ");
        Assertions.equals("yes", updated.get("AllowBlindTrading"), "ordinary setting was lost");
        Assertions.equals("IBKR Mobile", updated.get("SecondFactorDevice"),
                "SecondFactorDevice must be trimmed and use canonical casing");
        Assertions.isFalse(updated.containsKey("secondfactordevice"),
                "case-variant duplicate SecondFactorDevice must be removed");
        Assertions.equals("old device", original.get("secondfactordevice"),
                "profile merge must not mutate its input map");

        Map<String, String> removed = ProfileEditorDialog.withSecondFactorDevice(updated, "  ");
        Assertions.isFalse(removed.keySet().stream().anyMatch(key -> key.equalsIgnoreCase("SecondFactorDevice")),
                "blank profile field must remove the override");
        Assertions.throwsType(NullPointerException.class,
                () -> ProfileEditorDialog.withSecondFactorDevice(null, "device"),
                "null settings map must be rejected");
    }

    private void installationButtons() {
        javax.swing.JButton detect = new javax.swing.JButton("Detect common installations...");
        javax.swing.JButton install = new javax.swing.JButton("Install latest IBC from GitHub...");
        java.awt.Dimension detectPreferred = detect.getPreferredSize();
        java.awt.Dimension installPreferred = install.getPreferredSize();
        JPanel panel = ProfileEditorDialog.installationActions(detect, install);
        panel.setSize(detectPreferred.width + installPreferred.width + 80,
                Math.max(detectPreferred.height, installPreferred.height) + 8);
        panel.doLayout();
        Assertions.equals(detectPreferred, detect.getMinimumSize(),
                "detect button minimum size must equal its preferred size");
        Assertions.equals(installPreferred, install.getMinimumSize(),
                "install button minimum size must equal its preferred size");
        Assertions.isTrue(detect.getWidth() >= detectPreferred.width,
                "detect button was compressed horizontally");
        Assertions.isTrue(detect.getHeight() >= detectPreferred.height,
                "detect button was compressed vertically");
        Assertions.isTrue(install.getWidth() >= installPreferred.width,
                "install button was compressed horizontally");
        Assertions.isTrue(install.getHeight() >= installPreferred.height,
                "install button was compressed vertically");
    }

    private void sessionActionPrompts() {
        Profile live = Profile.builder()
                .name("Live gateway")
                .tradingMode(io.github.ibcmanager.model.TradingMode.LIVE)
                .targetType(io.github.ibcmanager.model.TargetType.GATEWAY)
                .apiPort(4001)
                .build();
        Profile paper = live.toBuilder()
                .name("Paper workstation")
                .tradingMode(io.github.ibcmanager.model.TradingMode.PAPER)
                .targetType(io.github.ibcmanager.model.TargetType.TWS)
                .apiPort(7497)
                .build();

        ProfileSessionAction.Prompt liveStart = ProfileSessionAction.START.prompt(live);
        Assertions.equals("Confirm start", liveStart.title(), "start confirmation title mismatch");
        Assertions.equals("Start", liveStart.confirmationLabel(), "start confirmation label mismatch");
        Assertions.equals(javax.swing.JOptionPane.WARNING_MESSAGE, liveStart.messageType(),
                "live start must use a warning confirmation");
        Assertions.contains(liveStart.message(), "Live gateway", "start confirmation must identify the profile");
        Assertions.contains(liveStart.message(), "IB Gateway", "start confirmation must identify the application");
        Assertions.contains(liveStart.message(), "Trading mode: Live", "start confirmation must identify live mode");
        Assertions.contains(liveStart.message(), "API port: 4001", "start confirmation must identify the API port");
        Assertions.contains(liveStart.message(), "LIVE trading profile",
                "live start confirmation must include an explicit live warning");

        ProfileSessionAction.Prompt paperStart = ProfileSessionAction.START.prompt(paper);
        Assertions.equals(javax.swing.JOptionPane.QUESTION_MESSAGE, paperStart.messageType(),
                "paper start may use an informational confirmation");
        Assertions.contains(paperStart.message(), "Unsupported legacy TWS profile",
                "paper start confirmation must identify TWS");
        Assertions.notContains(paperStart.message(), "LIVE trading profile",
                "paper start must not display the live-mode warning");

        ProfileSessionAction.Prompt stop = ProfileSessionAction.STOP.prompt(live);
        Assertions.equals("Confirm stop", stop.title(), "stop confirmation title mismatch");
        Assertions.equals("Stop", stop.confirmationLabel(), "stop confirmation label mismatch");
        Assertions.contains(stop.message(), "graceful shutdown",
                "stop confirmation must explain graceful shutdown");
        Assertions.contains(stop.message(), "connectivity will stop",
                "stop confirmation must explain the connectivity impact");
        Assertions.equals(javax.swing.JOptionPane.WARNING_MESSAGE, stop.messageType(),
                "stop must use a warning confirmation");

        ProfileSessionAction.Prompt forceStop = ProfileSessionAction.FORCE_STOP.prompt(live);
        Assertions.equals("Confirm force stop", forceStop.title(),
                "force-stop confirmation title mismatch");
        Assertions.equals("Force Stop", forceStop.confirmationLabel(),
                "force-stop confirmation label mismatch");
        Assertions.contains(forceStop.message(), "forcibly terminates",
                "force-stop confirmation must explain that termination is forced");
        Assertions.contains(forceStop.message(), "Unsaved",
                "force-stop confirmation must warn about unsaved state");
        Assertions.equals(javax.swing.JOptionPane.WARNING_MESSAGE, forceStop.messageType(),
                "force stop must use a warning confirmation");

        ProfileSessionAction.Prompt restart = ProfileSessionAction.RESTART.prompt(live);
        Assertions.equals("Confirm restart", restart.title(), "restart confirmation title mismatch");
        Assertions.contains(restart.message(), "connectivity will be interrupted",
                "restart confirmation must explain its connectivity impact");
        Assertions.equals(javax.swing.JOptionPane.WARNING_MESSAGE, restart.messageType(),
                "restart must use a warning confirmation");

        ProfileSessionAction.Prompt pause = ProfileSessionAction.PAUSE.prompt(live);
        Assertions.equals("Confirm pause", pause.title(), "pause confirmation title mismatch");
        Assertions.contains(pause.message(), "Trading connectivity stops",
                "pause confirmation must explain that trading connectivity stops");
        Assertions.contains(pause.message(), "started again",
                "pause confirmation must explain how to resume");
        Assertions.throwsType(NullPointerException.class,
                () -> ProfileSessionAction.START.prompt(null),
                "session confirmations must reject a null profile");
    }

    private void sessionActionButtons() {
        javax.swing.JButton start = new javax.swing.JButton();
        javax.swing.JButton stop = new javax.swing.JButton();
        javax.swing.JButton forceStop = new javax.swing.JButton();
        javax.swing.JButton restart = new javax.swing.JButton();
        javax.swing.JButton pause = new javax.swing.JButton();
        ProfileSessionAction.START.configureButton(start);
        ProfileSessionAction.STOP.configureButton(stop);
        ProfileSessionAction.FORCE_STOP.configureButton(forceStop);
        ProfileSessionAction.RESTART.configureButton(restart);
        ProfileSessionAction.PAUSE.configureButton(pause);

        for (javax.swing.JButton button : List.of(start, stop, forceStop, restart, pause)) {
            Assertions.isTrue(button.getPreferredSize().width >= ProfileSessionAction.MINIMUM_BUTTON_WIDTH,
                    button.getText() + " button is not wide enough");
            Assertions.isTrue(button.getPreferredSize().height >= ProfileSessionAction.MINIMUM_BUTTON_HEIGHT,
                    button.getText() + " button is not tall enough");
            Assertions.equals(button.getPreferredSize(), button.getMinimumSize(),
                    button.getText() + " minimum size must retain its action presentation");
            Assertions.equals(button.getPreferredSize(), button.getMaximumSize(),
                    button.getText() + " maximum size must prevent toolbar layout from shrinking it");
            Assertions.isTrue(button.getFont().isBold(), button.getText() + " button must use bold text");
            Assertions.isTrue(button.getBorder() != null, button.getText() + " button must have a distinct border");
            Assertions.isTrue(button.getToolTipText() != null && !button.getToolTipText().isBlank(),
                    button.getText() + " button must explain its action");
            Assertions.equals(button.getText(), button.getAccessibleContext().getAccessibleName(),
                    button.getText() + " accessible name mismatch");
            Assertions.equals(button.getToolTipText(), button.getAccessibleContext().getAccessibleDescription(),
                    button.getText() + " accessible description mismatch");
        }
        Assertions.equals("startProfileButton", start.getName(), "start component name mismatch");
        Assertions.equals("stopProfileButton", stop.getName(), "stop component name mismatch");
        Assertions.equals("forceStopProfileButton", forceStop.getName(),
                "force-stop component name mismatch");
        Assertions.equals("restartProfileButton", restart.getName(), "restart component name mismatch");
        Assertions.equals("pauseProfileButton", pause.getName(), "pause component name mismatch");
        List<javax.swing.JButton> actions = List.of(start, stop, forceStop, restart, pause);
        for (int first = 0; first < actions.size(); first++) {
            for (int second = first + 1; second < actions.size(); second++) {
                Assertions.notEquals(actions.get(first).getForeground(), actions.get(second).getForeground(),
                        actions.get(first).getText() + " and " + actions.get(second).getText()
                                + " buttons must have distinct accents");
            }
        }
    }


    private void statusIndicator() {
        Assertions.equals(StatusIndicator.Tone.GREEN,
                StatusIndicator.presentationFor(io.github.ibcmanager.model.RuntimeState.API_LISTENER_DETECTED).tone(),
                "API-listener state must use the green indicator");
        for (io.github.ibcmanager.model.RuntimeState state : List.of(
                io.github.ibcmanager.model.RuntimeState.RUNNING,
                io.github.ibcmanager.model.RuntimeState.VALIDATING,
                io.github.ibcmanager.model.RuntimeState.STARTING,
                io.github.ibcmanager.model.RuntimeState.AUTO_RECOVERY_STOPPING,
                io.github.ibcmanager.model.RuntimeState.AUTO_RECOVERY_FORCE_CLEANUP,
                io.github.ibcmanager.model.RuntimeState.AUTO_RECOVERY_COOLDOWN,
                io.github.ibcmanager.model.RuntimeState.STARTING_FRESH,
                io.github.ibcmanager.model.RuntimeState.RESTARTING,
                io.github.ibcmanager.model.RuntimeState.WAITING_FOR_LOGIN,
                io.github.ibcmanager.model.RuntimeState.WAITING_FOR_SECOND_FACTOR,
                io.github.ibcmanager.model.RuntimeState.PAUSING,
                io.github.ibcmanager.model.RuntimeState.PAUSED,
                io.github.ibcmanager.model.RuntimeState.STOPPING,
                io.github.ibcmanager.model.RuntimeState.UNKNOWN)) {
            Assertions.equals(StatusIndicator.Tone.YELLOW, StatusIndicator.presentationFor(state).tone(),
                    state + " must use the yellow attention indicator");
        }
        Assertions.equals(StatusIndicator.Tone.RED,
                StatusIndicator.presentationFor(io.github.ibcmanager.model.RuntimeState.STOPPED).tone(),
                "stopped state must use the red indicator");
        Assertions.equals(StatusIndicator.Tone.RED,
                StatusIndicator.presentationFor(io.github.ibcmanager.model.RuntimeState.ERROR).tone(),
                "error state must use the red indicator");
        Assertions.equals(StatusIndicator.Tone.RED,
                StatusIndicator.presentationFor(io.github.ibcmanager.model.RuntimeState.STARTUP_STALLED).tone(),
                "stalled startup must use the red indicator");
        Assertions.equals(StatusIndicator.Tone.RED,
                StatusIndicator.presentationFor(io.github.ibcmanager.model.RuntimeState.RECOVERY_FAILED).tone(),
                "failed unattended recovery must use the red indicator");
        Assertions.equals("Gateway startup stalled",
                StatusIndicator.presentationFor(io.github.ibcmanager.model.RuntimeState.STARTUP_STALLED).headline(),
                "stalled startup must be stated explicitly");
        Assertions.equals("Gateway running \u2014 API listener available",
                StatusIndicator.presentationFor(io.github.ibcmanager.model.RuntimeState.API_LISTENER_DETECTED).headline(),
                "green state must state exactly what was verified");
        Assertions.equals("Logged in \u2014 API listener unavailable",
                StatusIndicator.presentationFor(io.github.ibcmanager.model.RuntimeState.RUNNING).headline(),
                "login without a detected API listener must remain an attention state");
        StatusIndicator indicator = new StatusIndicator();
        indicator.updateStatus(io.github.ibcmanager.model.ProfileStatus.stopped(java.util.UUID.randomUUID()));
        Assertions.equals("profileStatusIndicator", indicator.getName(), "status component name mismatch");
        Assertions.equals(StatusIndicator.Tone.RED, indicator.presentation().tone(),
                "component must update to the supplied state");
        Assertions.isTrue(indicator.getAccessibleContext().getAccessibleDescription().contains("stopped"),
                "status indicator must expose non-color accessibility text");
    }

    private void statusWording() {
        for (io.github.ibcmanager.model.RuntimeState state : io.github.ibcmanager.model.RuntimeState.values()) {
            String headline = StatusIndicator.presentationFor(state).headline();
            String compact = StatusIndicator.shortLabel(state);
            Assertions.isFalse(headline.isBlank(), state + " needs a headline");
            Assertions.isFalse(compact.isBlank(), state + " needs a compact label");
            Assertions.notContains(headline, "_", "no raw diagnostic enum in the headline");
            Assertions.notContains(compact, "_", "no raw diagnostic enum in the status row");
            Assertions.notContains(headline.toLowerCase(java.util.Locale.ROOT), "trading ready",
                    "listener monitoring must not claim trading readiness");
        }
        Assertions.equals("Running", StatusIndicator.shortLabel(
                io.github.ibcmanager.model.RuntimeState.API_LISTENER_DETECTED), "compact success wording");
        Assertions.equals("Waiting for 2FA approval", StatusIndicator.shortLabel(
                io.github.ibcmanager.model.RuntimeState.WAITING_FOR_SECOND_FACTOR), "not a failure");
        Assertions.equals("Recovery: waiting to restart", StatusIndicator.shortLabel(
                io.github.ibcmanager.model.RuntimeState.AUTO_RECOVERY_COOLDOWN), "cooldown explained");
    }

    private void unknownListenerWording() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            StatusIndicator indicator = new StatusIndicator();
            indicator.updateStatus(new io.github.ibcmanager.model.ProfileStatus(
                    java.util.UUID.randomUUID(), io.github.ibcmanager.model.RuntimeState.RUNNING,
                    true, true, io.github.ibcmanager.model.PortListenerState.UNKNOWN, 42024,
                    java.time.Instant.EPOCH, null, "Listener inspection is temporarily unavailable", java.time.Instant.EPOCH));
            Assertions.equals("Logged in \u2014 checking API listener", indicator.presentation().headline(),
                    "unknown inspection does not assert a missing listener");
            Assertions.equals(StatusIndicator.Tone.YELLOW, indicator.presentation().tone(),
                    "uncertainty is not green");
        });
    }

    private void apiMonitoringTooltip() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            StatusIndicator indicator = new StatusIndicator();
            indicator.updateStatus(new io.github.ibcmanager.model.ProfileStatus(
                    java.util.UUID.randomUUID(), io.github.ibcmanager.model.RuntimeState.API_LISTENER_DETECTED,
                    true, true, io.github.ibcmanager.model.PortListenerState.LISTENING, 42024,
                    java.time.Instant.EPOCH, null, "Gateway's API listener is available.", java.time.Instant.EPOCH));
            Assertions.contains(indicator.getToolTipText(), "Your trading application verifies its own API connection",
                    "API responsibility remains available on demand");
            Assertions.notContains(indicator.getAccessibleContext().getAccessibleDescription(), "not verified",
                    "normal success does not read as a failed check");
            indicator.updateStatus(io.github.ibcmanager.model.ProfileStatus.stopped(java.util.UUID.randomUUID()));
            Assertions.notContains(indicator.getToolTipText(), "Your trading application",
                    "old success tooltip does not leak into the stopped state");
        });
    }

    private void settingsTable() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                ProfileSettingsTableModel model = new ProfileSettingsTableModel(Map.of());
                SettingsTable table = new SettingsTable(model);
                table.setSize(760, Math.max(300, model.getRowCount() * table.getRowHeight()));
                table.doLayout();
                int enumRow = rowFor(model, "AcceptIncomingConnectionAction");
                Assertions.isTrue(table.getCellEditor(enumRow, 2) instanceof DefaultCellEditor,
                        "enumerated values must use a constrained editor");
                DefaultCellEditor enumEditor = (DefaultCellEditor) table.getCellEditor(enumRow, 2);
                javax.swing.JComboBox<?> choices = (javax.swing.JComboBox<?>) enumEditor.getComponent();
                Assertions.equals("", choices.getItemAt(0), "empty imported fallback must remain selectable");
                int textRow = rowFor(model, "CommandPrompt");
                Assertions.isFalse(table.getCellEditor(textRow, 2) instanceof DefaultCellEditor
                                && ((DefaultCellEditor) table.getCellEditor(textRow, 2)).getComponent()
                                instanceof javax.swing.JComboBox,
                        "free-text values must not use an enum combo box");
                Rectangle cell = table.getCellRect(enumRow, 1, true);
                Point point = new Point(cell.x + 2, cell.y + 2);
                MouseEvent event = new MouseEvent(table, MouseEvent.MOUSE_MOVED,
                        System.currentTimeMillis(), 0, point.x, point.y, 0, false);
                String tooltip = table.getToolTipText(event);
                Assertions.contains(tooltip, "AcceptIncomingConnectionAction",
                        "tooltip must identify the exact IBC key");
                Assertions.contains(tooltip, "Included configuration default", "tooltip must distinguish template and runtime fallback");
                Assertions.equals(null, table.getToolTipText(new MouseEvent(table, MouseEvent.MOUSE_MOVED,
                        System.currentTimeMillis(), 0, -10, -10, 0, false)),
                        "points outside rows must not produce a tooltip");
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        if (failure.get() != null) throw new AssertionError("Swing table test failed", failure.get());
    }

    private void profileEditResult() throws Exception {
        Path root = TestSupport.tempDirectory("edit-result");
        try {
            Profile profile = TestSupport.validProfile(root.resolve("install"));
            char[] original = "temporary-password".toCharArray();
            ProfileEditResult result = new ProfileEditResult(profile, original);
            original[0] = 'X';
            Assertions.equals(profile, result.profile(), "result must retain profile");
            Assertions.isTrue(result.hasPassword(), "non-empty password must be reported");
            char[] first = result.passwordCopy();
            Assertions.equals('t', first[0], "constructor must defensively copy password");
            first[1] = 'X';
            Assertions.equals('e', result.passwordCopy()[1], "passwordCopy must be defensive");
            result.close();
            char[] cleared = result.passwordCopy();
            for (char value : cleared) Assertions.equals('\0', value, "close must clear internal password array");
            ProfileEditResult empty = new ProfileEditResult(profile, null);
            Assertions.isFalse(empty.hasPassword(), "null password must become empty");
            Assertions.equals(0, empty.passwordCopy().length, "empty password copy must have zero length");
            empty.close();
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void layoutHelpers() {
        GridBagConstraints constraints = UiUtil.constraints(2, 3);
        Assertions.equals(2, constraints.gridx, "grid x mismatch");
        Assertions.equals(3, constraints.gridy, "grid y mismatch");
        Assertions.equals(GridBagConstraints.WEST, constraints.anchor, "grid anchor mismatch");
        Assertions.equals(GridBagConstraints.HORIZONTAL, constraints.fill, "grid fill mismatch");
        Assertions.isTrue(constraints.insets.top > 0, "grid constraints must provide spacing");
        JPanel panel = UiUtil.formPanel();
        Assertions.isTrue(panel.getLayout() instanceof java.awt.GridBagLayout,
                "form panel must use GridBagLayout");
        javax.swing.JTextField field = new javax.swing.JTextField();
        UiUtil.addRow(panel, 0, "Label", field);
        Assertions.equals(2, panel.getComponentCount(), "form row must add label and field");
        JPanel path = UiUtil.pathField(panel, field, true);
        Assertions.equals(2, path.getComponentCount(), "path field must combine input and browse button");
    }

    private void eventDispatchHelper() throws Exception {
        CountDownLatch queued = new CountDownLatch(1);
        AtomicBoolean queuedOnEdt = new AtomicBoolean();
        Thread worker = new Thread(() -> UiUtil.onEdt(() -> {
            queuedOnEdt.set(SwingUtilities.isEventDispatchThread());
            queued.countDown();
        }), "ui-test-worker");
        worker.start();
        worker.join();
        Assertions.eventually(Duration.ofSeconds(2), () -> queued.getCount() == 0,
                "off-EDT action must be queued");
        Assertions.isTrue(queuedOnEdt.get(), "queued action must execute on EDT");

        AtomicBoolean direct = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> UiUtil.onEdt(() ->
                direct.set(SwingUtilities.isEventDispatchThread())));
        Assertions.isTrue(direct.get(), "EDT action must execute immediately on EDT");
    }

    private void themeInstall() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                UiTheme.install();
                UiTheme.install();
                Assertions.isTrue(javax.swing.UIManager.getLookAndFeel() != null,
                        "theme installation must leave a usable look and feel");
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        if (failure.get() != null) throw new AssertionError("theme installation failed", failure.get());
    }

    private static int rowFor(ProfileSettingsTableModel model, String key) {
        for (int row = 0; row < model.getRowCount(); row++) {
            if (model.definitionAt(row).key().equals(key)) return row;
        }
        throw new AssertionError("Missing schema row: " + key);
    }

    private static List<SettingDefinition> definitions(ProfileSettingsTableModel model) {
        List<SettingDefinition> result = new ArrayList<>();
        for (int row = 0; row < model.getRowCount(); row++) result.add(model.definitionAt(row));
        return result;
    }
}
