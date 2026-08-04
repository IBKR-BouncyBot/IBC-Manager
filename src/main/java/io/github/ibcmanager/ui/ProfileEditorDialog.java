package io.github.ibcmanager.ui;

import io.github.ibcmanager.app.Version;
import io.github.ibcmanager.discovery.DetectedInstallation;
import io.github.ibcmanager.config.ManagedConfigService;
import io.github.ibcmanager.discovery.InstallationDiscoveryService;
import io.github.ibcmanager.install.IbcInstallerService;
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
    private final IbcInstallerService ibcInstallerService = new IbcInstallerService();

    private final JTextField nameField = new JTextField(30);
    private final JCheckBox enabledBox = new JCheckBox("Profile enabled");
    private final JComboBox<TargetType> targetBox = new JComboBox<>(TargetType.values());
    private final JComboBox<TradingMode> tradingModeBox = new JComboBox<>(TradingMode.values());
    private final JTextField versionField = new JTextField(12);
    private final JTextField ibcPathField = new JTextField(36);
    private final JTextField twsPathField = new JTextField(36);
    private final JTextField settingsPathField = new JTextField(36);
    private final JTextField baseConfigField = new JTextField(36);
    private final JTextField ibcJavaPathField = new JTextField(36);
    private final JSpinner apiPortSpinner = new JSpinner(new SpinnerNumberModel(4002, 1, 65535, 1));
    private final JSpinner commandPortSpinner = new JSpinner(new SpinnerNumberModel(7462, 1, 65535, 1));
    private final JTextField bindAddressField = new JTextField("127.0.0.1", 20);
    private final JTextField usernameField = new JTextField(24);
    private final JTextField secondFactorDeviceField = new JTextField(24);
    private final JComboBox<CredentialMode> credentialModeBox = new JComboBox<>(CredentialMode.values());
    private final JPasswordField passwordField = new JPasswordField(24);
    private final JPasswordField confirmPasswordField = new JPasswordField(24);
    private final JLabel passwordHint = new JLabel();
    private final JComboBox<TwoFactorTimeoutAction> twoFactorActionBox =
            new JComboBox<>(TwoFactorTimeoutAction.values());
    private final JCheckBox reloginAfterSecondFactorTimeoutBox =
            new JCheckBox("Retry the login sequence inside IBC after a 2FA timeout");
    private final JCheckBox forceApiPortBox =
            new JCheckBox("Force this API port into TWS/Gateway through IBC at startup");
    private final JCheckBox autoStartBox = new JCheckBox("Start this profile when IBC Manager starts");
    private final JCheckBox minimizeBox = new JCheckBox("Minimize TWS/Gateway after login");
    private final JSpinner gracefulStopSpinner = new JSpinner(new SpinnerNumberModel(90, 30, 300, 1));
    private final ProfileSettingsTableModel settingsModel;
    private final SettingsTable settingsTable;
    private ProfileEditResult result;

    private ProfileEditorDialog(Frame owner, Profile profile, boolean credentialStoreAvailable) {
        super(owner, profile == null ? "New IBC profile" : "Edit profile - " + profile.name(), true);
        this.newProfile = profile == null;
        this.credentialStoreAvailable = credentialStoreAvailable;
        this.source = profile == null ? Profile.builder().build() : profile;
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
        ProfileEditorDialog dialog = new ProfileEditorDialog(owner, profile, credentialStoreAvailable);
        dialog.setVisible(true);
        return Optional.ofNullable(dialog.result);
    }

    private void buildUi() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Profile", createGeneralPanel());
        tabs.addTab("IBC settings", createSettingsPanel());

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
                ? "Search common Windows locations for IBC and offline TWS/IB Gateway installations"
                : "Automatic discovery is available on Windows");
        detectButton.addActionListener(event -> detectInstallations());

        JButton installButton = new JButton("Install IBC " + Version.IBC_BASELINE + " from GitHub...");
        installButton.setName("installIbcButton");
        installButton.setEnabled(ibcInstallerService.isAvailable());
        installButton.setToolTipText(ibcInstallerService.isAvailable()
                ? "Download the official Windows IBC " + Version.IBC_BASELINE
                        + " release from GitHub and install it in C:\\IBC"
                : "Automatic IBC installation is available on Windows");
        installButton.addActionListener(event -> downloadAndInstallIbc());
        UiUtil.addRow(panel, row++, "Installation", installationActions(detectButton, installButton));
        UiUtil.addRow(panel, row++, "Profile name", nameField);
        UiUtil.addRow(panel, row++, "Enabled", enabledBox);
        UiUtil.addRow(panel, row++, "Application", targetBox);
        UiUtil.addRow(panel, row++, "Trading mode", tradingModeBox);
        UiUtil.addRow(panel, row++, "Offline version number", versionField);
        versionField.setToolTipText("Numeric major version used by the offline installer, for example 1045");
        String batchPathGuidance = WindowsCommandSafety.externalPathGuidance();
        ibcPathField.setToolTipText(batchPathGuidance);
        twsPathField.setToolTipText(batchPathGuidance);
        settingsPathField.setToolTipText(batchPathGuidance);
        baseConfigField.setToolTipText(batchPathGuidance);
        ibcJavaPathField.setToolTipText("Optional folder containing java.exe for IBC. "
                + batchPathGuidance);
        UiUtil.addRow(panel, row++, "IBC directory", UiUtil.pathField(this, ibcPathField, true));
        UiUtil.addRow(panel, row++, "TWS/Gateway root", UiUtil.pathField(this, twsPathField, true));
        UiUtil.addRow(panel, row++, "TWS settings directory", UiUtil.pathField(this, settingsPathField, true));
        UiUtil.addRow(panel, row++, "Existing/base config.ini", UiUtil.pathField(this, baseConfigField, false));
        UiUtil.addRow(panel, row++, "IBC Java directory", UiUtil.pathField(this, ibcJavaPathField, true));

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
        reloginAfterSecondFactorTimeoutBox.setToolTipText("Controls IBC's ReloginAfterSecondFactorAuthenticationTimeout setting inside the current Java process");
        UiUtil.addRow(panel, row++, "IBC 2FA retry policy", reloginAfterSecondFactorTimeoutBox);
        twoFactorActionBox.setToolTipText("Controls what StartIBC.bat does only after IBC exits with its 2FA-timeout exit code");
        UiUtil.addRow(panel, row++, "After IBC exits on 2FA timeout", twoFactorActionBox);
        UiUtil.addRow(panel, row++, "Automatic startup", autoStartBox);
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

    static JPanel installationActions(JButton detectButton, JButton installButton) {
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.add(detectButton);
        actions.add(installButton);
        detectButton.setMinimumSize(detectButton.getPreferredSize());
        installButton.setMinimumSize(installButton.getPreferredSize());
        return actions;
    }

    private void downloadAndInstallIbc() {
        IbcInstallerDialog.installDefault(this, ibcInstallerService).ifPresent(installed ->
                ibcPathField.setText(installed.installationDirectory().toString()));
    }

    private void detectInstallations() {
        List<DetectedInstallation> detected = installationDiscovery.discover();
        if (detected.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No matching installation was found in the common Windows locations.\n"
                            + "Select the IBC and TWS/IB Gateway directories manually.",
                    "No installations detected", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        DetectedInstallation selected = (DetectedInstallation) JOptionPane.showInputDialog(this,
                "Select the IBC and offline TWS/IB Gateway installation to use:",
                "Detected installations", JOptionPane.QUESTION_MESSAGE, null,
                detected.toArray(DetectedInstallation[]::new), detected.get(0));
        if (selected == null) return;
        targetBox.setSelectedItem(selected.targetType());
        versionField.setText(selected.version());
        ibcPathField.setText(selected.ibcPath().toString());
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
        if (targetType == TargetType.TWS) return tradingMode == TradingMode.LIVE ? 7496 : 7497;
        return tradingMode == TradingMode.LIVE ? 4001 : 4002;
    }

    private JPanel createSettingsPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JLabel help = new JLabel("<html>Only explicit overrides are saved. Blank means IBC's existing/default value. "
                + "Hover over a row for its description.</html>");
        panel.add(help, BorderLayout.NORTH);
        panel.add(new JScrollPane(settingsTable), BorderLayout.CENTER);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton clear = new JButton("Clear selected override");
        clear.addActionListener(event -> {
            int selected = settingsTable.getSelectedRow();
            if (selected >= 0) settingsModel.reset(settingsTable.convertRowIndexToModel(selected));
        });
        controls.add(clear);
        panel.add(controls, BorderLayout.SOUTH);
        return panel;
    }

    private void loadProfile() {
        nameField.setText(source.name());
        enabledBox.setSelected(source.enabled());
        targetBox.setSelectedItem(source.targetType());
        tradingModeBox.setSelectedItem(source.tradingMode());
        versionField.setText(source.twsMajorVersion());
        ibcPathField.setText(pathText(source.ibcPath()));
        twsPathField.setText(pathText(source.twsPath()));
        settingsPathField.setText(pathText(source.twsSettingsPath()));
        baseConfigField.setText(pathText(source.baseConfigPath()));
        ibcJavaPathField.setText(pathText(source.ibcJavaPath()));
        apiPortSpinner.setValue(source.apiPort());
        commandPortSpinner.setValue(source.commandServerPort());
        bindAddressField.setText(source.bindAddress());
        usernameField.setText(source.username());
        secondFactorDeviceField.setText(ManagedConfigService.settingValue(
                source, ManagedConfigService.SECOND_FACTOR_DEVICE_KEY));
        credentialModeBox.setSelectedItem(source.credentialMode());
        twoFactorActionBox.setSelectedItem(source.twoFactorTimeoutAction());
        reloginAfterSecondFactorTimeoutBox.setSelected(source.reloginAfterSecondFactorTimeout());
        forceApiPortBox.setSelected(source.forceApiPortAtLaunch());
        autoStartBox.setSelected(source.autoStart());
        minimizeBox.setSelected(source.minimizeMainWindow());
        gracefulStopSpinner.setValue(source.gracefulStopTimeoutSeconds());
    }

    private void updateCredentialControls() {
        CredentialMode mode = (CredentialMode) credentialModeBox.getSelectedItem();
        boolean encrypted = mode == CredentialMode.ENCRYPTED;
        boolean existing = mode == CredentialMode.EXISTING_CONFIG;
        usernameField.setEnabled(!existing);
        passwordField.setEnabled(encrypted && credentialStoreAvailable);
        confirmPasswordField.setEnabled(encrypted && credentialStoreAvailable);
        baseConfigField.setEnabled(true);
        if (encrypted && !credentialStoreAvailable) {
            passwordHint.setText("Encrypted password storage is unavailable on this operating system.");
        } else if (encrypted && !newProfile) {
            passwordHint.setText("Leave both password fields blank to retain the stored password.");
        } else if (encrypted) {
            passwordHint.setText("Protected with Windows DPAPI for the current Windows user.");
        } else if (existing) {
            passwordHint.setText("IBC Manager will not copy or display credentials from the existing config file.");
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
                    .targetType((TargetType) targetBox.getSelectedItem())
                    .tradingMode((TradingMode) tradingModeBox.getSelectedItem())
                    .twsMajorVersion(versionField.getText())
                    .ibcPath(toPath(ibcPathField.getText()))
                    .twsPath(toPath(twsPathField.getText()))
                    .twsSettingsPath(toPath(settingsPathField.getText()))
                    .baseConfigPath(toPath(baseConfigField.getText()))
                    .ibcJavaPath(toPath(ibcJavaPathField.getText()))
                    .apiPort((Integer) apiPortSpinner.getValue())
                    .commandServerPort((Integer) commandPortSpinner.getValue())
                    .bindAddress(bindAddressField.getText())
                    .username(usernameField.getText())
                    .credentialMode(mode)
                    .twoFactorTimeoutAction((TwoFactorTimeoutAction) twoFactorActionBox.getSelectedItem())
                    .reloginAfterSecondFactorTimeout(reloginAfterSecondFactorTimeoutBox.isSelected())
                    .forceApiPortAtLaunch(forceApiPortBox.isSelected())
                    .autoStart(autoStartBox.isSelected())
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
