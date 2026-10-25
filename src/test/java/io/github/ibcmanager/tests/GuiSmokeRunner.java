package io.github.ibcmanager.tests;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.app.AppServices;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.ui.MainFrame;
import io.github.ibcmanager.ui.AboutDialog;
import io.github.ibcmanager.ui.ProfileEditorDialog;
import io.github.ibcmanager.ui.UiTheme;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JMenu;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class GuiSmokeRunner {
    private GuiSmokeRunner() { }

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("GUI smoke requires an interactive display or Xvfb");
        }
        if (args.length != 0 && (args.length != 2 || !args[0].equals("--screenshot"))) {
            throw new IllegalArgumentException("Usage: GuiSmokeRunner [--screenshot output.png]");
        }
        Path screenshot = args.length == 2 ? Path.of(args[1]) : null;
        Path root = TestSupport.tempDirectory("gui-smoke");
        String previous = System.getProperty("ibcmanager.suppressFirstRunWizard");
        System.setProperty("ibcmanager.suppressFirstRunWizard", "true");
        try {
            AppPaths paths = new AppPaths(root.resolve("data"));
            Profile profile = TestSupport.validProfile(root.resolve("install"));
            try (AppServices services = AppServices.create(paths)) {
                services.profileRepository().save(profile);
                AtomicReference<MainFrame> frameRef = new AtomicReference<>();
                AtomicReference<ProfileEditorDialog> profileDialogRef = new AtomicReference<>();
                AtomicReference<Throwable> failure = new AtomicReference<>();
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        UiTheme.install();
                        MainFrame frame = new MainFrame(services);
                        frameRef.set(frame);
                        frame.setVisible(true);
                        Constructor<ProfileEditorDialog> constructor = ProfileEditorDialog.class
                                .getDeclaredConstructor(java.awt.Frame.class, Profile.class, boolean.class);
                        constructor.setAccessible(true);
                        ProfileEditorDialog profileDialog = constructor.newInstance(frame, null, false);
                        profileDialog.setModal(false);
                        profileDialogRef.set(profileDialog);
                        profileDialog.setVisible(true);
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    }
                });
                if (failure.get() != null) throw new AssertionError("Could not create real main window", failure.get());
                Thread.sleep(750);
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        MainFrame frame = frameRef.get();
                        require(frame != null, "main frame was not created");
                        require(frame.isShowing(), "main frame is not visible");
                        require(frame.getTitle().startsWith("IBC Manager 2.3.0"), "window title is incorrect");
                        require(frame.getJMenuBar() != null && frame.getJMenuBar().getMenuCount() == 3,
                                "menu bar is incomplete");
                        JMenu tools = frame.getJMenuBar().getMenu(1);
                        boolean forceStopPresent = false;
                        for (int index = 0; index < tools.getItemCount(); index++) {
                            if (tools.getItem(index) != null
                                    && "Force stop selected profile...".equals(tools.getItem(index).getText())) {
                                forceStopPresent = true;
                            }
                        }
                        for (int index = 0; index < tools.getItemCount(); index++) {
                            if (tools.getItem(index) != null) require(!tools.getItem(index).getText().contains("managed config"),
                                    "raw config menu must not exist");
                        }
                        require(forceStopPresent, "force-stop recovery action is missing from Tools menu");
                        List<Component> components = descendants(frame);
                        require(components.stream().filter(JLabel.class::isInstance).map(JLabel.class::cast)
                                        .noneMatch(label -> label.getText() != null
                                                && (label.getText().contains("API listener detected means")
                                                || label.getText().contains("handshake is not verified"))),
                                "permanent API disclaimer must not be displayed");
                        JLabel apiValue = named(components, "profileApiValue", JLabel.class);
                        require(apiValue.getToolTipText().contains("Your trading application verifies"),
                                "API verification explanation remains available on hover");
                        JList<?> profileList = components.stream().filter(JList.class::isInstance)
                                .map(JList.class::cast).findFirst().orElseThrow();
                        require(profileList.getModel().getSize() == 1, "saved profile was not loaded into dashboard");
                        require(profileList.getSelectedIndex() == 0, "first profile was not selected");
                        JTabbedPane tabs = components.stream().filter(JTabbedPane.class::isInstance)
                                .map(JTabbedPane.class::cast).findFirst().orElseThrow();
                        require(tabs.getTabCount() == 3, "dashboard detail tabs are incomplete");
                        require("Overview".equals(tabs.getTitleAt(0)), "overview tab missing");
                        require("Logs".equals(tabs.getTitleAt(1)), "logs tab missing");
                        require("Commands".equals(tabs.getTitleAt(2)), "commands tab missing");
                        Component statusIndicator = components.stream()
                                .filter(component -> "profileStatusIndicator".equals(component.getName()))
                                .findFirst().orElseThrow();
                        require(statusIndicator.isVisible(), "profile status indicator is not visible");
                        require(statusIndicator.getAccessibleContext().getAccessibleDescription() != null
                                        && statusIndicator.getAccessibleContext().getAccessibleDescription().contains("stopped"),
                                "profile status indicator lacks explicit state text");
                        require(components.stream().filter(JButton.class::isInstance)
                                        .map(JButton.class::cast)
                                        .noneMatch(button -> "Enable API".equals(button.getText()) && button.isVisible()),
                                "TWS-only ENABLEAPI is hidden for a Gateway profile");
                        JButton start = named(components, "startProfileButton", JButton.class);
                        JButton stop = named(components, "stopProfileButton", JButton.class);
                        JButton forceStop = named(components, "forceStopProfileButton", JButton.class);
                        JButton restart = named(components, "restartProfileButton", JButton.class);
                        JButton pause = named(components, "pauseProfileButton", JButton.class);
                        for (JButton action : List.of(start, stop, forceStop, restart, pause)) {
                            require(action.getWidth() >= action.getPreferredSize().width,
                                    action.getText() + " button was compressed horizontally");
                            require(action.getHeight() >= action.getPreferredSize().height,
                                    action.getText() + " button was compressed vertically");
                            require(action.getPreferredSize().width >= 112,
                                    action.getText() + " button is not visibly wide enough");
                            require(action.getPreferredSize().height >= 38,
                                    action.getText() + " button is not visibly tall enough");
                            require(action.getFont().isBold(), action.getText() + " button is not visually emphasized");
                        }
                        List<JButton> actions = List.of(start, stop, forceStop, restart, pause);
                        for (int first = 0; first < actions.size(); first++) {
                            for (int second = first + 1; second < actions.size(); second++) {
                                require(!actions.get(first).getForeground().equals(actions.get(second).getForeground()),
                                        actions.get(first).getText() + " and " + actions.get(second).getText()
                                                + " buttons are not visually distinct");
                            }
                        }

                        require(components.stream().filter(JButton.class::isInstance).map(JButton.class::cast)
                                .noneMatch(b -> b.getText().equals("Managed config")), "no duplicate configuration toolbar");
                        JDialog profileDialog = profileDialogRef.get();
                        require(profileDialog != null && profileDialog.isShowing(),
                                "profile editor was not created and displayed");
                        List<Component> profileComponents = descendants(profileDialog);
                        JButton copyButton = named(components, "copyProfileButton", JButton.class);
                        require(copyButton.isEnabled(), "Copy profile is available for a selected profile");
                        javax.swing.JComboBox<?> application = named(profileComponents, "applicationTypeBox", javax.swing.JComboBox.class);
                        require(application.getItemCount() == 2, "Gateway and TWS are offered");
                        application.setSelectedItem(io.github.ibcmanager.model.TargetType.TWS);
                        require(application.getSelectedItem() == io.github.ibcmanager.model.TargetType.TWS, "TWS selection persists");
                        application.setSelectedItem(io.github.ibcmanager.model.TargetType.GATEWAY);
                        JCheckBox workaround = named(profileComponents, "managedHeadlessRuntimeBox", JCheckBox.class);
                        require("Fix Java 25 headless startup with managed JBR + JavaFX".equals(workaround.getText()),
                                "checkbox must omit the 1050 prefix without changing Java25 compatibility");
                        require(!workaround.isSelected(), "new profiles do not download a runtime without consent");
                        answerRuntimeConsent(workaround, JOptionPane.NO_OPTION);
                        require(!workaround.isSelected(), "declining workaround leaves it disabled");
                        answerRuntimeConsent(workaround, JOptionPane.YES_OPTION);
                        require(workaround.isSelected(), "accepted workaround is staged in the editor");
                        require(profileComponents.stream().filter(JLabel.class::isInstance).map(JLabel.class::cast)
                                .noneMatch(l -> l.getText().contains("Existing/base config")
                                        || l.getText().contains("After IBC exits on 2FA")), "no hidden external or obsolete policy controls");
                        javax.swing.JTable settings = profileComponents.stream().filter(javax.swing.JTable.class::isInstance)
                                .map(javax.swing.JTable.class::cast).findFirst().orElseThrow();
                        require(settings.getColumnCount() == 4, "effective values and their source are shown");
                        JTabbedPane profileTabs = profileComponents.stream().filter(JTabbedPane.class::isInstance)
                                .map(JTabbedPane.class::cast).findFirst().orElseThrow();
                        require(profileTabs.indexOfTab("Windows startup") >= 0, "startup settings are inside Profile");
                        JButton detect = named(profileComponents, "detectInstallationsButton", JButton.class);
                        Component engine = profileComponents.stream()
                                .filter(component -> "integratedEngineLabel".equals(component.getName()))
                                .findFirst().orElseThrow();
                        require(engine.isVisible(), "included-engine label is not visible");
                        require(profileComponents.stream()
                                        .noneMatch(component -> "installIbcButton".equals(component.getName())),
                                "external IBC installer must not be offered");
                        JTextField secondFactor = named(profileComponents, "secondFactorDeviceField", JTextField.class);
                        JCheckBox autoRecovery = named(profileComponents,
                                "autoRecoverStartupStallBox", JCheckBox.class);
                        JCheckBox secondFactorRetry = named(profileComponents,
                                "secondFactorRetryBox", JCheckBox.class);
                        require(detect.getWidth() >= detect.getPreferredSize().width,
                                "detect-installations button was compressed horizontally");
                        require(detect.getHeight() >= detect.getPreferredSize().height,
                                "detect-installations button was compressed vertically");
                        require(secondFactor.isVisible(), "SecondFactorDevice field is not visible on the profile tab");
                        require(autoRecovery.isVisible(),
                                "automatic stalled-start recovery control is not visible on the profile tab");
                        require(autoRecovery.isSelected(),
                                "new profiles must default to unattended stalled-start recovery");
                        require(secondFactorRetry.isVisible(),
                                "automatic five-minute 2FA retry control is not visible on the profile tab");
                        require(secondFactorRetry.isSelected(),
                                "new profiles must default to repeated 2FA phone notifications");
                        require(secondFactorRetry.getText().contains("5 minutes"),
                                "the 2FA retry control must state the configured threshold");
                        require(secondFactorRetry.getToolTipText().contains("independent retry timer"),
                                "2FA timing must describe the maintained engine timer");
                        require(profileComponents.stream().anyMatch(JScrollPane.class::isInstance),
                                "profile editor must provide scrolling instead of compressing controls");
                        profileTabs.setSelectedIndex(profileTabs.indexOfTab("TWS / Gateway settings"));
                        io.github.ibcmanager.ui.ProfileSettingsTableModel tableModel =
                                (io.github.ibcmanager.ui.ProfileSettingsTableModel) settings.getModel();
                        int resetRow = -1;
                        for (int row = 0; row < tableModel.getRowCount(); row++) {
                            if (tableModel.definitionAt(row).key().equals("LoginDialogDisplayTimeout")) resetRow = row;
                        }
                        require(resetRow >= 0, "login timeout setting exists");
                        int viewRow = settings.convertRowIndexToView(resetRow);
                        settings.setRowSelectionInterval(viewRow, viewRow);
                        require(settings.editCellAt(viewRow, 2), "editable timeout value");
                        ((JTextField) settings.getEditorComponent()).setText("999");
                        profileComponents.stream().filter(JButton.class::isInstance).map(JButton.class::cast)
                                .filter(b -> b.getText().equals("Reset selected to included default"))
                                .findFirst().orElseThrow().doClick();
                        require(!settings.isEditing(), "reset cancels the stale cell editor");
                        require("60".equals(tableModel.getValueAt(resetRow, 2)), "reset cannot be overwritten by an old cell value");
                        profileDialog.dispose();
                        require(!profileDialog.isDisplayable(), "profile editor did not dispose cleanly");
                        assertCopyDialog(frame, services, profile, copyButton);
                        assertTwsDisplay(frame, services, profile);

                        assertConfirmationDialog(start, "Confirm start");
                        stop.setEnabled(true);
                        assertConfirmationDialog(stop, "Confirm stop");
                        restart.setEnabled(true);
                        assertConfirmationDialog(restart, "Confirm restart");
                        pause.setEnabled(true);
                        assertConfirmationDialog(pause, "Confirm pause");
                        require(services.runtimeRegistry().controller(profile.id())
                                        .map(controller -> !controller.status().processAlive()).orElse(false),
                                "cancelling a session confirmation must not start or change the process");

                        JDialog about = AboutDialog.create(frame);
                        about.setModal(false);
                        about.setVisible(true);
                        require(about.isShowing(), "About window displayed");
                        List<Component> aboutComponents = descendants(about);
                        JLabel thanks = named(aboutComponents, "ibcAuthorAcknowledgement", JLabel.class);
                        require(thanks.isShowing(), "IBC acknowledgement visible");
                        require(thanks.getText().contains("Richard L King (rlktradewright)"),
                                "About explicitly credits the upstream author");
                        require(thanks.getText().contains("Thank you"), "About expresses thanks");
                        require(aboutComponents.stream().filter(JLabel.class::isInstance).map(JLabel.class::cast)
                                .anyMatch(label -> label.getText().contains("Steven M. Kearns")),
                                "other upstream contributors also credited");
                        aboutComponents.stream().filter(JButton.class::isInstance).map(JButton.class::cast)
                                .filter(button -> "Close".equals(button.getText())).findFirst().orElseThrow().doClick();
                        require(!about.isDisplayable(), "About Close disposes cleanly");

                        assertDiagnosticExport(frame, services, profile);
                        if (screenshot == null) {
                            frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
                            require(!frame.isDisplayable(), "normal close path did not dispose the window");
                        }
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    }
                });
                if (failure.get() != null) throw new AssertionError("GUI smoke assertion failed", failure.get());
                if (screenshot != null) GuiScreenshotFixture.capture(frameRef.get(), services, profile, screenshot);
            }
            System.out.println("GUI SMOKE PASSED");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) {
                    if (window instanceof MainFrame frame) {
                        try {
                            var field = MainFrame.class.getDeclaredField("uiTimer");
                            field.setAccessible(true);
                            ((Timer) field.get(frame)).stop();
                        } catch (ReflectiveOperationException ex) { throw new AssertionError("GUI timer cleanup failed", ex); }
                    }
                    window.dispose();
                }
            });
            if (previous == null) System.clearProperty("ibcmanager.suppressFirstRunWizard");
            else System.setProperty("ibcmanager.suppressFirstRunWizard", previous);
            TestSupport.deleteTree(root);
        }
    }

    /** Exercise the actual GUI export callback, not just the already-tested ZIP service. */
    private static void assertDiagnosticExport(MainFrame frame, AppServices services, Profile profile) throws Exception {
        var controller = services.runtimeRegistry().controller(profile.id()).orElseThrow();
        String marker = "GUI_DIAGNOSTIC_BUFFER_ONLY_SENTINEL";
        java.nio.file.Files.writeString(profile.twsSettingsPath().resolve("launcher.log"), "GUI_BROKER_LAUNCHER_SENTINEL token=broker-secret\n");
        controller.logs().append(marker);
        controller.logs().append("error token=gui-export-secret");
        Path diskLog = services.paths().profileLog(profile.id());
        require(!java.nio.file.Files.exists(diskLog) || !java.nio.file.Files.readString(diskLog).contains(marker),
                "fixture marker must not already exist in the disk tail");
        java.awt.SecondaryLoop loop = java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        AtomicBoolean shown = new AtomicBoolean();
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
        Timer closer = new Timer(40, null);
        closer.addActionListener(event -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog dialog && dialog.isShowing() && "Diagnostics".equals(dialog.getTitle())) {
                    shown.set(true);
                    descendants(dialog).stream().filter(javax.swing.JOptionPane.class::isInstance)
                            .map(javax.swing.JOptionPane.class::cast).findFirst()
                            .ifPresent(pane -> pane.setValue(javax.swing.JOptionPane.NO_OPTION));
                    dialog.dispose(); closer.stop(); SwingUtilities.invokeLater(loop::exit);
                    return;
                }
            }
            if (System.nanoTime() >= deadline) { closer.stop(); loop.exit(); }
        });
        try {
            closer.start();
            var export = MainFrame.class.getDeclaredMethod("exportDiagnostics"); export.setAccessible(true);
            export.invoke(frame);
            loop.enter();
            require(shown.get(), "GUI export must complete and report its bundle");
            Path archive;
            try (var files = java.nio.file.Files.list(services.paths().diagnostics())) {
                archive = files.filter(p -> p.toString().endsWith(".zip")).findFirst().orElseThrow();
            }
            try (var zip = new java.util.zip.ZipFile(archive.toFile())) {
                String live = new String(zip.getInputStream(zip.getEntry("live-runtime-log.txt")).readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                require(live.contains(marker), "actual GUI export must include the current in-memory log");
                require(!live.contains("gui-export-secret"), "GUI export must redact live error text");
                require(!live.contains("No in-memory runtime log"), "the UI must not call the empty-log overload");
                require(zip.getEntry("application-log-inventory.txt") != null, "real GUI export adds application log inventory");
                StringBuilder brokerLogs = new StringBuilder();
                for (var entry : java.util.Collections.list(zip.entries())) if (entry.getName().startsWith("application-logs/")) {
                    brokerLogs.append(new String(zip.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
                require(brokerLogs.toString().contains("GUI_BROKER_LAUNCHER_SENTINEL"), "real UI includes launcher.log");
                require(!brokerLogs.toString().contains("broker-secret"), "broker log content is redacted through GUI");
            }
        } finally { closer.stop(); }
    }

    private static void assertCopyDialog(MainFrame frame, AppServices services, Profile original, JButton action) throws Exception {
        AtomicBoolean copied = new AtomicBoolean();
        AtomicReference<Throwable> problem = new AtomicReference<>();
        Timer responder = new Timer(40, null);
        responder.addActionListener(event -> {
            for (Window window : Window.getWindows()) {
                if (!(window instanceof JDialog dialog) || !dialog.isShowing()) continue;
                if ("Copy profile".equals(dialog.getTitle())) {
                    descendants(dialog).stream().filter(JOptionPane.class::isInstance).map(JOptionPane.class::cast)
                            .findFirst().ifPresent(pane -> pane.setValue(JOptionPane.OK_OPTION));
                } else if (dialog.getTitle().startsWith("Copy profile - ")) {
                    try {
                        var source = ProfileEditorDialog.class.getDeclaredField("source"); source.setAccessible(true);
                        Profile clone = (Profile) source.get(dialog);
                        require(!clone.id().equals(original.id()), "actual Copy callback allocates a new identity");
                        require(!clone.enabled() && !clone.autoStart(), "actual Copy callback cannot auto-launch a session");
                        require(clone.credentialMode() == io.github.ibcmanager.model.CredentialMode.MANUAL, "Copy does not borrow the password association");
                        copied.set(true);
                    } catch (Throwable failure) { problem.set(failure); }
                    finally { dialog.dispose(); responder.stop(); }
                }
            }
        });
        responder.start(); action.doClick(); responder.stop();
        if (problem.get() != null) throw new AssertionError("Copy callback failed", problem.get());
        require(copied.get(), "actual Copy toolbar opens an independently editable profile");
        require(services.profileRepository().loadAll().profiles().size() == 1, "cancelling Copy saves nothing");
        require(services.profileRepository().loadAll().profiles().get(0).id().equals(original.id()), "original is unchanged");
    }

    private static void assertTwsDisplay(MainFrame frame, AppServices services, Profile original) throws Exception {
        Profile tws = original.toBuilder().id(java.util.UUID.randomUUID()).name("TWS display fixture")
                .targetType(io.github.ibcmanager.model.TargetType.TWS).enabled(false).autoStart(false)
                .apiPort(7497).commandServerPort(7498).build();
        var load = MainFrame.class.getDeclaredMethod("loadProfiles"); load.setAccessible(true);
        var select = MainFrame.class.getDeclaredMethod("selectProfile", java.util.UUID.class); select.setAccessible(true);
        services.profileRepository().save(tws); load.invoke(frame); select.invoke(frame, tws.id());
        List<Component> all = descendants(frame);
        JButton enable = named(all, "enableApiButton", JButton.class);
        require(enable.isVisible(), "TWS has its own Enable API control");
        require(!enable.isEnabled(), "TWS API control stays disabled before the actual session is ready");
        JLabel headline = named(all, "profileStatusIndicatorHeadline", JLabel.class);
        require(headline.getText().contains("TWS"), "TWS is not presented as a Gateway session");
        services.profileRepository().delete(tws.id()); load.invoke(frame); select.invoke(frame, original.id());
        require(!enable.isVisible(), "Gateway does not expose TWS-only API commands");
    }

    private static void answerRuntimeConsent(JCheckBox checkbox, int answer) {
        AtomicBoolean shown = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Timer responder = new Timer(40, null);
        responder.addActionListener(event -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog dialog && dialog.isShowing()
                        && "Install managed runtime".equals(dialog.getTitle())) {
                    for (Component child : descendants(dialog)) {
                        if (child instanceof JOptionPane pane) {
                            try {
                                String message = pane.getMessage().toString();
                                require(message.contains("25.0.4-b508.27"), "consent describes exact pinned runtime");
                                require(message.contains("width: 540px"), "consent text must wrap on screen");
                                require(!message.contains("1050 / "), "remove release-specific consent prefix");
                                require(message.contains("Unavailable checksum or runtime checks") && message.contains("not fully verified"),
                                        "actual consent explains that unavailable checks are skipped");
                                require(message.contains("On Save") && message.contains("Manual installations are kept"),
                                        "actual consent retains timing and removal scope");
                                require(message.length() < 800, "visible consent is short rather than a technical essay");
                                shown.set(true);
                            } catch (Throwable ex) { failure.set(ex); }
                            finally { pane.setValue(answer); responder.stop(); }
                            break;
                        }
                    }
                }
            }
        });
        responder.start(); checkbox.doClick(); responder.stop();
        if (failure.get() != null) throw new AssertionError("Runtime consent check failed", failure.get());
        require(shown.get(), "checkbox shows a real consent dialog");
    }

    private static void assertConfirmationDialog(JButton button, String expectedTitle) {
        AtomicBoolean displayed = new AtomicBoolean();
        Timer closer = new Timer(40, null);
        closer.addActionListener(event -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog dialog && dialog.isShowing()
                        && expectedTitle.equals(dialog.getTitle())) {
                    displayed.set(true);
                    dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                    if (dialog.isShowing()) dialog.dispose();
                    closer.stop();
                    return;
                }
            }
        });
        closer.setRepeats(true);
        closer.start();
        button.doClick();
        closer.stop();
        require(displayed.get(), button.getText() + " did not display " + expectedTitle);
    }

    private static List<Component> descendants(Container root) {
        List<Component> result = new ArrayList<>();
        for (Component component : root.getComponents()) {
            result.add(component);
            if (component instanceof Container container) result.addAll(descendants(container));
        }
        return result;
    }

    private static <T extends Component> T named(List<Component> components, String name, Class<T> type) {
        return components.stream()
                .filter(component -> name.equals(component.getName()))
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Named component missing: " + name));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
