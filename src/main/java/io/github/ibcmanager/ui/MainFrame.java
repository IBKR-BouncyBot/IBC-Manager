package io.github.ibcmanager.ui;

import io.github.ibcmanager.app.AppServices;
import io.github.ibcmanager.app.StartupCommand;
import io.github.ibcmanager.app.Version;
import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.ProfileStatus;
import io.github.ibcmanager.model.RuntimeState;
import io.github.ibcmanager.model.Severity;
import io.github.ibcmanager.model.TargetType;
import io.github.ibcmanager.model.ValidationIssue;
import io.github.ibcmanager.runtime.IbcCommandResult;
import io.github.ibcmanager.runtime.ProfileRuntimeController;
import io.github.ibcmanager.runtime.RuntimeControllerException;
import io.github.ibcmanager.security.CredentialStoreException;
import io.github.ibcmanager.storage.ProfileRepository;
import io.github.ibcmanager.validation.ValidationResult;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;

@SuppressWarnings("serial")
public final class MainFrame extends JFrame {
    private static final String STARTUP_TASK_NAME = "IBC Manager";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final AppServices services;
    private final DefaultListModel<Profile> profilesModel = new DefaultListModel<>();
    private final JList<Profile> profilesList = new JList<>(profilesModel);
    private final JLabel nameValue = valueLabel();
    private final JLabel stateValue = valueLabel();
    private final JLabel targetValue = valueLabel();
    private final JLabel modeValue = valueLabel();
    private final JLabel pidValue = valueLabel();
    private final JLabel commandValue = valueLabel();
    private final JLabel apiValue = valueLabel();
    private final JLabel startedValue = valueLabel();
    private final JLabel messageValue = valueLabel();
    private final JTextArea logArea = new JTextArea();
    private final JLabel statusBar = new JLabel("Ready");
    private final StatusIndicator profileStatusIndicator = new StatusIndicator();
    private final JButton startButton = new JButton("Start");
    private final JButton stopButton = new JButton("Stop");
    private final JButton restartButton = new JButton("Restart");
    private final JButton pauseButton = new JButton("Pause");
    private final JButton editButton = new JButton("Edit");
    private final JButton validateButton = new JButton("Validate");
    private final JButton configButton = new JButton("Managed config");
    private final JButton diagnosticsButton = new JButton("Diagnostics");
    private final JButton reconnectDataButton = new JButton("Reconnect data");
    private final JButton reconnectAccountButton = new JButton("Reconnect account");
    private final JButton enableApiButton = new JButton("Enable API");
    private final Timer uiTimer;
    private List<Profile> profiles = List.of();
    private boolean busy;

