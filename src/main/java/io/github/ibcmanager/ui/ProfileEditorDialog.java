package io.github.ibcmanager.ui;

import io.github.ibcmanager.discovery.DetectedInstallation;
import io.github.ibcmanager.config.ManagedConfigService;
import io.github.ibcmanager.config.SecondFactorPolicy;
import io.github.ibcmanager.discovery.InstallationDiscoveryService;
import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.TargetType;
import io.github.ibcmanager.model.TradingMode;
import io.github.ibcmanager.model.TwoFactorTimeoutAction;
import io.github.ibcmanager.security.WindowsCommandSafety;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@SuppressWarnings("serial")
public final class ProfileEditorDialog extends JDialog {
    private final boolean newProfile;
    private final boolean credentialStoreAvailable;
    private final Profile source;
    private final InstallationDiscoveryService installationDiscovery = new InstallationDiscoveryService();

    private final JTextField nameField = new JTextField(30);
    private final JCheckBox enabledBox = new JCheckBox("Profile enabled");
    private final JComboBox<TradingMode> tradingModeBox = new JComboBox<>(TradingMode.values());
    private final JTextField versionField = new JTextField(12);
    private final JTextField twsPathField = new JTextField(36);
    private final JTextField settingsPathField = new JTextField(36);
    private final JTextField ibcJavaPathField = new JTextField(36);
    private final JSpinner apiPortSpinner = new JSpinner(new SpinnerNumberModel(4002, 1, 65535, 1));
    private final JSpinner commandPortSpinner = new JSpinner(new SpinnerNumberModel(7462, 1, 65535, 1));
    private final JTextField bindAddressField = new JTextField("127.0.0.1", 20);
    private final JTextField usernameField = new JTextField(24);
    private final JTextField secondFactorDeviceField = new JTextField(24);
    private final JComboBox<CredentialMode> credentialModeBox = new JComboBox<>(new CredentialMode[] {CredentialMode.MANUAL, CredentialMode.ENCRYPTED});
    private final JPasswordField passwordField = new JPasswordField(24);
    private final JPasswordField confirmPasswordField = new JPasswordField(24);
    private final JLabel passwordHint = new JLabel();
    private final JCheckBox reloginAfterSecondFactorTimeoutBox =
            new JCheckBox("Retry IBKR Mobile login after 5 minutes without approval");
    private final JCheckBox forceApiPortBox =
            new JCheckBox("Force this API port into IB Gateway through IBC at startup");
    private final JCheckBox autoStartBox = new JCheckBox("Start this profile when IBC Manager starts");
    private final JCheckBox autoRecoverStartupStallBox =
            new JCheckBox("Automatically recover a stalled IB Gateway startup");
    private final JCheckBox minimizeBox = new JCheckBox("Minimize IB Gateway after login");
    private final JSpinner gracefulStopSpinner = new JSpinner(new SpinnerNumberModel(90, 30, 300, 1));
    private final ProfileSettingsTableModel settingsModel;
    private final SettingsTable settingsTable;
    private ProfileEditResult result;
    private Runnable installStartup;
    private Runnable removeStartup;

    private ProfileEditorDialog(Frame owner, Profile profile, boolean credentialStoreAvailable) {
        super(owner, profile == null ? "New IBC profile" : "Edit profile - " + profile.name(), true);
        this.newProfile = profile == null;
        this.credentialStoreAvailable = credentialStoreAvailable;
        this.source = profile == null ? Profile.builder().profileOnlyConfiguration(true).build() : profile;
        this.settingsModel = new ProfileSettingsTableModel(source.settings());
        this.settingsTable = new SettingsTable(settingsModel);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        buildUi();
        loadProfile();
        updateCredentialControls();
        setMinimumSize(new Dimension(830, 700));
        setSize(960, 780);
        setLocationRelativeTo(owner);
    }

    public static Optional<ProfileEditResult> showDialog(Frame owner, Profile profile,
            boolean credentialStoreAvailable) {
        if (profile != null && profile.targetType() != TargetType.GATEWAY) {
            JOptionPane.showMessageDialog(owner, "This legacy TWS profile is preserved but not supported. Create an IB Gateway profile instead.",
                    "Unsupported legacy profile", JOptionPane.WARNING_MESSAGE);
            return Optional.empty();
        }
        return showDialog(owner, profile, credentialStoreAvailable, null, null);
    }

    public static Optional<ProfileEditResult> showDialog(Frame owner, Profile profile,
            boolean credentialStoreAvailable, Runnable installStartup, Runnable removeStartup) {
        if (profile != null && profile.targetType() != TargetType.GATEWAY) {
            JOptionPane.showMessageDialog(owner, "This legacy TWS profile is preserved but unsupported.",
                    "Unsupported profile", JOptionPane.WARNING_MESSAGE);
            return Optional.empty();
        }
        ProfileEditorDialog dialog = new ProfileEditorDialog(owner, profile, credentialStoreAvailable);
        dialog.installStartup = installStartup;
        dialog.removeStartup = removeStartup;
        dialog.setVisible(true);
        return Optional.ofNullable(dialog.result);
    }

    private void buildUi() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Profile", createGeneralPanel());
        tabs.addTab("Gateway settings", createSettingsPanel());
        tabs.addTab("Windows startup", createStartupPanel());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(event -> dispose());
        JButton save = new JButton("Save");
        save.addActionListener(event -> save());
        getRootPane().setDefaultButton(save);
        buttons.add(cancel);
        buttons.add(save);

        add(tabs, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
    }