    public MainFrame(AppServices services) throws IOException {
        super("IBC Manager " + Version.VERSION);
        this.services = services;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(980, 650));
        setSize(1280, 820);
        buildUi();
        loadProfiles();
        services.runtimeRegistry().addStatusListener(status -> UiUtil.onEdt(() -> {
            profilesList.repaint();
            if (selectedProfileId().filter(status.profileId()::equals).isPresent()) refreshSelected();
        }));
        uiTimer = new Timer(1000, event -> refreshSelected());
        uiTimer.start();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { requestClose(); }
        });
        setLocationRelativeTo(null);
        if (profiles.isEmpty() && !Boolean.getBoolean("ibcmanager.suppressFirstRunWizard")) {
            SwingUtilities.invokeLater(() -> editProfile(null));
        }
    }

    public void startAutoProfiles() {
        services.runtimeRegistry().startAutoStartProfiles();
    }

    private void buildUi() {
        setJMenuBar(createMenuBar());
        add(createToolbar(), BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, createProfilesPanel(), createDetailTabs());
        split.setResizeWeight(0.22);
        split.setDividerLocation(275);
        add(split, BorderLayout.CENTER);
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEtchedBorder(), BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        add(statusBar, BorderLayout.SOUTH);
    }

    private JMenuBar createMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        JMenuItem newProfile = new JMenuItem("New profile...");
        newProfile.addActionListener(event -> editProfile(null));
        JMenuItem editProfile = new JMenuItem("Edit selected profile...");
        editProfile.addActionListener(event -> editSelected());
        JMenuItem deleteProfile = new JMenuItem("Delete selected profile...");
        deleteProfile.addActionListener(event -> deleteSelected());
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(event -> requestClose());
        file.add(newProfile);
        file.add(editProfile);
        file.add(deleteProfile);
        file.addSeparator();
        file.add(exit);

        JMenu tools = new JMenu("Tools");
        JMenuItem validate = new JMenuItem("Validate selected profile");
        validate.addActionListener(event -> validateSelected());
        JMenuItem managedConfig = new JMenuItem("Edit managed config.ini...");
        managedConfig.addActionListener(event -> editManagedConfig());
        JMenuItem diagnostics = new JMenuItem("Export diagnostic bundle...");
        diagnostics.addActionListener(event -> exportDiagnostics());
        JMenuItem forceStop = new JMenuItem("Force stop selected profile...");
        forceStop.addActionListener(event -> confirmForceStopSelected());
        JMenuItem openData = new JMenuItem("Open IBC Manager data folder");
        openData.addActionListener(event -> UiUtil.openPath(this, services.paths().root()));
        JMenuItem installStartup = new JMenuItem("Install startup task");
        installStartup.addActionListener(event -> installStartupTask());
        JMenuItem removeStartup = new JMenuItem("Remove startup task");
        removeStartup.addActionListener(event -> removeStartupTask());
        tools.add(validate);
        tools.add(managedConfig);
        tools.add(diagnostics);
        tools.addSeparator();
        tools.add(forceStop);
        tools.add(openData);
        tools.addSeparator();
        tools.add(installStartup);
        tools.add(removeStartup);

        JMenu help = new JMenu("Help");
        JMenuItem about = new JMenuItem("About");
        about.addActionListener(event -> AboutDialog.show(this));
        help.add(about);
        bar.add(file);
        bar.add(tools);
        bar.add(help);
        return bar;
    }

    private JPanel createToolbar() {
        JPanel container = new JPanel(new BorderLayout());
        JToolBar profileToolbar = toolbarRow();
        JToolBar sessionToolbar = toolbarRow();
        JButton newButton = new JButton("New");
        newButton.addActionListener(event -> editProfile(null));
        editButton.addActionListener(event -> editSelected());
        ProfileSessionAction.START.configureButton(startButton);
        ProfileSessionAction.STOP.configureButton(stopButton);
        ProfileSessionAction.RESTART.configureButton(restartButton);
        ProfileSessionAction.PAUSE.configureButton(pauseButton);
        startButton.addActionListener(event -> confirmStartSelected());
        stopButton.addActionListener(event -> confirmStopSelected());
        restartButton.addActionListener(event -> confirmCommand(
                ProfileSessionAction.RESTART, "Restart", ProfileRuntimeController::restartSession));
        pauseButton.addActionListener(event -> confirmCommand(
                ProfileSessionAction.PAUSE, "Pause", ProfileRuntimeController::pause));
        validateButton.addActionListener(event -> validateSelected());
        configButton.addActionListener(event -> editManagedConfig());
        diagnosticsButton.addActionListener(event -> exportDiagnostics());
        profileToolbar.add(newButton);
        profileToolbar.add(editButton);
        profileToolbar.addSeparator();
        profileToolbar.add(validateButton);
        profileToolbar.add(configButton);
        profileToolbar.add(diagnosticsButton);

        JLabel sessionLabel = new JLabel("Session:");
        sessionLabel.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 8));
        sessionToolbar.add(sessionLabel);
        sessionToolbar.add(startButton);
        sessionToolbar.add(stopButton);
        sessionToolbar.add(restartButton);
        sessionToolbar.add(pauseButton);

        container.add(profileToolbar, BorderLayout.NORTH);
        container.add(sessionToolbar, BorderLayout.SOUTH);
        return container;
    }

    private static JToolBar toolbarRow() {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setBorder(BorderFactory.createEmptyBorder(4, 5, 4, 5));
        return toolbar;
    }

    private JPanel createProfilesPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 4));
        JLabel heading = new JLabel("Profiles");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 16f));
        panel.add(heading, BorderLayout.NORTH);
        profilesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        profilesList.setCellRenderer(new ProfileRenderer());
        profilesList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) refreshSelected();
        });
        panel.add(new JScrollPane(profilesList), BorderLayout.CENTER);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton add = new JButton("+");
        add.setToolTipText("New profile");
        add.addActionListener(event -> editProfile(null));
        JButton remove = new JButton("-");
        remove.setToolTipText("Delete selected profile");
        remove.addActionListener(event -> deleteSelected());
        controls.add(add);
        controls.add(remove);
        panel.add(controls, BorderLayout.SOUTH);
        return panel;
    }

    private JTabbedPane createDetailTabs() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Overview", createOverviewPanel());
        tabs.addTab("Logs", createLogsPanel());
        tabs.addTab("Commands", createCommandsPanel());
        return tabs;
    }

    private JPanel createOverviewPanel() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        outer.add(profileStatusIndicator, BorderLayout.NORTH);
        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        addDetailRow(form, row++, "Profile", nameValue);
        addDetailRow(form, row++, "State", stateValue);
        addDetailRow(form, row++, "Application", targetValue);
        addDetailRow(form, row++, "Trading mode", modeValue);
        addDetailRow(form, row++, "Process ID", pidValue);
        addDetailRow(form, row++, "IBC command server", commandValue);
        addDetailRow(form, row++, "API TCP listener", apiValue);
        addDetailRow(form, row++, "Started", startedValue);
        addDetailRow(form, row++, "Details", messageValue);
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = 0;
        filler.gridy = row;
        filler.gridwidth = 2;
        filler.weightx = 1;
        filler.weighty = 1;
        filler.fill = GridBagConstraints.BOTH;
        form.add(new JPanel(), filler);
        outer.add(form, BorderLayout.CENTER);
        JLabel apiNotice = new JLabel("<html>API listener detected means the operating system reports a listening TCP socket. "
                + "IBC Manager does not connect to the port for monitoring. This still does not prove that an IB API "
                + "handshake or account validation completed.</html>");
        apiNotice.setBorder(BorderFactory.createEmptyBorder(10, 4, 4, 4));
        outer.add(apiNotice, BorderLayout.SOUTH);
        return outer;
    }

    private JPanel createLogsPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setLineWrap(false);
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton clear = new JButton("Clear view");
        clear.addActionListener(event -> selectedController().ifPresent(controller -> {
            controller.logs().clear();
            logArea.setText("");
        }));
        JButton open = new JButton("Open log folder");
        open.addActionListener(event -> UiUtil.openPath(this, services.paths().logs()));
        buttons.add(clear);
        buttons.add(open);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createCommandsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JLabel notice = new JLabel("<html>These controls use the selected profile's local IBC command server. "
                + "They are enabled after IBC reports that the command server is ready; status monitoring does not "
                + "open a recurring command connection.</html>");
        panel.add(notice, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 12));
        reconnectDataButton.addActionListener(event -> runCommand("Reconnect market data", ProfileRuntimeController::reconnectData));
        reconnectAccountButton.addActionListener(event -> runCommand("Reconnect account", ProfileRuntimeController::reconnectAccount));
        enableApiButton.addActionListener(event -> runCommand("Enable API", ProfileRuntimeController::enableApi));
        buttons.add(reconnectDataButton);
        buttons.add(reconnectAccountButton);
        buttons.add(enableApiButton);
        panel.add(buttons, BorderLayout.CENTER);
        return panel;
    }

    private void loadProfiles() throws IOException {
        UUID selected = selectedProfileId().orElse(null);
        ProfileRepository.LoadResult result = services.profileRepository().loadAll();
        profiles = result.profiles();
        profilesModel.clear();
        for (Profile profile : profiles) profilesModel.addElement(profile);
        services.runtimeRegistry().setProfiles(profiles);
        if (selected != null) selectProfile(selected);
        if (profilesList.getSelectedIndex() < 0 && !profiles.isEmpty()) profilesList.setSelectedIndex(0);
        if (!result.warnings().isEmpty()) {
            JOptionPane.showMessageDialog(this, String.join("\n", result.warnings()),
                    "Profile recovery warnings", JOptionPane.WARNING_MESSAGE);
        }
        refreshSelected();
    }

    private void editSelected() {
        Profile profile = profilesList.getSelectedValue();
        if (profile == null) return;
        if (selectedController().map(controller -> controller.status().processAlive()).orElse(false)) {
            JOptionPane.showMessageDialog(this, "Stop the profile before editing it.",
                    "Profile is running", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            editProfile(services.managedConfigService().synchronizeEditableProfile(profile));
        } catch (IOException ex) {
            UiUtil.showError(this, "Could not synchronize the Profile editor with managed config.ini", ex);
        }
    }

    private void editProfile(Profile current) {
        Optional<ProfileEditResult> edited = ProfileEditorDialog.showDialog(
                this, current, services.credentialStore().isAvailable());
        if (edited.isEmpty()) return;
        try (ProfileEditResult result = edited.get()) {
            Profile candidate = result.profile();
            ValidationResult editValidation = services.profileValidator().validateForEdit(candidate, false);
            List<Profile> candidateSet = new ArrayList<>(profiles);
            candidateSet.removeIf(profile -> profile.id().equals(candidate.id()));
            candidateSet.add(candidate);
            ValidationResult setValidation = services.profileSetValidator().validate(candidateSet);
            List<ValidationIssue> combined = new ArrayList<>(editValidation.issues());
            combined.addAll(setValidation.issues());
            if (candidate.credentialMode() == CredentialMode.ENCRYPTED
                    && !result.hasPassword() && !services.credentialStore().exists(candidate.id())) {
                combined.add(new ValidationIssue(Severity.ERROR, "credentialMode",
                        "Enter a password before enabling encrypted credential mode"));
            }
            ValidationResult all = new ValidationResult(combined);
            if (!all.isValid()) {
                ValidationDialog.show(this, candidate.name(), all);
                return;
            }
            if (all.warningCount() > 0) {
                int choice = JOptionPane.showConfirmDialog(this,
                        "The profile has " + all.warningCount() + " warning(s). Save it anyway?",
                        "Validation warnings", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.YES_OPTION) {
                    ValidationDialog.show(this, candidate.name(), all);
                    return;
                }
            }
            char[] password = result.passwordCopy();
            try {
                services.profileSaveService().save(current, candidate, password);
            } finally {
                Arrays.fill(password, '\0');
            }
            services.runtimeRegistry().upsert(candidate);
            loadProfiles();
            selectProfile(candidate.id());
            setStatus("Saved profile '" + candidate.name() + "'");
        } catch (IOException | CredentialStoreException ex) {
            UiUtil.showError(this, "Could not save profile", ex);
        }
    }

    private void deleteSelected() {
        Profile profile = profilesList.getSelectedValue();
        if (profile == null) return;
        if (selectedController().map(controller -> controller.status().processAlive()).orElse(false)) {
            JOptionPane.showMessageDialog(this, "Stop the profile before deleting it.",
                    "Profile is running", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "Delete profile '" + profile.name() + "'? Logs are retained.",
                "Delete profile", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;
        try {
            services.profileDeletionService().delete(profile);
            loadProfiles();
            setStatus("Deleted profile '" + profile.name() + "'");
        } catch (IOException | CredentialStoreException ex) {
            UiUtil.showError(this, "Could not delete profile", ex);
        }
    }

    private void confirmStartSelected() {
        Optional<ProfileRuntimeController> selected = selectedController();
        if (selected.isEmpty()) return;
        ProfileRuntimeController controller = selected.get();
        if (!ProfileSessionAction.START.confirm(this, controller.profile())) return;
        start(controller);
    }

    private void start(ProfileRuntimeController controller) {
        runAsync("Starting profile", () -> {
            controller.start();
            return null;
        }, ignored -> setStatus("Started '" + controller.profile().name() + "'"));
    }

    private void confirmStopSelected() {
        Optional<ProfileRuntimeController> selected = selectedController();
        if (selected.isEmpty()) return;
        ProfileRuntimeController controller = selected.get();
        if (!ProfileSessionAction.STOP.confirm(this, controller.profile())) return;
        stop(controller, false);
    }

    private void stopSelected(boolean force) {
        Optional<ProfileRuntimeController> selected = selectedController();
        if (selected.isEmpty()) return;
        stop(selected.get(), force);
    }

    private void stop(ProfileRuntimeController controller, boolean force) {
        runAsync(force ? "Force stopping profile" : "Stopping profile", () -> {
            if (force) controller.forceStop(); else controller.stop();
            return null;
        }, ignored -> setStatus("Stopped '" + controller.profile().name() + "'"));
    }

    private void confirmForceStopSelected() {
        Profile profile = profilesList.getSelectedValue();
        Optional<ProfileRuntimeController> controller = selectedController();
        if (profile == null || controller.isEmpty() || !controller.get().status().processAlive()) {
            JOptionPane.showMessageDialog(this, "The selected profile is not running.",
                    "Force stop", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "Force stop '" + profile.name() + "'?\n\n"
                        + "This terminates only the process tree owned by this profile. "
                        + "Use normal Stop first whenever possible.",
                "Confirm force stop", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) stopSelected(true);
    }

    private void runCommand(String label, ControllerCommand operation) {
        Optional<ProfileRuntimeController> selected = selectedController();
        if (selected.isEmpty()) return;
        runCommand(label, selected.get(), operation);
    }

    private void confirmCommand(ProfileSessionAction action, String label, ControllerCommand operation) {
        Optional<ProfileRuntimeController> selected = selectedController();
        if (selected.isEmpty()) return;
        ProfileRuntimeController controller = selected.get();
        if (!action.confirm(this, controller.profile())) return;
        runCommand(label, controller, operation);
    }

    private void runCommand(String label, ProfileRuntimeController controller, ControllerCommand operation) {
        runAsync(label, () -> operation.apply(controller), result -> {
            IbcCommandResult commandResult = result;
            setStatus(label + ": " + commandResult.response().replace('\n', ' '));
        });
    }

    private void validateSelected() {
        Profile profile = profilesList.getSelectedValue();
        if (profile == null) return;
        List<ValidationIssue> issues = new ArrayList<>(services.profileValidator().validate(profile, true).issues());
        issues.addAll(services.profileSetValidator().validate(profiles).issues());
        ValidationDialog.show(this, profile.name(), new ValidationResult(issues));
    }

    private void editManagedConfig() {
        Profile profile = profilesList.getSelectedValue();
        if (profile == null) return;
        if (selectedController().map(controller -> controller.status().processAlive()).orElse(false)) {
            JOptionPane.showMessageDialog(this, "Stop the profile before changing its managed config.ini.",
                    "Profile is running", JOptionPane.WARNING_MESSAGE);
            return;
        }
        var edited = ManagedConfigDialog.show(this, profile, services.managedConfigService());
        if (edited.isEmpty()) return;
        try {
            Profile synchronizedProfile = services.profileSaveService().saveManagedConfig(profile, edited.get());
            services.runtimeRegistry().upsert(synchronizedProfile);
            loadProfiles();
            selectProfile(synchronizedProfile.id());
            statusBar.setText("Managed config.ini saved and synchronized with the Profile editor");
        } catch (IOException ex) {
            UiUtil.showError(this, "Managed configuration was not saved", ex);
        }
    }

    private void exportDiagnostics() {
        Profile profile = profilesList.getSelectedValue();
        Optional<ProfileRuntimeController> controller = selectedController();
        if (profile == null || controller.isEmpty()) return;
        runAsync("Creating diagnostic bundle", () ->
                services.diagnosticBundleService().create(profile, controller.get().status()), path -> {
            setStatus("Diagnostic bundle created: " + path);
            int open = JOptionPane.showConfirmDialog(this,
                    "Diagnostic bundle created:\n" + path + "\n\nOpen its folder?",
                    "Diagnostics", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
            if (open == JOptionPane.YES_OPTION) UiUtil.openPath(this, path.getParent());
        });
    }

    private void installStartupTask() {
        if (!services.taskSchedulerService().isAvailable()) {
            JOptionPane.showMessageDialog(this, "Windows Task Scheduler integration is unavailable.",
                    "Startup task", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Optional<StartupCommand> command = StartupCommand.detect();
        if (command.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Build or run the packaged JAR/application before installing a startup task.",
                    "Startup task", JOptionPane.WARNING_MESSAGE);
            return;
        }
        runAsync("Installing startup task", () -> {
            services.taskSchedulerService().installAtLogon(STARTUP_TASK_NAME,
                    command.get().executable(), command.get().arguments(), Duration.ofMinutes(1));
            return null;
        }, ignored -> setStatus("Installed Windows startup task"));
    }

    private void removeStartupTask() {
        if (!services.taskSchedulerService().isAvailable()) return;
        runAsync("Removing startup task", () -> {
            services.taskSchedulerService().remove(STARTUP_TASK_NAME);
            return null;
        }, ignored -> setStatus("Removed Windows startup task"));
    }

    private void refreshSelected() {
        Profile profile = profilesList.getSelectedValue();
        Optional<ProfileRuntimeController> controller = selectedController();
        if (profile == null || controller.isEmpty()) {
            clearDetails();
            updateButtons(null);
            return;
        }
        ProfileStatus status = controller.get().status();
        profileStatusIndicator.updateStatus(status);
        nameValue.setText(profile.name());
        stateValue.setText(formatState(status.state()));
        targetValue.setText(profile.targetType().toString() + " " + profile.twsMajorVersion());
        modeValue.setText(profile.tradingMode().toString());
        pidValue.setText(status.pid() > 0 ? Long.toString(status.pid()) : "-");
        commandValue.setText(status.commandPortOpen()
                ? "Ready on " + profile.bindAddress() + ":" + profile.commandServerPort() + " (reported by IBC)"
                : "Not ready (" + profile.bindAddress() + ":" + profile.commandServerPort() + ")");
        commandValue.setToolTipText("IBC Manager derives command-server readiness from IBC lifecycle output "
                + "and real commands; it does not open a monitoring connection every two seconds.");
        io.github.ibcmanager.runtime.ListenerObservation apiObservation =
                controller.get().apiListenerObservation();
        apiValue.setText(switch (status.apiListenerState()) {
            case LISTENING -> "Verified listener on "
                    + (apiObservation.localAddress().isBlank() ? "127.0.0.1" : apiObservation.localAddress())
                    + ":" + profile.apiPort() + " (PID " + apiObservation.owningPid() + ")";
            case NOT_LISTENING -> "Not listening (127.0.0.1:" + profile.apiPort() + ")";
            case UNKNOWN -> apiObservation.state() == io.github.ibcmanager.model.PortListenerState.LISTENING
                    ? "Listener detected but ownership is unverified"
                            + (apiObservation.ownershipAvailable() ? " (PID " + apiObservation.owningPid() + ")" : "")
                    : "Listener state unavailable (127.0.0.1:" + profile.apiPort() + ")";
        });
        apiValue.setToolTipText("IBC Manager passively inspects the operating-system listener table; "
                + "it does not open a raw API connection for health monitoring.");
        startedValue.setText(status.startedAt() == null ? "-" : TIME_FORMAT.format(status.startedAt()));
        messageValue.setText("<html>" + html(status.message()) + "</html>");
        List<String> logLines = controller.get().logs().snapshot();
        String text = String.join(System.lineSeparator(), logLines);
        if (!text.equals(logArea.getText())) {
            boolean atBottom = logArea.getCaretPosition() >= Math.max(0, logArea.getDocument().getLength() - 2);
            logArea.setText(text);
            if (atBottom) logArea.setCaretPosition(logArea.getDocument().getLength());
        }
        updateButtons(status);
        profilesList.repaint();
    }

    private void updateButtons(ProfileStatus status) {
        Optional<ProfileRuntimeController> selectedController = selectedController();
        boolean selected = status != null && selectedController.isPresent();
        boolean running = selected && status.processAlive();
        ProfileRuntimeController controller = selectedController.orElse(null);
        startButton.setEnabled(!busy && selected && !running);
        stopButton.setEnabled(!busy && running);
        restartButton.setEnabled(!busy && controller != null && controller.canRestartSession());
        pauseButton.setEnabled(!busy && controller != null && controller.canExecute(io.github.ibcmanager.runtime.IbcCommand.PAUSE));
        editButton.setEnabled(!busy && selected && !running);
        validateButton.setEnabled(!busy && selected);
        configButton.setEnabled(!busy && selected && !running);
        diagnosticsButton.setEnabled(!busy && selected);
        reconnectDataButton.setEnabled(!busy && controller != null
                && controller.canExecute(io.github.ibcmanager.runtime.IbcCommand.RECONNECTDATA));
        reconnectAccountButton.setEnabled(!busy && controller != null
                && controller.canExecute(io.github.ibcmanager.runtime.IbcCommand.RECONNECTACCOUNT));
        Profile selectedProfile = profilesList.getSelectedValue();
        boolean twsSelected = selectedProfile != null && selectedProfile.targetType() == TargetType.TWS;
        enableApiButton.setEnabled(!busy && controller != null
                && controller.canExecute(io.github.ibcmanager.runtime.IbcCommand.ENABLEAPI));
        enableApiButton.setToolTipText(twsSelected
                ? "Enable API connections after IBC has confirmed login and main-window readiness"
                : "IBC's ENABLEAPI command is supported by TWS, not IB Gateway");
    }

    private void clearDetails() {
        profileStatusIndicator.updateStatus(null);
        for (JLabel label : List.of(nameValue, stateValue, targetValue, modeValue, pidValue,
                commandValue, apiValue, startedValue, messageValue)) label.setText("-");
        logArea.setText("");
    }

    private Optional<ProfileRuntimeController> selectedController() {
        return selectedProfileId().flatMap(services.runtimeRegistry()::controller);
    }

    private Optional<UUID> selectedProfileId() {
        Profile selected = profilesList.getSelectedValue();
        return selected == null ? Optional.empty() : Optional.of(selected.id());
    }

    private void selectProfile(UUID id) {
        for (int index = 0; index < profilesModel.size(); index++) {
            if (profilesModel.get(index).id().equals(id)) {
                profilesList.setSelectedIndex(index);
                profilesList.ensureIndexIsVisible(index);
                return;
            }
        }
    }

    private <T> void runAsync(String label, Callable<T> operation, java.util.function.Consumer<T> success) {
        if (busy) return;
        busy = true;
        setStatus(label + "...");
        updateButtons(selectedController().map(ProfileRuntimeController::status).orElse(null));
        UiUtil.runAsync(this, label + " failed", operation, success, () -> {
            busy = false;
            refreshSelected();
        });
    }

    private void requestClose() {
        long running = services.runtimeRegistry().controllers().stream()
                .filter(controller -> controller.status().processAlive()).count();
        if (running > 0) {
            int choice = JOptionPane.showConfirmDialog(this,
                    running + " IBC profile(s) are still running. Closing IBC Manager will not terminate them. Continue?",
                    "Profiles still running", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;
        }
        List<ProfileRuntimeController> blocked = services.runtimeRegistry().controllersBlockingManagerExit();
        if (!blocked.isEmpty()) {
            String names = blocked.stream().map(controller -> controller.profile().name())
                    .collect(java.util.stream.Collectors.joining(", "));
            JOptionPane.showMessageDialog(this,
                    "IBC Manager cannot close securely because temporary runtime configurations for these profiles "
                            + "are still in use or could not yet be removed safely:\n"
                            + names + "\n\nWait until startup completes, or stop the profiles and retry.",
                    "Temporary credentials still present", JOptionPane.WARNING_MESSAGE);
            return;
        }
        uiTimer.stop();
        dispose();
    }

    private void setStatus(String text) {
        statusBar.setText(text == null ? "" : text);
    }

    private static void addDetailRow(JPanel panel, int row, String label, JLabel value) {
        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0;
        left.gridy = row;
        left.insets = new Insets(7, 6, 7, 12);
        left.anchor = GridBagConstraints.NORTHWEST;
        JLabel title = new JLabel(label);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        panel.add(title, left);
        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1;
        right.gridy = row;
        right.insets = new Insets(7, 6, 7, 6);
        right.anchor = GridBagConstraints.NORTHWEST;
        right.fill = GridBagConstraints.HORIZONTAL;
        right.weightx = 1;
        panel.add(value, right);
    }

    private static JLabel valueLabel() {
        JLabel label = new JLabel("-");
        label.setVerticalAlignment(SwingConstants.TOP);
        return label;
    }

    private static String formatState(RuntimeState state) {
        return state.name().replace('_', ' ');
    }

    private static String html(String value) {
        return (value == null ? "" : value).replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\n", "<br>");
    }


    private final class ProfileRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof Profile profile) {
                RuntimeState state = services.runtimeRegistry().controller(profile.id())
                        .map(controller -> controller.status().state()).orElse(RuntimeState.STOPPED);
                StatusIndicator.Presentation presentation = StatusIndicator.presentationFor(state);
                label.setIcon(StatusIndicator.iconFor(state, 12));
                label.setIconTextGap(7);
                label.setText(profile.name() + "  -  " + profile.tradingMode()
                        + "  [" + presentation.headline() + "]");
                label.setToolTipText(presentation.headline() + "; " + profile.targetType()
                        + ", API " + profile.apiPort() + ", IBC command " + profile.commandServerPort());
                label.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            }
            return label;
        }
    }


    @FunctionalInterface
    private interface ControllerCommand {
        IbcCommandResult apply(ProfileRuntimeController controller) throws RuntimeControllerException;
    }
}