    private JComponent createGeneralPanel() {
        JPanel panel = UiUtil.formPanel();
        int row = 0;
        JButton detectButton = new JButton("Detect common installations...");
        detectButton.setName("detectInstallationsButton");
        detectButton.setEnabled(installationDiscovery.isAvailable());
        detectButton.setToolTipText(installationDiscovery.isAvailable()
                ? "Search common Windows locations for offline IB Gateway installations"
                : "Automatic discovery is available on Windows");
        detectButton.addActionListener(event -> detectInstallations());

        UiUtil.addRow(panel, row++, "Installation", installationActions(detectButton));
        JLabel engineLabel = new JLabel("Included engine " + io.github.ibcmanager.app.Version.ENGINE_VERSION);
        engineLabel.setName("integratedEngineLabel");
        UiUtil.addRow(panel, row++, "Automation engine", engineLabel);
        UiUtil.addRow(panel, row++, "Profile name", nameField);
        UiUtil.addRow(panel, row++, "Enabled", enabledBox);
        UiUtil.addRow(panel, row++, "Application", new JLabel("IB Gateway (Windows)"));
        UiUtil.addRow(panel, row++, "Trading mode", tradingModeBox);
        UiUtil.addRow(panel, row++, "Offline version number", versionField);
        versionField.setToolTipText("Numeric major version used by the offline installer, for example 1045");
        String batchPathGuidance = WindowsCommandSafety.externalPathGuidance();
        twsPathField.setToolTipText(batchPathGuidance);
        settingsPathField.setToolTipText(batchPathGuidance);
        ibcJavaPathField.setToolTipText("Optional folder containing java.exe. Leave blank to let the official "
                + "StartIBC.bat select the runtime bundled with the chosen IB Gateway installation. "
                + "IB Gateway 10.48 and newer require Java 25 when an override is used. "
                + batchPathGuidance);
        UiUtil.addRow(panel, row++, "IB Gateway root", UiUtil.pathField(this, twsPathField, true));
        UiUtil.addRow(panel, row++, "Gateway settings directory", UiUtil.pathField(this, settingsPathField, true));
        UiUtil.addRow(panel, row++, "IBC Java override", UiUtil.pathField(this, ibcJavaPathField, true));

        JPanel portPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        portPanel.add(apiPortSpinner);
        portPanel.add(new JLabel("     IBC command port: "));
        portPanel.add(commandPortSpinner);
        UiUtil.addRow(panel, row++, "API socket port", portPanel);
        forceApiPortBox.setToolTipText("Normally disabled. Enabling this asks IBC to open the application configuration UI and change its persistent API port.");
        UiUtil.addRow(panel, row++, "API port override", forceApiPortBox);
        UiUtil.addRow(panel, row++, "Command bind address", bindAddressField);
        UiUtil.addRow(panel, row++, "IBKR username", usernameField);
        secondFactorDeviceField.setName("secondFactorDeviceField");
        secondFactorDeviceField.setToolTipText("Exact IBC SecondFactorDevice value; leave blank to use IBC's default device selection");
        UiUtil.addRow(panel, row++, "Second-factor device", secondFactorDeviceField);
        UiUtil.addRow(panel, row++, "Credential handling", credentialModeBox);
        UiUtil.addRow(panel, row++, "Password", passwordField);
        UiUtil.addRow(panel, row++, "Confirm password", confirmPasswordField);
        UiUtil.addRow(panel, row++, "", passwordHint);
        reloginAfterSecondFactorTimeoutBox.setName("secondFactorRetryBox");
        reloginAfterSecondFactorTimeoutBox.setToolTipText("Uses the maintained engine's independent retry timer. After "
                + (SecondFactorPolicy.RETRY_TIMEOUT_SECONDS / 60)
                + " minutes from the detected 2FA challenge, it cancels the pending prompt and retries login. "
                + "Phone delivery depends on IBKR and the device/network; no process is killed.");
        UiUtil.addRow(panel, row++, "2FA notification retry", reloginAfterSecondFactorTimeoutBox);
        UiUtil.addRow(panel, row++, "Automatic startup", autoStartBox);
        autoRecoverStartupStallBox.setName("autoRecoverStartupStallBox");
        autoRecoverStartupStallBox.setToolTipText("After five minutes without login, second-factor, or API-listener progress, IBC Manager captures diagnostics, tries graceful Stop once, force-cleans only this profile if required, waits for ports to be released, and performs one fresh Start. A fresh login may trigger IBKR Mobile approval.");
        UiUtil.addRow(panel, row++, "Stalled-start recovery", autoRecoverStartupStallBox);
        UiUtil.addRow(panel, row++, "Window handling", minimizeBox);
        UiUtil.addRow(panel, row++, "Graceful stop timeout", gracefulStopSpinner);

        JLabel warning = new JLabel("<html><b>Live mode:</b> verify the selected account and API port in paper trading first. "
                + "IBC Manager does not generate or submit TOTP codes.</html>");
        warning.setBorder(BorderFactory.createEmptyBorder(10, 0, 2, 0));
        GridBagConstraints warningConstraints = UiUtil.constraints(0, row);
        warningConstraints.gridwidth = 2;
        warningConstraints.weightx = 1;
        panel.add(warning, warningConstraints);
        row++;

        GridBagConstraints filler = UiUtil.constraints(0, row);
        filler.gridwidth = 2;
        filler.weighty = 1;
        filler.fill = GridBagConstraints.BOTH;
        panel.add(new JPanel(), filler);

        credentialModeBox.addActionListener(event -> updateCredentialControls());

        JPanel content = new JPanel(new BorderLayout());
        content.add(panel, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(content,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        return scroll;
    }

    static JPanel installationActions(JButton... buttons) {
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        for (JButton button : buttons) {
            actions.add(button);
            button.setMinimumSize(button.getPreferredSize());
        }
        return actions;
    }

    private void detectInstallations() {
        List<DetectedInstallation> detected = installationDiscovery.discover();
        if (detected.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No matching installation was found in the common Windows locations.\n"
                            + "Select the IB Gateway directories manually.",
                    "No installations detected", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        DetectedInstallation selected = (DetectedInstallation) JOptionPane.showInputDialog(this,
                "Select the offline IB Gateway installation to use:",
                "Detected installations", JOptionPane.QUESTION_MESSAGE, null,
                detected.toArray(DetectedInstallation[]::new), detected.get(0));
        if (selected == null) return;
        versionField.setText(selected.version());
        twsPathField.setText(selected.twsRoot().toString());
        settingsPathField.setText(selected.settingsPath().toString());
        if (newProfile && (nameField.getText().isBlank() || "New profile".equals(nameField.getText().trim()))) {
            nameField.setText(selected.targetType() + " " + selected.version());
        }
        if (newProfile && ((Integer) apiPortSpinner.getValue()) == 4002) {
            apiPortSpinner.setValue(defaultApiPort(selected.targetType(),
                    (TradingMode) tradingModeBox.getSelectedItem()));
        }
    }

    private static int defaultApiPort(TargetType targetType, TradingMode tradingMode) {
        return tradingMode == TradingMode.LIVE ? 4001 : 4002;
    }

    private JPanel createSettingsPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JLabel help = new JLabel("<html>All settings are owned by this profile. Values shown are the values generated for the engine. "
                + "Blank may mean an engine fallback or keeping Gateway's setting; hover for details.</html>");
        panel.add(help, BorderLayout.NORTH);
        panel.add(new JScrollPane(settingsTable), BorderLayout.CENTER);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton clear = new JButton("Reset selected to included default");
        clear.addActionListener(event -> {
            if (settingsTable.isEditing()) settingsTable.getCellEditor().cancelCellEditing();
            int selected = settingsTable.getSelectedRow();
            if (selected >= 0) settingsModel.reset(settingsTable.convertRowIndexToModel(selected));
        });
        controls.add(clear);
        JButton add = new JButton("Add engine property...");
        add.addActionListener(event -> {
            if (settingsTable.isEditing() && !settingsTable.getCellEditor().stopCellEditing()) return;
            String key = JOptionPane.showInputDialog(this, "Engine property name:", "Additional property", JOptionPane.QUESTION_MESSAGE);
            if (key == null) return;
            try { settingsModel.addProperty(key, ""); }
            catch (IllegalArgumentException failure) {
                JOptionPane.showMessageDialog(this, failure.getMessage(), "Invalid property", JOptionPane.ERROR_MESSAGE);
            }
        });
        controls.add(add);
        panel.add(controls, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createStartupPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        panel.add(new JLabel("<html>Windows startup applies to the whole Manager, not just this profile.<br>"
                + "Task changes take effect immediately. Profile auto-start is configured on the Profile tab.</html>"), BorderLayout.NORTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton install = new JButton("Install/update Windows startup task");
        JButton remove = new JButton("Remove Windows startup task");
        install.addActionListener(event -> { if (installStartup != null) installStartup.run(); });
        remove.addActionListener(event -> { if (removeStartup != null) removeStartup.run(); });
        actions.add(install); actions.add(remove); panel.add(actions, BorderLayout.CENTER);
        return panel;
    }

    private void loadProfile() {
        nameField.setText(source.name());
        enabledBox.setSelected(source.enabled());
        tradingModeBox.setSelectedItem(source.tradingMode());
        versionField.setText(source.twsMajorVersion());
        twsPathField.setText(pathText(source.twsPath()));
        settingsPathField.setText(pathText(source.twsSettingsPath()));
        ibcJavaPathField.setText(pathText(source.ibcJavaPath()));
        apiPortSpinner.setValue(source.apiPort());
        commandPortSpinner.setValue(source.commandServerPort());
        bindAddressField.setText(source.bindAddress());
        usernameField.setText(source.username());
        secondFactorDeviceField.setText(ManagedConfigService.settingValue(
                source, ManagedConfigService.SECOND_FACTOR_DEVICE_KEY));
        credentialModeBox.setSelectedItem(source.credentialMode());
        reloginAfterSecondFactorTimeoutBox.setSelected(source.reloginAfterSecondFactorTimeout());
        forceApiPortBox.setSelected(source.forceApiPortAtLaunch());
        autoStartBox.setSelected(source.autoStart());
        autoRecoverStartupStallBox.setSelected(source.autoRecoverStartupStall());
        minimizeBox.setSelected(source.minimizeMainWindow());
        gracefulStopSpinner.setValue(source.gracefulStopTimeoutSeconds());
    }

    private void updateCredentialControls() {
        CredentialMode mode = (CredentialMode) credentialModeBox.getSelectedItem();
        boolean encrypted = mode == CredentialMode.ENCRYPTED;
        usernameField.setEnabled(true);
        passwordField.setEnabled(encrypted && credentialStoreAvailable);
        confirmPasswordField.setEnabled(encrypted && credentialStoreAvailable);
        if (encrypted && !credentialStoreAvailable) {
            passwordHint.setText("Encrypted password storage is unavailable on this operating system.");
        } else if (encrypted && !newProfile) {
            passwordHint.setText("Leave both password fields blank to retain the stored password.");
        } else if (encrypted) {
            passwordHint.setText("Protected with Windows DPAPI for the current Windows user.");

        } else {
            passwordHint.setText("The password must be entered in the IBKR login window.");
        }
    }

    private void save() {
        if (settingsTable.isEditing() && !settingsTable.getCellEditor().stopCellEditing()) return;
        char[] password = passwordField.getPassword();
        char[] confirmation = confirmPasswordField.getPassword();
        try {
            CredentialMode mode = (CredentialMode) credentialModeBox.getSelectedItem();
            if (mode == CredentialMode.ENCRYPTED && !credentialStoreAvailable) {
                throw new IllegalArgumentException("Encrypted credential storage is unavailable");
            }
            if (!Arrays.equals(password, confirmation)) {
                throw new IllegalArgumentException("Password and confirmation do not match");
            }
            if (mode != CredentialMode.ENCRYPTED && password.length > 0) {
                throw new IllegalArgumentException("A password can only be stored in encrypted credential mode");
            }
            Profile profile = source.toBuilder()
                    .name(nameField.getText())
                    .enabled(enabledBox.isSelected())
                    .profileOnlyConfiguration(true)
                    .targetType(TargetType.GATEWAY)
                    .tradingMode((TradingMode) tradingModeBox.getSelectedItem())
                    .twsMajorVersion(versionField.getText())
                    .twsPath(toPath(twsPathField.getText()))
                    .twsSettingsPath(toPath(settingsPathField.getText()))
                    .baseConfigPath(Path.of(""))
                    .ibcJavaPath(toPath(ibcJavaPathField.getText()))
                    .apiPort((Integer) apiPortSpinner.getValue())
                    .commandServerPort((Integer) commandPortSpinner.getValue())
                    .bindAddress(bindAddressField.getText())
                    .username(usernameField.getText())
                    .credentialMode(mode)
                    .twoFactorTimeoutAction(TwoFactorTimeoutAction.EXIT)
                    .reloginAfterSecondFactorTimeout(reloginAfterSecondFactorTimeoutBox.isSelected())
                    .forceApiPortAtLaunch(forceApiPortBox.isSelected())
                    .autoStart(autoStartBox.isSelected())
                    .autoRecoverStartupStall(autoRecoverStartupStallBox.isSelected())
                    .minimizeMainWindow(minimizeBox.isSelected())
                    .gracefulStopTimeoutSeconds((Integer) gracefulStopSpinner.getValue())
                    .settings(withSecondFactorDevice(settingsModel.settings(), secondFactorDeviceField.getText()))
                    .build();
            result = new ProfileEditResult(profile, password);
            dispose();
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Invalid profile", JOptionPane.ERROR_MESSAGE);
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(confirmation, '\0');
        }
    }

    static Map<String, String> withSecondFactorDevice(Map<String, String> settings, String device) {
        Map<String, String> result = new LinkedHashMap<>(settings);
        result.keySet().removeIf(key -> key.equalsIgnoreCase(ManagedConfigService.SECOND_FACTOR_DEVICE_KEY));
        String normalized = device == null ? "" : device.trim();
        if (!normalized.isEmpty()) {
            result.put(ManagedConfigService.SECOND_FACTOR_DEVICE_KEY, normalized);
        }
        return result;
    }

    static Path toPath(String value) {
        if (value == null || value.isBlank()) return Path.of("");
        try {
            return Path.of(value.trim()).toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException ex) {
            throw new IllegalArgumentException("The selected path is invalid on this operating system", ex);
        }
    }

    private static String pathText(Path path) {
        return path == null || path.toString().isBlank() ? "" : path.toString();
    }
}
